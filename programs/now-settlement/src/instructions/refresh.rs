use anchor_lang::prelude::*;

use crate::{
    associated_token_address, validate_active_token_account, validate_reward_mint,
    validate_token_program, witness_policy_is_valid, ClaimStatus, PayoutRule, ProtocolConfig,
    ProtocolError, RefreshEscrow, RefreshStatus, VerificationClass, CONFIG_SEED,
    MAX_WITNESSES_V1, PROTOCOL_VERSION_V1, REFRESH_SEED,
};

#[derive(Accounts)]
#[instruction(refresh_id: [u8; 32])]
pub struct CreateRefresh<'info> {
    #[account(mut)]
    pub creator: Signer<'info>,

    #[account(
        seeds = [CONFIG_SEED],
        bump = config.bump
    )]
    pub config: Account<'info, ProtocolConfig>,

    #[account(
        init,
        payer = creator,
        space = RefreshEscrow::SPACE,
        seeds = [REFRESH_SEED, refresh_id.as_ref()],
        bump
    )]
    pub refresh: Account<'info, RefreshEscrow>,

    /// CHECK: validated against ProtocolConfig and the standard Token Program.
    pub reward_mint: UncheckedAccount<'info>,

    /// CHECK: validated as the canonical ATA owned by the RefreshEscrow PDA.
    pub vault_token_account: UncheckedAccount<'info>,

    /// CHECK: validated against the standard SPL Token Program ID.
    pub token_program: UncheckedAccount<'info>,

    pub system_program: Program<'info, System>,
}

#[allow(clippy::too_many_arguments)]
pub fn handler(
    ctx: Context<CreateRefresh>,
    refresh_id: [u8; 32],
    state_id_digest: [u8; 32],
    intent_core_hash: [u8; 32],
    refresh_expires_at: i64,
    verification_class: VerificationClass,
    required_witnesses: u8,
    max_witnesses: u8,
    payout_rule: PayoutRule,
) -> Result<()> {
    let config = &ctx.accounts.config;
    if config.version != PROTOCOL_VERSION_V1 {
        return err!(ProtocolError::InvalidProtocolVersion);
    }
    if config.paused {
        return err!(ProtocolError::ProtocolPaused);
    }

    validate_token_program(&ctx.accounts.token_program.to_account_info())?;
    if config.reward_token_program != ctx.accounts.token_program.key() {
        return err!(ProtocolError::InvalidTokenProgram);
    }

    validate_reward_mint(
        &ctx.accounts.reward_mint.to_account_info(),
        &config.reward_mint,
    )?;

    let now = Clock::get()?.unix_timestamp;
    if refresh_expires_at <= now {
        return err!(ProtocolError::InvalidRefreshExpiry);
    }

    if !witness_policy_is_valid(
        verification_class,
        required_witnesses,
        max_witnesses,
        payout_rule,
    ) {
        return err!(ProtocolError::InvalidWitnessPolicy);
    }

    let refresh_key = ctx.accounts.refresh.key();
    let expected_vault = associated_token_address(&refresh_key, &config.reward_mint);
    if ctx.accounts.vault_token_account.key() != expected_vault {
        return err!(ProtocolError::InvalidVaultTokenAccount);
    }

    let vault = validate_active_token_account(
        &ctx.accounts.vault_token_account.to_account_info(),
        &config.reward_mint,
        &refresh_key,
    )?;
    if vault.amount != 0 {
        return err!(ProtocolError::VaultAlreadyFunded);
    }

    let refresh = &mut ctx.accounts.refresh;
    refresh.version = PROTOCOL_VERSION_V1;
    refresh.refresh_id = refresh_id;
    refresh.state_id_digest = state_id_digest;
    refresh.intent_core_hash = intent_core_hash;
    refresh.creator = ctx.accounts.creator.key();
    refresh.reward_mint = config.reward_mint;
    refresh.vault_token_account = expected_vault;
    refresh.verifier_authority = config.current_verifier_authority;
    refresh.created_at = now;
    refresh.refresh_expires_at = refresh_expires_at;
    refresh.verification_class = verification_class;
    refresh.required_witnesses = required_witnesses;
    refresh.max_witnesses = max_witnesses;
    refresh.payout_rule = payout_rule;
    refresh.status = RefreshStatus::Open;
    refresh.total_funded = 0;
    refresh.locked_reward_amount = 0;
    refresh.funding_locked = false;
    refresh.claimants = [Pubkey::default(); MAX_WITNESSES_V1];
    refresh.claim_deadlines = [0; MAX_WITNESSES_V1];
    refresh.claim_statuses = [ClaimStatus::Empty; MAX_WITNESSES_V1];
    refresh.settled_amount = 0;
    refresh.settled_at = 0;
    refresh.verification_result_digest = [0; 32];
    refresh.settlement_operation_hash = [0; 32];
    refresh.bump = ctx.bumps.refresh;

    Ok(())
}
