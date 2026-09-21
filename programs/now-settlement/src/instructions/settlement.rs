use anchor_lang::prelude::*;

use crate::{
    associated_token_address, transfer_checked_signed, validate_active_token_account,
    validate_reward_mint, validate_token_program, witness_policy_is_valid, ClaimStatus,
    PayoutRule, ProtocolConfig, ProtocolError, RefreshEscrow, RefreshSettled, RefreshStatus,
    CONFIG_SEED, MAX_WITNESSES_V1, PROTOCOL_VERSION_V1, REFRESH_SEED,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct SettlementPlan {
    amounts: [u64; MAX_WITNESSES_V1],
    recipient_count: usize,
}

fn settlement_plan(refresh: &RefreshEscrow) -> Result<SettlementPlan> {
    if !witness_policy_is_valid(
        refresh.verification_class,
        refresh.required_witnesses,
        refresh.max_witnesses,
        refresh.payout_rule,
    ) {
        return err!(ProtocolError::InvalidWitnessConfiguration);
    }

    let required = usize::from(refresh.required_witnesses);
    if required == 0 || required > MAX_WITNESSES_V1 {
        return err!(ProtocolError::InvalidWitnessConfiguration);
    }

    for index in 0..required {
        if refresh.claim_statuses[index] != ClaimStatus::Claimed
            || refresh.claimants[index] == Pubkey::default()
        {
            return err!(ProtocolError::InsufficientSettlementWitnesses);
        }
    }

    let mut amounts = [0u64; MAX_WITNESSES_V1];

    match refresh.payout_rule {
        PayoutRule::SingleWinnerAll => {
            if required != 1 {
                return err!(ProtocolError::InvalidPayoutRule);
            }
            amounts[0] = refresh.locked_reward_amount;
        }
        PayoutRule::EqualSplitRequiredWitnesses => {
            if required != 2 && required != 3 {
                return err!(ProtocolError::InvalidPayoutRule);
            }

            let divisor = required as u64;
            let base = refresh.locked_reward_amount / divisor;
            let remainder = refresh.locked_reward_amount % divisor;

            amounts[0] = match base.checked_add(remainder) {
                Some(value) => value,
                None => return err!(ProtocolError::ArithmeticOverflow),
            };
            for amount in amounts.iter_mut().take(required).skip(1) {
                *amount = base;
            }
        }
    }

    let mut total = 0u64;
    for amount in amounts.iter().take(required) {
        total = match total.checked_add(*amount) {
            Some(value) => value,
            None => return err!(ProtocolError::ArithmeticOverflow),
        };
    }
    if total != refresh.locked_reward_amount {
        return err!(ProtocolError::ArithmeticOverflow);
    }

    Ok(SettlementPlan {
        amounts,
        recipient_count: required,
    })
}

fn validate_settlement_state(
    refresh: &RefreshEscrow,
    verifier: &Pubkey,
    now: i64,
    settlement_operation_hash: &[u8; 32],
) -> Result<()> {
    if refresh.version != PROTOCOL_VERSION_V1 {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if refresh.status == RefreshStatus::Settled {
        return err!(ProtocolError::SettlementAlreadyCompleted);
    }
    if refresh.status != RefreshStatus::Locked
        || !refresh.funding_locked
        || refresh.locked_reward_amount == 0
    {
        return err!(ProtocolError::SettlementNotEligible);
    }
    if now > refresh.refresh_expires_at {
        return err!(ProtocolError::RefreshExpired);
    }
    if verifier != &refresh.verifier_authority {
        return err!(ProtocolError::UnauthorizedVerifier);
    }
    if settlement_operation_hash == &[0; 32]
        || refresh.settlement_operation_hash != [0; 32]
    {
        return err!(ProtocolError::InvalidOperationHash);
    }
    if refresh.settled_amount != 0 || refresh.settled_at != 0 {
        return err!(ProtocolError::SettlementNotEligible);
    }

    Ok(())
}

fn validate_verification_result_digest(
    verification_result_digest: &[u8; 32],
) -> Result<()> {
    if verification_result_digest == &[0; 32] {
        return err!(ProtocolError::InvalidVerificationDigest);
    }

    Ok(())
}

#[derive(Accounts)]
#[instruction(refresh_id: [u8; 32])]
pub struct SettleRefresh<'info> {
    pub verifier: Signer<'info>,

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

    /// CHECK: validated against the RefreshEscrow canonical vault.
    #[account(mut)]
    pub vault_token_account: UncheckedAccount<'info>,

    /// CHECK: validated against ProtocolConfig and the standard Token Program.
    pub reward_mint: UncheckedAccount<'info>,

    /// CHECK: validated against the standard SPL Token Program ID.
    pub token_program: UncheckedAccount<'info>,
}

pub fn handler<'info>(
    ctx: Context<'info, SettleRefresh<'info>>,
    refresh_id: [u8; 32],
    settlement_operation_hash: [u8; 32],
    verification_result_digest: [u8; 32],
) -> Result<()> {
    let config = &ctx.accounts.config;
    if config.version != PROTOCOL_VERSION_V1 || ctx.accounts.refresh.version != PROTOCOL_VERSION_V1 {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if ctx.accounts.refresh.refresh_id != refresh_id {
        return err!(ProtocolError::InvalidRefreshIdentity);
    }
    if config.reward_token_program != ctx.accounts.token_program.key() {
        return err!(ProtocolError::InvalidTokenProgram);
    }
    if ctx.accounts.refresh.reward_mint != config.reward_mint {
        return err!(ProtocolError::InvalidRewardMint);
    }

    validate_token_program(&ctx.accounts.token_program.to_account_info())?;
    let decimals = validate_reward_mint(
        &ctx.accounts.reward_mint.to_account_info(),
        &config.reward_mint,
    )?;

    let now = Clock::get()?.unix_timestamp;
    let verifier_key = ctx.accounts.verifier.key();
    validate_settlement_state(
        &ctx.accounts.refresh,
        &verifier_key,
        now,
        &settlement_operation_hash,
    )?;
    validate_verification_result_digest(&verification_result_digest)?;

    let plan = settlement_plan(&ctx.accounts.refresh)?;
    if ctx.remaining_accounts.len() != plan.recipient_count {
        return err!(ProtocolError::InvalidRecipientCount);
    }

    let refresh_key = ctx.accounts.refresh.key();
    if ctx.accounts.refresh.vault_token_account != ctx.accounts.vault_token_account.key() {
        return err!(ProtocolError::InvalidVaultTokenAccount);
    }
    let expected_vault = associated_token_address(&refresh_key, &config.reward_mint);
    if ctx.accounts.vault_token_account.key() != expected_vault {
        return err!(ProtocolError::InvalidVaultTokenAccount);
    }

    let vault = validate_active_token_account(
        &ctx.accounts.vault_token_account.to_account_info(),
        &config.reward_mint,
        &refresh_key,
    )?;
    if vault.amount < ctx.accounts.refresh.locked_reward_amount {
        return err!(ProtocolError::VaultBalanceInvariant);
    }

    for index in 0..plan.recipient_count {
        let claimant = ctx.accounts.refresh.claimants[index];
        let destination = &ctx.remaining_accounts[index];
        let expected_destination = associated_token_address(&claimant, &config.reward_mint);

        if destination.key() != expected_destination {
            return err!(ProtocolError::InvalidRecipient);
        }
        validate_active_token_account(destination, &config.reward_mint, &claimant)?;
    }

    let bump = [ctx.accounts.refresh.bump];
    let signer_seeds: &[&[u8]] = &[REFRESH_SEED, refresh_id.as_ref(), bump.as_ref()];
    let signer = &[signer_seeds];

    for index in 0..plan.recipient_count {
        transfer_checked_signed(
            &ctx.accounts.token_program.to_account_info(),
            &ctx.accounts.vault_token_account.to_account_info(),
            &ctx.accounts.reward_mint.to_account_info(),
            &ctx.remaining_accounts[index],
            &ctx.accounts.refresh.to_account_info(),
            plan.amounts[index],
            decimals,
            signer,
        )?;
    }

    let locked_reward_amount = ctx.accounts.refresh.locked_reward_amount;
    let refresh = &mut ctx.accounts.refresh;
    refresh.status = RefreshStatus::Settled;
    refresh.settled_amount = locked_reward_amount;
    refresh.settled_at = now;
    refresh.verification_result_digest = verification_result_digest;
    refresh.settlement_operation_hash = settlement_operation_hash;

    emit!(RefreshSettled {
        refresh: refresh_key,
        settlement_operation_hash,
        locked_reward_amount,
        settled_at: now,
    });

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{VerificationClass, SPL_TOKEN_PROGRAM_ID};

    fn refresh(
        payout_rule: PayoutRule,
        required_witnesses: u8,
        max_witnesses: u8,
        locked_reward_amount: u64,
    ) -> RefreshEscrow {
        let mut claimants = [Pubkey::default(); MAX_WITNESSES_V1];
        let mut claim_statuses = [ClaimStatus::Empty; MAX_WITNESSES_V1];
        for index in 0..usize::from(required_witnesses.min(MAX_WITNESSES_V1 as u8)) {
            claimants[index] = Pubkey::new_from_array([(index as u8) + 1; 32]);
            claim_statuses[index] = ClaimStatus::Claimed;
        }

        let verification_class = if required_witnesses == 1 {
            VerificationClass::Fast
        } else if max_witnesses == required_witnesses {
            VerificationClass::Strict
        } else {
            VerificationClass::Corroborated
        };

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
            verification_class,
            required_witnesses,
            max_witnesses,
            payout_rule,
            status: RefreshStatus::Locked,
            total_funded: locked_reward_amount,
            locked_reward_amount,
            funding_locked: true,
            claimants,
            claimed_at: [101; MAX_WITNESSES_V1],
            claim_deadlines: [900; MAX_WITNESSES_V1],
            claim_statuses,
            settled_amount: 0,
            settled_at: 0,
            verification_result_digest: [0; 32],
            settlement_operation_hash: [0; 32],
            bump: 255,
        }
    }

    #[test]
    fn single_winner_receives_entire_locked_reward() {
        let refresh = refresh(PayoutRule::SingleWinnerAll, 1, 1, 725);
        let plan = settlement_plan(&refresh).unwrap();

        assert_eq!(plan.recipient_count, 1);
        assert_eq!(plan.amounts, [725, 0, 0]);
    }

    #[test]
    fn equal_split_assigns_entire_remainder_to_slot_zero() {
        let two = refresh(PayoutRule::EqualSplitRequiredWitnesses, 2, 2, 101);
        let two_plan = settlement_plan(&two).unwrap();
        assert_eq!(two_plan.recipient_count, 2);
        assert_eq!(two_plan.amounts, [51, 50, 0]);

        let three = refresh(PayoutRule::EqualSplitRequiredWitnesses, 3, 3, 100);
        let three_plan = settlement_plan(&three).unwrap();
        assert_eq!(three_plan.recipient_count, 3);
        assert_eq!(three_plan.amounts, [34, 33, 33]);
    }

    #[test]
    fn settlement_requires_all_required_claimant_slots() {
        let mut refresh = refresh(PayoutRule::EqualSplitRequiredWitnesses, 2, 2, 100);
        refresh.claim_statuses[1] = ClaimStatus::Released;

        assert!(settlement_plan(&refresh).is_err());
    }

    #[test]
    fn settlement_rejects_invalid_witness_configuration() {
        let refresh = refresh(PayoutRule::SingleWinnerAll, 2, 2, 100);
        assert!(settlement_plan(&refresh).is_err());
    }

    #[test]
    fn settlement_state_rejects_duplicate_expired_and_fake_verifier() {
        let mut candidate = refresh(PayoutRule::SingleWinnerAll, 1, 1, 100);
        let verifier = candidate.verifier_authority;
        let operation_hash = [0x44; 32];

        assert!(validate_settlement_state(&candidate, &verifier, 1_000, &operation_hash).is_ok());
        assert!(validate_settlement_state(&candidate, &verifier, 1_001, &operation_hash).is_err());
        assert!(validate_settlement_state(
            &candidate,
            &Pubkey::new_from_array([0x99; 32]),
            1_000,
            &operation_hash,
        )
        .is_err());
        assert!(validate_settlement_state(&candidate, &verifier, 1_000, &[0; 32]).is_err());

        candidate.status = RefreshStatus::Settled;
        candidate.settlement_operation_hash = operation_hash;
        candidate.settled_amount = candidate.locked_reward_amount;
        candidate.settled_at = 999;
        assert!(validate_settlement_state(&candidate, &verifier, 1_000, &operation_hash).is_err());

        let mut refunding = refresh(PayoutRule::SingleWinnerAll, 1, 1, 100);
        refunding.status = RefreshStatus::Refunding;
        assert!(validate_settlement_state(&refunding, &verifier, 1_000, &operation_hash).is_err());
    }

    #[test]
    fn verification_result_digest_must_be_nonzero() {
        assert!(validate_verification_result_digest(&[0; 32]).is_err());
        assert!(validate_verification_result_digest(&[0x55; 32]).is_ok());
    }

    #[test]
    fn standard_token_program_constant_remains_expected() {
        assert_eq!(
            SPL_TOKEN_PROGRAM_ID.to_string(),
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        );
    }
}
