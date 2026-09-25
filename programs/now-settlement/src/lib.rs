use anchor_lang::prelude::*;

pub mod constants;
pub mod error;
pub mod events;
pub mod identity;
pub mod instructions;
pub mod state;
pub mod token;
pub mod validation;

pub use constants::*;
pub use error::*;
pub use events::*;
pub use identity::*;
pub use instructions::*;
pub use state::*;
pub use token::*;
pub use validation::*;

declare_id!("7nqsPpBhpUwSahMrpuAPNMupx2vVEGqkU6XXcng7VaAm");

#[program]
pub mod now_settlement {
    use super::*;

    pub fn initialize_protocol(
        ctx: Context<InitializeProtocol>,
        current_verifier_authority: Pubkey,
    ) -> Result<()> {
        instructions::config::handler(ctx, current_verifier_authority)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn create_refresh(
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
        instructions::refresh::handler(
            ctx,
            refresh_id,
            state_id_digest,
            intent_core_hash,
            refresh_expires_at,
            verification_class,
            required_witnesses,
            max_witnesses,
            payout_rule,
        )
    }

    pub fn contribute(
        ctx: Context<Contribute>,
        refresh_id: [u8; 32],
        amount_atomic: u64,
    ) -> Result<()> {
        instructions::funding::handler(ctx, refresh_id, amount_atomic)
    }

    pub fn claim_witness(
        ctx: Context<ClaimWitness>,
        refresh_id: [u8; 32],
        claim_duration_seconds: u32,
    ) -> Result<()> {
        instructions::claim::handler(ctx, refresh_id, claim_duration_seconds)
    }

    pub fn cancel_unclaimed_refresh(
        ctx: Context<CancelUnclaimedRefresh>,
        refresh_id: [u8; 32],
    ) -> Result<()> {
        instructions::refund::cancel_handler(ctx, refresh_id)
    }

    pub fn refund_contribution(
        ctx: Context<RefundContribution>,
        refresh_id: [u8; 32],
    ) -> Result<()> {
        instructions::refund::refund_handler(ctx, refresh_id)
    }

    pub fn settle_refresh<'info>(
        ctx: Context<'info, SettleRefresh<'info>>,
        refresh_id: [u8; 32],
        settlement_operation_hash: [u8; 32],
        verification_result_digest: [u8; 32],
        recipient_mask: u8,
    ) -> Result<()> {
        instructions::settlement::handler(
            ctx,
            refresh_id,
            settlement_operation_hash,
            verification_result_digest,
            recipient_mask,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn program_id_is_not_default() {
        assert_ne!(ID, Pubkey::default());
    }

    #[test]
    fn account_sizes_are_fixed() {
        assert_eq!(ProtocolConfig::SPACE, 142);
        assert_eq!(RefreshEscrow::SPACE, 500);
        assert_eq!(Contribution::SPACE, 107);
    }
}
