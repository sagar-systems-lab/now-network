use anchor_lang::prelude::*;

use crate::{
    associated_token_address, transfer_checked_signed, validate_active_token_account,
    validate_reward_mint, validate_token_program, Contribution, ContributionRefunded,
    ProtocolConfig, ProtocolError, RefreshCancelled, RefreshEscrow, RefreshStatus, CONFIG_SEED,
    CONTRIBUTION_SEED, PROTOCOL_VERSION_V1, REFRESH_SEED,
};

fn validate_cancellation(refresh: &RefreshEscrow, creator: &Pubkey) -> Result<()> {
    if refresh.version != PROTOCOL_VERSION_V1 {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if creator != &refresh.creator {
        return err!(ProtocolError::UnauthorizedCreator);
    }
    if refresh.status != RefreshStatus::Open
        || refresh.funding_locked
        || refresh.locked_reward_amount != 0
    {
        return err!(ProtocolError::RefreshNotCancellable);
    }

    Ok(())
}

fn refund_is_eligible(refresh: &RefreshEscrow, now: i64) -> bool {
    match refresh.status {
        RefreshStatus::Cancelled | RefreshStatus::Refunding => true,
        RefreshStatus::Settled | RefreshStatus::Closed => false,
        _ => now > refresh.refresh_expires_at,
    }
}

fn refundable_amount(contribution: &Contribution) -> Result<u64> {
    let remaining = match contribution
        .amount_contributed
        .checked_sub(contribution.amount_refunded)
    {
        Some(value) => value,
        None => return err!(ProtocolError::InvalidRefundAccounting),
    };

    if remaining == 0 {
        return err!(ProtocolError::NothingToRefund);
    }

    Ok(remaining)
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
pub struct CancelUnclaimedRefresh<'info> {
    pub creator: Signer<'info>,

    #[account(
        mut,
        seeds = [REFRESH_SEED, refresh_id.as_ref()],
        bump = refresh.bump
    )]
    pub refresh: Account<'info, RefreshEscrow>,
}

pub fn cancel_handler(ctx: Context<CancelUnclaimedRefresh>, refresh_id: [u8; 32]) -> Result<()> {
    if ctx.accounts.refresh.refresh_id != refresh_id {
        return err!(ProtocolError::InvalidRefreshIdentity);
    }

    let creator_key = ctx.accounts.creator.key();
    validate_cancellation(&ctx.accounts.refresh, &creator_key)?;

    let now = Clock::get()?.unix_timestamp;
    ctx.accounts.refresh.status = RefreshStatus::Cancelled;

    emit!(RefreshCancelled {
        refresh: ctx.accounts.refresh.key(),
        creator: creator_key,
        cancelled_at: now,
    });

    Ok(())
}

#[derive(Accounts)]
#[instruction(refresh_id: [u8; 32])]
pub struct RefundContribution<'info> {
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
        mut,
        seeds = [
            CONTRIBUTION_SEED,
            refresh.key().as_ref(),
            funder.key().as_ref()
        ],
        bump = contribution.bump
    )]
    pub contribution: Account<'info, Contribution>,

    /// CHECK: validated against the RefreshEscrow canonical vault.
    #[account(mut)]
    pub vault_token_account: UncheckedAccount<'info>,

    /// CHECK: validated as the funder's canonical token account for the reward mint.
    #[account(mut)]
    pub destination_token_account: UncheckedAccount<'info>,

    /// CHECK: validated against ProtocolConfig and the standard Token Program.
    pub reward_mint: UncheckedAccount<'info>,

    /// CHECK: validated against the standard SPL Token Program ID.
    pub token_program: UncheckedAccount<'info>,
}

