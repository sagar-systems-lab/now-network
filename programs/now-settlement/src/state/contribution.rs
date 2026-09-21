use anchor_lang::prelude::*;

#[account]
pub struct Contribution {
    pub version: u16,
    pub refresh: Pubkey,
    pub funder: Pubkey,
    pub amount_contributed: u64,
    pub amount_refunded: u64,
    pub created_at: i64,
    pub last_contributed_at: i64,
    pub bump: u8,
}

impl Contribution {
    pub const BODY_LEN: usize =
        2 + // version
        32 + // refresh
        32 + // funder
        8 + // amount_contributed
        8 + // amount_refunded
        8 + // created_at
        8 + // last_contributed_at
        1; // bump

    pub const SPACE: usize = 8 + Self::BODY_LEN;
}
