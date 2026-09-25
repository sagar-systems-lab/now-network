use anchor_lang::prelude::*;

use crate::{
    associated_token_address, execution_hash, validate_active_token_account, validate_reward_mint,
    ClaimStatus, ProtocolConfig, ProtocolError, RefreshEscrow, RefreshStatus, RewardLocked,
    WitnessClaimed, CONFIG_SEED, MAX_WITNESSES_V1, PROTOCOL_VERSION_V1, REFRESH_SEED,
    SPL_TOKEN_PROGRAM_ID,
};

#[derive(Accounts)]
#[instruction(refresh_id: [u8; 32])]
pub struct ClaimWitness<'info> {
    pub claimant: Signer<'info>,

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

    /// CHECK: validated against ProtocolConfig and the standard Token Program.
    pub reward_mint: UncheckedAccount<'info>,

    /// CHECK: validated as claimant's canonical token account for reward mint.
    pub claimant_reward_token_account: UncheckedAccount<'info>,
}

fn claim_deadline(
    now: i64,
    refresh_expires_at: i64,
    claim_duration_seconds: u32,
) -> Result<i64> {
    if now >= refresh_expires_at {
        return err!(ProtocolError::RefreshExpired);
    }
    if claim_duration_seconds == 0 {
        return err!(ProtocolError::InvalidClaimDuration);
    }

    let deadline = match now.checked_add(i64::from(claim_duration_seconds)) {
        Some(value) => value,
        None => return err!(ProtocolError::ArithmeticOverflow),
    };

    if deadline > refresh_expires_at {
        return err!(ProtocolError::ClaimDeadlineAfterRefreshExpiry);
    }

    Ok(deadline)
}

fn validate_claimant_identity(refresh: &RefreshEscrow, claimant: &Pubkey) -> Result<()> {
    if claimant == &refresh.creator {
        return err!(ProtocolError::SelfClaimProhibited);
    }

    for index in 0..MAX_WITNESSES_V1 {
        if refresh.claim_statuses[index] == ClaimStatus::Claimed
            && refresh.claimants[index] == *claimant
        {
            return err!(ProtocolError::AlreadyClaimed);
        }
    }

    Ok(())
}

fn available_claim_slot(refresh: &RefreshEscrow) -> Result<usize> {
    let max_witnesses = usize::from(refresh.max_witnesses);
    if max_witnesses == 0 || max_witnesses > MAX_WITNESSES_V1 {
        return err!(ProtocolError::InvalidWitnessPolicy);
    }

    for index in 0..max_witnesses {
        if refresh.claim_statuses[index] != ClaimStatus::Claimed {
            return Ok(index);
        }
    }

    err!(ProtocolError::NoAvailableWitnessSlot)
}

fn lock_reward_for_first_claim(
    refresh: &mut RefreshEscrow,
    refresh_key: &Pubkey,
) -> Result<Option<[u8; 32]>> {
    match refresh.status {
        RefreshStatus::Open => {
            if refresh.total_funded == 0 {
                return err!(ProtocolError::RewardNotFunded);
            }
            if refresh.funding_locked || refresh.locked_reward_amount != 0 {
                return err!(ProtocolError::InvalidFundingLockState);
            }

            refresh.locked_reward_amount = refresh.total_funded;
            refresh.funding_locked = true;
            refresh.status = RefreshStatus::Locked;

            Ok(Some(execution_hash(
                &refresh.intent_core_hash,
                refresh.locked_reward_amount,
                refresh_key,
            )))
        }
        RefreshStatus::Locked => {
            if !refresh.funding_locked || refresh.locked_reward_amount == 0 {
                return err!(ProtocolError::InvalidFundingLockState);
            }
            Ok(None)
        }
        _ => err!(ProtocolError::RefreshNotClaimable),
    }
}

