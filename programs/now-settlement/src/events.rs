use anchor_lang::prelude::*;

#[event]
pub struct RewardLocked {
    pub refresh: Pubkey,
    pub locked_reward_amount: u64,
    pub execution_hash: [u8; 32],
}

#[event]
pub struct WitnessClaimed {
    pub refresh: Pubkey,
    pub slot: u8,
    pub claimant: Pubkey,
    pub claim_deadline: i64,
}

#[event]
pub struct RefreshSettled {
    pub refresh: Pubkey,
    pub settlement_operation_hash: [u8; 32],
    pub locked_reward_amount: u64,
    pub settled_at: i64,
}

#[event]
pub struct ContributionRefunded {
    pub refresh: Pubkey,
    pub funder: Pubkey,
    pub amount: u64,
}

#[event]
pub struct RefreshCancelled {
    pub refresh: Pubkey,
    pub creator: Pubkey,
    pub cancelled_at: i64,
}
