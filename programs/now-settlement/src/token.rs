use anchor_lang::{
    prelude::*,
    solana_program::{
        instruction::Instruction,
        program::invoke,
    },
};

use crate::{
    ASSOCIATED_TOKEN_PROGRAM_ID, NATIVE_MINT_ID, ProtocolError, SPL_TOKEN_PROGRAM_ID,
};

const TOKEN_ACCOUNT_LEN: usize = 165;
const TOKEN_ACCOUNT_STATE_OFFSET: usize = 108;
const MINT_LEN: usize = 82;
const MINT_DECIMALS_OFFSET: usize = 44;
const MINT_INITIALIZED_OFFSET: usize = 45;
const TRANSFER_CHECKED_TAG: u8 = 12;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TokenAccountFields {
    pub mint: Pubkey,
    pub authority: Pubkey,
    pub amount: u64,
    pub state: u8,
}

pub fn validate_token_program(account: &AccountInfo<'_>) -> Result<()> {
    if account.key != &SPL_TOKEN_PROGRAM_ID || !account.executable {
        return err!(ProtocolError::InvalidTokenProgram);
    }
    Ok(())
}

pub fn validate_reward_mint(account: &AccountInfo<'_>, expected: &Pubkey) -> Result<u8> {
    if account.key != expected {
        return err!(ProtocolError::InvalidRewardMint);
    }
    if account.key == &NATIVE_MINT_ID {
        return err!(ProtocolError::NativeRewardMintUnsupported);
    }
    if account.owner != &SPL_TOKEN_PROGRAM_ID {
        return err!(ProtocolError::InvalidMintAccount);
    }

    let data = account.try_borrow_data()?;
    parse_mint_decimals(&data)
}

pub fn validate_active_token_account(
    account: &AccountInfo<'_>,
    expected_mint: &Pubkey,
    expected_authority: &Pubkey,
) -> Result<TokenAccountFields> {
    if account.owner != &SPL_TOKEN_PROGRAM_ID {
        return err!(ProtocolError::InvalidTokenAccount);
    }

    let data = account.try_borrow_data()?;
    let fields = parse_token_account_data(&data)?;

    if fields.state == 0 {
        return err!(ProtocolError::TokenAccountNotInitialized);
    }
    if fields.state == 2 {
        return err!(ProtocolError::TokenAccountFrozen);
    }
    if fields.state != 1 {
        return err!(ProtocolError::InvalidTokenAccount);
    }
    if &fields.mint != expected_mint {
        return err!(ProtocolError::InvalidTokenMint);
    }
    if &fields.authority != expected_authority {
        return err!(ProtocolError::InvalidTokenAuthority);
    }

    Ok(fields)
}

pub fn associated_token_address(authority: &Pubkey, mint: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(
        &[
            authority.as_ref(),
            SPL_TOKEN_PROGRAM_ID.as_ref(),
            mint.as_ref(),
        ],
        &ASSOCIATED_TOKEN_PROGRAM_ID,
    )
    .0
}

pub fn transfer_checked_instruction(
    source: Pubkey,
    mint: Pubkey,
    destination: Pubkey,
    authority: Pubkey,
    amount: u64,
    decimals: u8,
) -> Instruction {
    let mut data = Vec::with_capacity(10);
    data.push(TRANSFER_CHECKED_TAG);
    data.extend_from_slice(&amount.to_le_bytes());
    data.push(decimals);

    Instruction {
        program_id: SPL_TOKEN_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(source, false),
            AccountMeta::new_readonly(mint, false),
            AccountMeta::new(destination, false),
            AccountMeta::new_readonly(authority, true),
        ],
        data,
    }
}

pub fn transfer_checked<'info>(
    token_program: &AccountInfo<'info>,
    source: &AccountInfo<'info>,
    mint: &AccountInfo<'info>,
    destination: &AccountInfo<'info>,
    authority: &AccountInfo<'info>,
    amount: u64,
    decimals: u8,
) -> Result<()> {
    validate_token_program(token_program)?;

    let instruction = transfer_checked_instruction(
        *source.key,
        *mint.key,
        *destination.key,
        *authority.key,
        amount,
        decimals,
    );

    invoke(
        &instruction,
        &[
            source.clone(),
            mint.clone(),
            destination.clone(),
            authority.clone(),
        ],
    )?;

    Ok(())
}