pub fn handler(
    ctx: Context<ClaimWitness>,
    refresh_id: [u8; 32],
    claim_duration_seconds: u32,
) -> Result<()> {
    let config = &ctx.accounts.config;
    if config.version != PROTOCOL_VERSION_V1 || ctx.accounts.refresh.version != PROTOCOL_VERSION_V1 {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if config.paused {
        return err!(ProtocolError::ProtocolPaused);
    }
    if config.reward_token_program != SPL_TOKEN_PROGRAM_ID {
        return err!(ProtocolError::InvalidTokenProgram);
    }
    if ctx.accounts.refresh.refresh_id != refresh_id {
        return err!(ProtocolError::InvalidRefreshIdentity);
    }
    if ctx.accounts.refresh.reward_mint != config.reward_mint {
        return err!(ProtocolError::InvalidRewardMint);
    }

    validate_reward_mint(
        &ctx.accounts.reward_mint.to_account_info(),
        &config.reward_mint,
    )?;

    let claimant_key = ctx.accounts.claimant.key();
    validate_claimant_identity(&ctx.accounts.refresh, &claimant_key)?;

    let expected_reward_account = associated_token_address(&claimant_key, &config.reward_mint);
    if ctx.accounts.claimant_reward_token_account.key() != expected_reward_account {
        return err!(ProtocolError::InvalidClaimantRewardAccount);
    }
    validate_active_token_account(
        &ctx.accounts.claimant_reward_token_account.to_account_info(),
        &config.reward_mint,
        &claimant_key,
    )?;

    let now = Clock::get()?.unix_timestamp;
    let deadline = claim_deadline(
        now,
        ctx.accounts.refresh.refresh_expires_at,
        claim_duration_seconds,
    )?;
    let slot = available_claim_slot(&ctx.accounts.refresh)?;
    let refresh_key = ctx.accounts.refresh.key();

    let reward_lock_event = {
        let refresh = &mut ctx.accounts.refresh;
        let execution = lock_reward_for_first_claim(refresh, &refresh_key)?;

        refresh.claimants[slot] = claimant_key;
        refresh.claimed_at[slot] = now;
        refresh.claim_deadlines[slot] = deadline;
        refresh.claim_statuses[slot] = ClaimStatus::Claimed;

        execution
    };

    if let Some(execution) = reward_lock_event {
        emit!(RewardLocked {
            refresh: refresh_key,
            locked_reward_amount: ctx.accounts.refresh.locked_reward_amount,
            execution_hash: execution,
        });
    }

    emit!(WitnessClaimed {
        refresh: refresh_key,
        slot: slot as u8,
        claimant: claimant_key,
        claim_deadline: deadline,
    });

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{PayoutRule, VerificationClass};

    fn refresh(
        creator: Pubkey,
        status: RefreshStatus,
        total_funded: u64,
        max_witnesses: u8,
    ) -> RefreshEscrow {
        RefreshEscrow {
            version: PROTOCOL_VERSION_V1,
            refresh_id: [0x11; 32],
            state_id_digest: [0x12; 32],
            intent_core_hash: [0x13; 32],
            creator,
            reward_mint: Pubkey::new_from_array([0x14; 32]),
            vault_token_account: Pubkey::new_from_array([0x15; 32]),
            verifier_authority: Pubkey::new_from_array([0x16; 32]),
            created_at: 100,
            refresh_expires_at: 1_000,
            verification_class: VerificationClass::Fast,
            required_witnesses: 1,
            max_witnesses,
            payout_rule: PayoutRule::SingleWinnerAll,
            status,
            total_funded,
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

    #[test]
    fn requester_wallet_is_hard_rejected() {
        let creator = Pubkey::new_from_array([0x21; 32]);
        let refresh = refresh(creator, RefreshStatus::Open, 500, 1);

        assert!(validate_claimant_identity(&refresh, &creator).is_err());
    }

    #[test]
    fn duplicate_claimant_cannot_occupy_another_slot() {
        let creator = Pubkey::new_from_array([0x21; 32]);
        let claimant = Pubkey::new_from_array([0x22; 32]);
        let mut refresh = refresh(creator, RefreshStatus::Locked, 500, 3);
        refresh.locked_reward_amount = 500;
        refresh.funding_locked = true;
        refresh.claimants[0] = claimant;
        refresh.claim_statuses[0] = ClaimStatus::Claimed;

        assert!(validate_claimant_identity(&refresh, &claimant).is_err());
    }

    #[test]
    fn slots_fill_deterministically_and_respect_max_witnesses() {
        let creator = Pubkey::new_from_array([0x21; 32]);
        let mut refresh = refresh(creator, RefreshStatus::Locked, 500, 2);
        refresh.locked_reward_amount = 500;
        refresh.funding_locked = true;

        assert_eq!(available_claim_slot(&refresh).unwrap(), 0);

        refresh.claim_statuses[0] = ClaimStatus::Claimed;
        assert_eq!(available_claim_slot(&refresh).unwrap(), 1);

        refresh.claim_statuses[1] = ClaimStatus::Claimed;
        assert!(available_claim_slot(&refresh).is_err());

        refresh.claim_statuses[0] = ClaimStatus::Released;
        assert_eq!(available_claim_slot(&refresh).unwrap(), 0);
    }

    #[test]
    fn first_claim_locks_exact_current_reward_once() {
        let creator = Pubkey::new_from_array([0x21; 32]);
        let refresh_key = Pubkey::new_from_array([0x31; 32]);
        let mut refresh = refresh(creator, RefreshStatus::Open, 725, 2);

        let first = lock_reward_for_first_claim(&mut refresh, &refresh_key)
            .unwrap()
            .expect("first claim emits execution identity");

        assert!(refresh.funding_locked);
        assert_eq!(refresh.status, RefreshStatus::Locked);
        assert_eq!(refresh.locked_reward_amount, 725);
        assert_eq!(
            first,
            execution_hash(&refresh.intent_core_hash, 725, &refresh_key),
        );

        let second = lock_reward_for_first_claim(&mut refresh, &refresh_key).unwrap();
        assert!(second.is_none());
        assert_eq!(refresh.locked_reward_amount, 725);
    }

    #[test]
    fn claim_requires_nonzero_reward_and_consistent_lock_state() {
        let creator = Pubkey::new_from_array([0x21; 32]);
        let refresh_key = Pubkey::new_from_array([0x31; 32]);

        let mut empty = refresh(creator, RefreshStatus::Open, 0, 1);
        assert!(lock_reward_for_first_claim(&mut empty, &refresh_key).is_err());

        let mut inconsistent = refresh(creator, RefreshStatus::Locked, 500, 1);
        assert!(lock_reward_for_first_claim(&mut inconsistent, &refresh_key).is_err());
    }

    #[test]
    fn claim_deadline_is_bounded_by_refresh_expiry() {
        assert_eq!(claim_deadline(100, 200, 100).unwrap(), 200);
        assert!(claim_deadline(100, 200, 0).is_err());
        assert!(claim_deadline(100, 200, 101).is_err());
        assert!(claim_deadline(200, 200, 1).is_err());
        assert!(claim_deadline(201, 200, 1).is_err());
    }
}
