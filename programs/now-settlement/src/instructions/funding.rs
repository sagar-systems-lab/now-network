use anchor_lang::prelude::*;

use crate::{
    associated_token_address, transfer_checked, validate_active_token_account,
    validate_reward_mint, validate_token_program, Contribution, ProtocolConfig, ProtocolError,
    RefreshEscrow, RefreshStatus, CONFIG_SEED, CONTRIBUTION_SEED, PROTOCOL_VERSION_V1,
    REFRESH_SEED,
};

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

pub fn handler(
    ctx: Context<Contribute>,
    refresh_id: [u8; 32],
    amount_atomic: u64,
) -> Result<()> {
    let config = &ctx.accounts.config;
    if config.version != PROTOCOL_VERSION_V1 || ctx.accounts.refresh.version != PROTOCOL_VERSION_V1 {
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

    let next_total_funded = match ctx.accounts.refresh.total_funded.checked_add(amount_atomic) {
        Some(value) => value,
        None => return err!(ProtocolError::ArithmeticOverflow),
    };

    let is_new_contribution = ctx.accounts.contribution.version == 0;
    let current_contribution_amount = if is_new_contribution {
        0
    } else {
        if ctx.accounts.contribution.version != PROTOCOL_VERSION_V1
            || ctx.accounts.contribution.refresh != refresh_key
            || ctx.accounts.contribution.funder != funder_key
        {
            return err!(ProtocolError::InvalidContributionIdentity);
        }
        ctx.accounts.contribution.amount_contributed
    };

    let next_contribution_amount = match current_contribution_amount.checked_add(amount_atomic) {
        Some(value) => value,
        None => return err!(ProtocolError::ArithmeticOverflow),
    };

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

    ctx.accounts.contribution.amount_contributed = next_contribution_amount;
    ctx.accounts.contribution.last_contributed_at = now;
    ctx.accounts.refresh.total_funded = next_total_funded;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn funding_arithmetic_rejects_overflow() {
        assert!(u64::MAX.checked_add(1).is_none());
        assert_eq!(41u64.checked_add(1), Some(42));
    }
}
