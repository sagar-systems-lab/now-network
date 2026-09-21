use anchor_lang::prelude::*;

pub mod constants;
pub mod identity;
pub mod state;

pub use constants::*;
pub use identity::*;
pub use state::*;

declare_id!("6HnAnrNjHWzyJ6RSDZtQ9mPWGwYehSmw1H8T2RKBwWwA");

#[program]
pub mod now_settlement {
    use super::*;

    pub fn initialize(_ctx: Context<Initialize>) -> Result<()> {
        Ok(())
    }
}

#[derive(Accounts)]
pub struct Initialize {}

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
        assert_eq!(RefreshEscrow::SPACE, 476);
        assert_eq!(Contribution::SPACE, 107);
    }
}
