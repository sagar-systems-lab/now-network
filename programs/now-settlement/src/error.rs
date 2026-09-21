use anchor_lang::prelude::*;

#[error_code]
pub enum ProtocolError {
    #[msg("Protocol activity is paused")]
    ProtocolPaused,
    #[msg("Verifier authority is invalid")]
    InvalidVerifierAuthority,
    #[msg("Only the configured standard SPL Token Program is supported")]
    InvalidTokenProgram,
    #[msg("Reward mint does not match protocol configuration")]
    InvalidRewardMint,
    #[msg("Native SOL reward escrow is not supported")]
    NativeRewardMintUnsupported,
    #[msg("Reward mint account is invalid")]
    InvalidMintAccount,
    #[msg("Token account data is invalid")]
    InvalidTokenAccount,
    #[msg("Token account is not initialized")]
    TokenAccountNotInitialized,
    #[msg("Token account is frozen")]
    TokenAccountFrozen,
    #[msg("Token account mint is invalid")]
    InvalidTokenMint,
    #[msg("Token account authority is invalid")]
    InvalidTokenAuthority,
    #[msg("Refresh vault is not the canonical token account")]
    InvalidVaultTokenAccount,
    #[msg("Refresh expiration must be in the future")]
    InvalidRefreshExpiry,
    #[msg("Witness count and payout rule are inconsistent")]
    InvalidWitnessPolicy,
    #[msg("Refresh is not open for funding")]
    RefreshNotOpen,
    #[msg("Funding is locked for this refresh")]
    FundingLocked,
    #[msg("Refresh has expired")]
    RefreshExpired,
    #[msg("Contribution amount must be greater than zero")]
    InvalidContributionAmount,
    #[msg("Arithmetic overflow")]
    ArithmeticOverflow,
    #[msg("Contribution account identity is invalid")]
    InvalidContributionIdentity,
    #[msg("Protocol account version is unsupported")]
    InvalidProtocolVersion,
    #[msg("Refresh identity does not match the requested refresh")]
    InvalidRefreshIdentity,
    #[msg("Refresh vault must be empty before tracked funding begins")]
    VaultAlreadyFunded,
}
