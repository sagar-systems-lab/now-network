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
