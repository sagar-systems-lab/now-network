use anchor_lang::prelude::*;

use crate::constants::MAX_WITNESSES_V1;
use crate::state::{ClaimStatus, PayoutRule, RefreshStatus, VerificationClass};

#[account]
pub struct RefreshEscrow {
    pub version: u16,
    pub refresh_id: [u8; 32],
    pub state_id_digest: [u8; 32],
    pub intent_core_hash: [u8; 32],

    pub creator: Pubkey,
    pub reward_mint: Pubkey,
    pub vault_token_account: Pubkey,
    pub verifier_authority: Pubkey,

    pub created_at: i64,
    pub refresh_expires_at: i64,

    pub verification_class: VerificationClass,
    pub required_witnesses: u8,
    pub max_witnesses: u8,
    pub payout_rule: PayoutRule,

    pub status: RefreshStatus,

    pub total_funded: u64,
    pub locked_reward_amount: u64,
    pub funding_locked: bool,

    pub claimants: [Pubkey; MAX_WITNESSES_V1],
    pub claim_deadlines: [i64; MAX_WITNESSES_V1],
    pub claim_statuses: [ClaimStatus; MAX_WITNESSES_V1],

    pub settled_amount: u64,
    pub settled_at: i64,
    pub verification_result_digest: [u8; 32],
    pub settlement_operation_hash: [u8; 32],

    pub bump: u8,
}

impl RefreshEscrow {
    pub const BODY_LEN: usize =
        2 + // version
        32 + // refresh_id
        32 + // state_id_digest
        32 + // intent_core_hash
        32 + // creator
        32 + // reward_mint
        32 + // vault_token_account
        32 + // verifier_authority
        8 + // created_at
        8 + // refresh_expires_at
        1 + // verification_class
        1 + // required_witnesses
        1 + // max_witnesses
        1 + // payout_rule
        1 + // status
        8 + // total_funded
        8 + // locked_reward_amount
        1 + // funding_locked
        (32 * MAX_WITNESSES_V1) + // claimants
        (8 * MAX_WITNESSES_V1) + // claim_deadlines
        MAX_WITNESSES_V1 + // claim_statuses
        8 + // settled_amount
        8 + // settled_at
        32 + // verification_result_digest
        32 + // settlement_operation_hash
        1; // bump

    pub const SPACE: usize = 8 + Self::BODY_LEN;
}