fn parse_mint_decimals(data: &[u8]) -> Result<u8> {
    if data.len() != MINT_LEN || data[MINT_INITIALIZED_OFFSET] != 1 {
        return err!(ProtocolError::InvalidMintAccount);
    }
    Ok(data[MINT_DECIMALS_OFFSET])
}

fn parse_token_account_data(data: &[u8]) -> Result<TokenAccountFields> {
    if data.len() != TOKEN_ACCOUNT_LEN {
        return err!(ProtocolError::InvalidTokenAccount);
    }

    let mut mint = [0u8; 32];
    mint.copy_from_slice(&data[0..32]);

    let mut authority = [0u8; 32];
    authority.copy_from_slice(&data[32..64]);

    let mut amount = [0u8; 8];
    amount.copy_from_slice(&data[64..72]);

    Ok(TokenAccountFields {
        mint: Pubkey::new_from_array(mint),
        authority: Pubkey::new_from_array(authority),
        amount: u64::from_le_bytes(amount),
        state: data[TOKEN_ACCOUNT_STATE_OFFSET],
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standard_token_layout_is_parsed_without_external_dependencies() {
        let mint = Pubkey::new_from_array([0x11; 32]);
        let authority = Pubkey::new_from_array([0x22; 32]);
        let mut data = [0u8; TOKEN_ACCOUNT_LEN];
        data[0..32].copy_from_slice(mint.as_ref());
        data[32..64].copy_from_slice(authority.as_ref());
        data[64..72].copy_from_slice(&42u64.to_le_bytes());
        data[TOKEN_ACCOUNT_STATE_OFFSET] = 1;

        let fields = parse_token_account_data(&data).expect("valid token account");
        assert_eq!(fields.mint, mint);
        assert_eq!(fields.authority, authority);
        assert_eq!(fields.amount, 42);
        assert_eq!(fields.state, 1);
    }

    #[test]
    fn standard_mint_layout_reads_decimals_only_when_initialized() {
        let mut data = [0u8; MINT_LEN];
        data[MINT_DECIMALS_OFFSET] = 6;
        data[MINT_INITIALIZED_OFFSET] = 1;
        assert_eq!(parse_mint_decimals(&data).unwrap(), 6);

        data[MINT_INITIALIZED_OFFSET] = 0;
        assert!(parse_mint_decimals(&data).is_err());
    }

    #[test]
    fn transfer_checked_encoding_matches_standard_token_instruction() {
        let source = Pubkey::new_from_array([1; 32]);
        let mint = Pubkey::new_from_array([2; 32]);
        let destination = Pubkey::new_from_array([3; 32]);
        let authority = Pubkey::new_from_array([4; 32]);
        let amount = 1_234_567u64;

        let instruction =
            transfer_checked_instruction(source, mint, destination, authority, amount, 6);

        assert_eq!(instruction.program_id, SPL_TOKEN_PROGRAM_ID);
        assert_eq!(instruction.accounts.len(), 4);
        assert_eq!(instruction.accounts[0], AccountMeta::new(source, false));
        assert_eq!(instruction.accounts[1], AccountMeta::new_readonly(mint, false));
        assert_eq!(instruction.accounts[2], AccountMeta::new(destination, false));
        assert_eq!(
            instruction.accounts[3],
            AccountMeta::new_readonly(authority, true)
        );

        let mut expected = vec![TRANSFER_CHECKED_TAG];
        expected.extend_from_slice(&amount.to_le_bytes());
        expected.push(6);
        assert_eq!(instruction.data, expected);
    }

    #[test]
    fn associated_token_address_is_deterministic_and_owner_bound() {
        let authority = Pubkey::new_from_array([0x33; 32]);
        let other_authority = Pubkey::new_from_array([0x34; 32]);
        let mint = Pubkey::new_from_array([0x44; 32]);

        let baseline = associated_token_address(&authority, &mint);

        assert_eq!(baseline, associated_token_address(&authority, &mint));
        assert_ne!(baseline, associated_token_address(&other_authority, &mint));
    }
}
