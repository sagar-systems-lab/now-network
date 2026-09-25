use anchor_lang::prelude::*;

use crate::{
    associated_token_address, transfer_checked, validate_active_token_account,
    validate_reward_mint, validate_token_program, Contribution, ProtocolConfig, ProtocolError,
    RefreshEscrow, RefreshStatus, CONFIG_SEED, CONTRIBUTION_SEED, PROTOCOL_VERSION_V1,
    REFRESH_SEED,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct FundingTotals {
    refresh_total_funded: u64,
    contribution_amount: u64,
}

fn next_funding_totals(
    refresh_total_funded: u64,
    contribution_amount: u64,
    amount_atomic: u64,
) -> Result<FundingTotals> {
    if amount_atomic == 0 {
        return err!(ProtocolError::InvalidContributionAmount);
    }

    let refresh_total_funded = match refresh_total_funded.checked_add(amount_atomic) {
        Some(value) => value,
        None => return err!(ProtocolError::ArithmeticOverflow),
    };

    let contribution_amount = match contribution_amount.checked_add(amount_atomic) {
        Some(value) => value,
        None => return err!(ProtocolError::ArithmeticOverflow),
    };

    Ok(FundingTotals {
        refresh_total_funded,
        contribution_amount,
    })
}

fn contribution_identity_matches(
    contribution: &Contribution,
    refresh: &Pubkey,
    funder: &Pubkey,
) -> bool {
    contribution.version == PROTOCOL_VERSION_V1
        && contribution.refresh == *refresh
        && contribution.funder == *funder
}

#[derive(Accounts)]
#[instruction(refresh_id: [u8; 32])]
pub struct Contribute<'info> {
    #[account(mut)]
    pub funder: Signer<'info>,

    #[account(
        seeds = [CONFIG_SEED],
        bump = config.bump
    )]
    pub config: Account<'info, ProtocolConfig>,

    #[account(
        mut,
        seeds = [REFRESH_SEED, refresh_id.as_ref()],
        bump = refresh.bump
    )]
    pub refresh: Account<'info, RefreshEscrow>,

    #[account(
        init_if_needed,
        payer = funder,
        space = Contribution::SPACE,
        seeds = [
            CONTRIBUTION_SEED,
            refresh.key().as_ref(),
            funder.key().as_ref()
        ],
        bump
    )]
    pub contribution: Account<'info, Contribution>,

    /// CHECK: validated as an initialized standard token account owned by funder.
    #[account(mut)]
    pub source_token_account: UncheckedAccount<'info>,

    /// CHECK: validated against the RefreshEscrow canonical vault.
    #[account(mut)]
    pub vault_token_account: UncheckedAccount<'info>,

    /// CHECK: validated against ProtocolConfig and the standard Token Program.
    pub reward_mint: UncheckedAccount<'info>,

    /// CHECK: validated against the standard SPL Token Program ID.
    pub token_program: UncheckedAccount<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<Contribute>, refresh_id: [u8; 32], amount_atomic: u64) -> Result<()> {
    let config = &ctx.accounts.config;
    if config.version != PROTOCOL_VERSION_V1 || ctx.accounts.refresh.version != PROTOCOL_VERSION_V1
    {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if ctx.accounts.refresh.refresh_id != refresh_id {
        return err!(ProtocolError::InvalidRefreshIdentity);
    }
    if config.paused {
        return err!(ProtocolError::ProtocolPaused);
    }
    if ctx.accounts.refresh.status != RefreshStatus::Open {
        return err!(ProtocolError::RefreshNotOpen);
    }
    if ctx.accounts.refresh.funding_locked {
        return err!(ProtocolError::FundingLocked);
    }
    if amount_atomic == 0 {
        return err!(ProtocolError::InvalidContributionAmount);
    }

    let now = Clock::get()?.unix_timestamp;
    if now > ctx.accounts.refresh.refresh_expires_at {
        return err!(ProtocolError::RefreshExpired);
    }

    validate_token_program(&ctx.accounts.token_program.to_account_info())?;
    if config.reward_token_program != ctx.accounts.token_program.key() {
        return err!(ProtocolError::InvalidTokenProgram);
    }
    if ctx.accounts.refresh.reward_mint != config.reward_mint {
        return err!(ProtocolError::InvalidRewardMint);
    }

    let decimals = validate_reward_mint(
        &ctx.accounts.reward_mint.to_account_info(),
        &config.reward_mint,
    )?;

    let funder_key = ctx.accounts.funder.key();
    validate_active_token_account(
        &ctx.accounts.source_token_account.to_account_info(),
        &config.reward_mint,
        &funder_key,
    )?;

    let refresh_key = ctx.accounts.refresh.key();
    let expected_vault = associated_token_address(&refresh_key, &config.reward_mint);
    if ctx.accounts.refresh.vault_token_account != expected_vault
        || ctx.accounts.vault_token_account.key() != expected_vault
    {
        return err!(ProtocolError::InvalidVaultTokenAccount);
    }

    validate_active_token_account(
        &ctx.accounts.vault_token_account.to_account_info(),
        &config.reward_mint,
        &refresh_key,
    )?;

    let is_new_contribution = ctx.accounts.contribution.version == 0;
    let current_contribution_amount = if is_new_contribution {
        0
    } else {
        if !contribution_identity_matches(&ctx.accounts.contribution, &refresh_key, &funder_key) {
            return err!(ProtocolError::InvalidContributionIdentity);
        }
        ctx.accounts.contribution.amount_contributed
    };

    let next = next_funding_totals(
        ctx.accounts.refresh.total_funded,
        current_contribution_amount,
        amount_atomic,
    )?;

    transfer_checked(
        &ctx.accounts.token_program.to_account_info(),
        &ctx.accounts.source_token_account.to_account_info(),
        &ctx.accounts.reward_mint.to_account_info(),
        &ctx.accounts.vault_token_account.to_account_info(),
        &ctx.accounts.funder.to_account_info(),
        amount_atomic,
        decimals,
    )?;

    if is_new_contribution {
        let contribution = &mut ctx.accounts.contribution;
        contribution.version = PROTOCOL_VERSION_V1;
        contribution.refresh = refresh_key;
        contribution.funder = funder_key;
        contribution.amount_contributed = 0;
        contribution.amount_refunded = 0;
        contribution.created_at = now;
        contribution.last_contributed_at = now;
        contribution.bump = ctx.bumps.contribution;
    }

    ctx.accounts.contribution.amount_contributed = next.contribution_amount;
    ctx.accounts.contribution.last_contributed_at = now;
    ctx.accounts.refresh.total_funded = next.refresh_total_funded;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contribution(refresh: Pubkey, funder: Pubkey, amount_contributed: u64) -> Contribution {
        Contribution {
            version: PROTOCOL_VERSION_V1,
            refresh,
            funder,
            amount_contributed,
            amount_refunded: 0,
            created_at: 100,
            last_contributed_at: 100,
            bump: 255,
        }
    }

    #[test]
    fn multiple_funders_accumulate_without_cross_account_mutation() {
        let funder_a_first = next_funding_totals(0, 0, 100).unwrap();
        assert_eq!(
            funder_a_first,
            FundingTotals {
                refresh_total_funded: 100,
                contribution_amount: 100,
            }
        );

        let funder_b_first =
            next_funding_totals(funder_a_first.refresh_total_funded, 0, 40).unwrap();
        assert_eq!(
            funder_b_first,
            FundingTotals {
                refresh_total_funded: 140,
                contribution_amount: 40,
            }
        );

        let funder_a_repeat = next_funding_totals(
            funder_b_first.refresh_total_funded,
            funder_a_first.contribution_amount,
            25,
        )
        .unwrap();

        assert_eq!(funder_a_repeat.refresh_total_funded, 165);
        assert_eq!(funder_a_repeat.contribution_amount, 125);
        assert_eq!(funder_b_first.contribution_amount, 40);
    }

    #[test]
    fn contribution_pda_isolated_by_funder_and_stable_for_replay() {
        let refresh = Pubkey::new_from_array([0x51; 32]);
        let funder_a = Pubkey::new_from_array([0x61; 32]);
        let funder_b = Pubkey::new_from_array([0x62; 32]);

        let funder_a_pda = crate::contribution_pda(&crate::ID, &refresh, &funder_a);
        let funder_a_repeat = crate::contribution_pda(&crate::ID, &refresh, &funder_a);
        let funder_b_pda = crate::contribution_pda(&crate::ID, &refresh, &funder_b);

        assert_eq!(funder_a_pda, funder_a_repeat);
        assert_ne!(funder_a_pda.0, funder_b_pda.0);
    }

    #[test]
    fn contribution_identity_rejects_cross_funder_and_cross_refresh_reuse() {
        let refresh = Pubkey::new_from_array([0x71; 32]);
        let other_refresh = Pubkey::new_from_array([0x72; 32]);
        let funder = Pubkey::new_from_array([0x81; 32]);
        let other_funder = Pubkey::new_from_array([0x82; 32]);
        let record = contribution(refresh, funder, 500);

        assert!(contribution_identity_matches(&record, &refresh, &funder));
        assert!(!contribution_identity_matches(
            &record,
            &refresh,
            &other_funder,
        ));
        assert!(!contribution_identity_matches(
            &record,
            &other_refresh,
            &funder,
        ));

        let mut wrong_version = record;
        wrong_version.version = 0;
        assert!(!contribution_identity_matches(
            &wrong_version,
            &refresh,
            &funder,
        ));
    }

    #[test]
    fn funding_arithmetic_rejects_zero_and_both_overflow_classes() {
        assert!(next_funding_totals(0, 0, 0).is_err());
        assert!(next_funding_totals(u64::MAX, 0, 1).is_err());
        assert!(next_funding_totals(0, u64::MAX, 1).is_err());

        assert_eq!(
            next_funding_totals(41, 17, 1).unwrap(),
            FundingTotals {
                refresh_total_funded: 42,
                contribution_amount: 18,
            }
        );
    }
}
