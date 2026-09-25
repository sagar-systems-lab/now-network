use anchor_lang::prelude::*;

use crate::{
    validate_reward_mint, validate_token_program, ProtocolConfig, ProtocolError, CONFIG_SEED,
    INTENT_SCHEMA_VERSION_V1, PROTOCOL_VERSION_V1, SPL_TOKEN_PROGRAM_ID,
};

#[derive(Accounts)]
pub struct InitializeProtocol<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,

    #[account(
        init,
        payer = admin,
        space = ProtocolConfig::SPACE,
        seeds = [CONFIG_SEED],
        bump
    )]
    pub config: Account<'info, ProtocolConfig>,

    /// CHECK: validated as the configured standard SPL reward mint.
    pub reward_mint: UncheckedAccount<'info>,

    /// CHECK: validated against the standard SPL Token Program ID.
    pub token_program: UncheckedAccount<'info>,

    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<InitializeProtocol>, current_verifier_authority: Pubkey) -> Result<()> {
    if current_verifier_authority == Pubkey::default() {
        return err!(ProtocolError::InvalidVerifierAuthority);
    }

    validate_token_program(&ctx.accounts.token_program.to_account_info())?;
    validate_reward_mint(
        &ctx.accounts.reward_mint.to_account_info(),
        &ctx.accounts.reward_mint.key(),
    )?;

    let config = &mut ctx.accounts.config;
    config.version = PROTOCOL_VERSION_V1;
    config.admin_authority = ctx.accounts.admin.key();
    config.current_verifier_authority = current_verifier_authority;
    config.reward_mint = ctx.accounts.reward_mint.key();
    config.reward_token_program = SPL_TOKEN_PROGRAM_ID;
    config.paused = false;
    config.intent_schema_version = INTENT_SCHEMA_VERSION_V1;
    config.bump = ctx.bumps.config;

    Ok(())
}