pub fn refund_handler(ctx: Context<RefundContribution>, refresh_id: [u8; 32]) -> Result<()> {
    let config = &ctx.accounts.config;
    if config.version != PROTOCOL_VERSION_V1 || ctx.accounts.refresh.version != PROTOCOL_VERSION_V1
    {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if ctx.accounts.refresh.refresh_id != refresh_id {
        return err!(ProtocolError::InvalidRefreshIdentity);
    }
    if ctx.accounts.refresh.status == RefreshStatus::Settled
        || ctx.accounts.refresh.settled_amount != 0
        || ctx.accounts.refresh.settlement_operation_hash != [0; 32]
    {
        return err!(ProtocolError::RefundNotEligible);
    }

    let now = Clock::get()?.unix_timestamp;
    if !refund_is_eligible(&ctx.accounts.refresh, now) {
        return err!(ProtocolError::RefundNotEligible);
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

    let refresh_key = ctx.accounts.refresh.key();
    let funder_key = ctx.accounts.funder.key();
    if !contribution_identity_matches(&ctx.accounts.contribution, &refresh_key, &funder_key) {
        return err!(ProtocolError::InvalidContributionIdentity);
    }

    let amount = refundable_amount(&ctx.accounts.contribution)?;
    let next_refunded = match ctx
        .accounts
        .contribution
        .amount_refunded
        .checked_add(amount)
    {
        Some(value) => value,
        None => return err!(ProtocolError::ArithmeticOverflow),
    };
    if next_refunded != ctx.accounts.contribution.amount_contributed {
        return err!(ProtocolError::InvalidRefundAccounting);
    }

    let expected_vault = associated_token_address(&refresh_key, &config.reward_mint);
    if ctx.accounts.refresh.vault_token_account != expected_vault
        || ctx.accounts.vault_token_account.key() != expected_vault
    {
        return err!(ProtocolError::InvalidVaultTokenAccount);
    }

    let vault = validate_active_token_account(
        &ctx.accounts.vault_token_account.to_account_info(),
        &config.reward_mint,
        &refresh_key,
    )?;
    if vault.amount < amount {
        return err!(ProtocolError::VaultBalanceInvariant);
    }

    let expected_destination = associated_token_address(&funder_key, &config.reward_mint);
    if ctx.accounts.destination_token_account.key() != expected_destination {
        return err!(ProtocolError::InvalidRefundDestination);
    }
    validate_active_token_account(
        &ctx.accounts.destination_token_account.to_account_info(),
        &config.reward_mint,
        &funder_key,
    )?;

    let bump = [ctx.accounts.refresh.bump];
    let signer_seeds: &[&[u8]] = &[REFRESH_SEED, refresh_id.as_ref(), bump.as_ref()];
    let signer = &[signer_seeds];

    transfer_checked_signed(
        &ctx.accounts.token_program.to_account_info(),
        &ctx.accounts.vault_token_account.to_account_info(),
        &ctx.accounts.reward_mint.to_account_info(),
        &ctx.accounts.destination_token_account.to_account_info(),
        &ctx.accounts.refresh.to_account_info(),
        amount,
        decimals,
        signer,
    )?;

    ctx.accounts.contribution.amount_refunded = next_refunded;
    ctx.accounts.refresh.status = RefreshStatus::Refunding;

    emit!(ContributionRefunded {
        refresh: refresh_key,
        funder: funder_key,
        amount,
    });

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ClaimStatus, PayoutRule, VerificationClass, MAX_WITNESSES_V1};

    fn refresh(status: RefreshStatus) -> RefreshEscrow {
        RefreshEscrow {
            version: PROTOCOL_VERSION_V1,
            refresh_id: [0x11; 32],
            state_id_digest: [0x12; 32],
            intent_core_hash: [0x13; 32],
            creator: Pubkey::new_from_array([0x14; 32]),
            reward_mint: Pubkey::new_from_array([0x15; 32]),
            vault_token_account: Pubkey::new_from_array([0x16; 32]),
            verifier_authority: Pubkey::new_from_array([0x17; 32]),
            created_at: 100,
            refresh_expires_at: 1_000,
            verification_class: VerificationClass::Fast,
            required_witnesses: 1,
            max_witnesses: 1,
            payout_rule: PayoutRule::SingleWinnerAll,
            status,
            total_funded: 500,
            locked_reward_amount: 0,
            funding_locked: false,
            claimants: [Pubkey::default(); MAX_WITNESSES_V1],
            claimed_at: [0; MAX_WITNESSES_V1],
            claim_deadlines: [0; MAX_WITNESSES_V1],
            claim_statuses: [ClaimStatus::Empty; MAX_WITNESSES_V1],
            settled_amount: 0,
            settled_at: 0,
            verification_result_digest: [0; 32],
            settlement_operation_hash: [0; 32],
            bump: 255,
        }
    }

    fn contribution(
        refresh: Pubkey,
        funder: Pubkey,
        amount_contributed: u64,
        amount_refunded: u64,
    ) -> Contribution {
        Contribution {
            version: PROTOCOL_VERSION_V1,
            refresh,
            funder,
            amount_contributed,
            amount_refunded,
            created_at: 100,
            last_contributed_at: 100,
            bump: 255,
        }
    }

    #[test]
    fn cancellation_requires_creator_and_pre_lock_open_state() {
        let creator = Pubkey::new_from_array([0x14; 32]);
        let outsider = Pubkey::new_from_array([0x99; 32]);
        let mut open = refresh(RefreshStatus::Open);

        assert!(validate_cancellation(&open, &creator).is_ok());
        assert!(validate_cancellation(&open, &outsider).is_err());

        open.funding_locked = true;
        open.locked_reward_amount = 500;
        open.status = RefreshStatus::Locked;
        assert!(validate_cancellation(&open, &creator).is_err());
    }

    #[test]
    fn refund_eligibility_matches_cancel_and_expiry_boundary() {
        let open = refresh(RefreshStatus::Open);
        assert!(!refund_is_eligible(&open, 1_000));
        assert!(refund_is_eligible(&open, 1_001));

        let cancelled = refresh(RefreshStatus::Cancelled);
        assert!(refund_is_eligible(&cancelled, 500));

        let refunding = refresh(RefreshStatus::Refunding);
        assert!(refund_is_eligible(&refunding, 500));

        let settled = refresh(RefreshStatus::Settled);
        assert!(!refund_is_eligible(&settled, 2_000));

        let closed = refresh(RefreshStatus::Closed);
        assert!(!refund_is_eligible(&closed, 2_000));
    }

    #[test]
    fn refund_returns_full_remaining_contribution_once() {
        let refresh_key = Pubkey::new_from_array([0x21; 32]);
        let funder = Pubkey::new_from_array([0x22; 32]);

        let untouched = contribution(refresh_key, funder, 500, 0);
        assert_eq!(refundable_amount(&untouched).unwrap(), 500);

        let partial = contribution(refresh_key, funder, 500, 125);
        assert_eq!(refundable_amount(&partial).unwrap(), 375);

        let complete = contribution(refresh_key, funder, 500, 500);
        assert!(refundable_amount(&complete).is_err());

        let invalid = contribution(refresh_key, funder, 499, 500);
        assert!(refundable_amount(&invalid).is_err());
    }

    #[test]
    fn contribution_identity_isolated_by_original_funder() {
        let refresh_key = Pubkey::new_from_array([0x31; 32]);
        let funder = Pubkey::new_from_array([0x32; 32]);
        let other_funder = Pubkey::new_from_array([0x33; 32]);
        let other_refresh = Pubkey::new_from_array([0x34; 32]);
        let record = contribution(refresh_key, funder, 500, 0);

        assert!(contribution_identity_matches(
            &record,
            &refresh_key,
            &funder,
        ));
        assert!(!contribution_identity_matches(
            &record,
            &refresh_key,
            &other_funder,
        ));
        assert!(!contribution_identity_matches(
            &record,
            &other_refresh,
            &funder,
        ));
    }
}
