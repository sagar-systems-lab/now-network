use anchor_lang::prelude::*;

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
    fn phase0_program_id_is_not_default() {
        assert_ne!(ID, Pubkey::default());
    }
}
