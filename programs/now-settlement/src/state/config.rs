use anchor_lang::prelude::*;

#[account]
pub struct ProtocolConfig {
    pub version: u16,
    pub admin_authority: Pubkey,
    pub current_verifier_authority: Pubkey,
    pub reward_mint: Pubkey,
    pub reward_token_program: Pubkey,
    pub paused: bool,
    pub intent_schema_version: u16,
    pub bump: u8,
}

impl ProtocolConfig {
    pub const BODY_LEN: usize =
        2 + // version
        32 + // admin_authority
        32 + // current_verifier_authority
        32 + // reward_mint
        32 + // reward_token_program
        1 + // paused
        2 + // intent_schema_version
        1; // bump

    pub const SPACE: usize = 8 + Self::BODY_LEN;
}
