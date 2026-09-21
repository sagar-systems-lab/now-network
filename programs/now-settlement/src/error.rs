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
    #[msg("Refresh state cannot accept a new witness claim")]
    RefreshNotClaimable,
    #[msg("Refresh has no funded reward to claim")]
    RewardNotFunded,
    #[msg("No witness slot is available")]
    NoAvailableWitnessSlot,
    #[msg("Claimant already occupies an active witness slot")]
    AlreadyClaimed,
    #[msg("Requester cannot claim its own refresh")]
    SelfClaimProhibited,
    #[msg("Claim duration is invalid")]
    InvalidClaimDuration,
    #[msg("Claim deadline exceeds refresh expiration")]
    ClaimDeadlineAfterRefreshExpiry,
    #[msg("Claimant reward account is not the canonical token account")]
    InvalidClaimantRewardAccount,
    #[msg("Refresh funding lock state is inconsistent")]
    InvalidFundingLockState,
    #[msg("Only the pinned verifier may settle this refresh")]
    UnauthorizedVerifier,
    #[msg("Settlement has already completed")]
    SettlementAlreadyCompleted,
    #[msg("Refresh is not eligible for settlement")]
    SettlementNotEligible,
    #[msg("Settlement operation hash is invalid")]
    InvalidOperationHash,
    #[msg("Settlement payout rule is invalid")]
    InvalidPayoutRule,
    #[msg("Settlement witness configuration is invalid")]
    InvalidWitnessConfiguration,
    #[msg("Required witness slots are not populated")]
    InsufficientSettlementWitnesses,
    #[msg("Settlement recipient account is invalid")]
    InvalidRecipient,
    #[msg("Settlement recipient account count is invalid")]
    InvalidRecipientCount,
    #[msg("Refresh vault balance cannot cover the locked reward")]
    VaultBalanceInvariant,
}
