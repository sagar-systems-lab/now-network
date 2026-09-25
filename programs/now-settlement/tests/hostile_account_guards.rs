use anchor_lang::prelude::{AccountInfo, Pubkey, Signer};
use now_settlement::{
    associated_token_address, validate_active_token_account, validate_reward_mint,
    validate_token_program, NATIVE_MINT_ID, SPL_TOKEN_PROGRAM_ID,
};

const TOKEN_ACCOUNT_LEN: usize = 165;
const TOKEN_ACCOUNT_STATE_OFFSET: usize = 108;
const MINT_LEN: usize = 82;
const MINT_DECIMALS_OFFSET: usize = 44;
const MINT_INITIALIZED_OFFSET: usize = 45;

fn account_info(
    key: Pubkey,
    owner: Pubkey,
    data: Vec<u8>,
    is_signer: bool,
    is_writable: bool,
    executable: bool,
) -> &'static AccountInfo<'static> {
    let key = Box::leak(Box::new(key));
    let owner = Box::leak(Box::new(owner));
    let lamports = Box::leak(Box::new(0u64));
    let data = Box::leak(data.into_boxed_slice());

    Box::leak(Box::new(AccountInfo::new(
        key,
        is_signer,
        is_writable,
        lamports,
        data,
        owner,
        executable,
    )))
}

fn mint_data(decimals: u8, initialized: bool) -> Vec<u8> {
    let mut data = vec![0u8; MINT_LEN];
    data[MINT_DECIMALS_OFFSET] = decimals;
    data[MINT_INITIALIZED_OFFSET] = u8::from(initialized);
    data
}

fn token_account_data(mint: Pubkey, authority: Pubkey, amount: u64, state: u8) -> Vec<u8> {
    let mut data = vec![0u8; TOKEN_ACCOUNT_LEN];
    data[0..32].copy_from_slice(mint.as_ref());
    data[32..64].copy_from_slice(authority.as_ref());
    data[64..72].copy_from_slice(&amount.to_le_bytes());
    data[TOKEN_ACCOUNT_STATE_OFFSET] = state;
    data
}

#[test]
fn unsigned_authority_is_rejected_by_anchor_signer_parser() {
    let key = Pubkey::new_from_array([0x11; 32]);
    let owner = Pubkey::new_from_array([0x12; 32]);

    let unsigned = account_info(key, owner, vec![], false, false, false);
    assert!(Signer::try_from(unsigned).is_err());

    let signed = account_info(key, owner, vec![], true, false, false);
    assert!(Signer::try_from(signed).is_ok());
}

#[test]
fn token_program_must_match_standard_program_and_be_executable() {
    let owner = Pubkey::new_from_array([0x21; 32]);

    let wrong_program = account_info(
        Pubkey::new_from_array([0x22; 32]),
        owner,
        vec![],
        false,
        false,
        true,
    );
    assert!(validate_token_program(wrong_program).is_err());

    let non_executable = account_info(SPL_TOKEN_PROGRAM_ID, owner, vec![], false, false, false);
    assert!(validate_token_program(non_executable).is_err());

    let valid = account_info(SPL_TOKEN_PROGRAM_ID, owner, vec![], false, false, true);
    assert!(validate_token_program(valid).is_ok());
}

#[test]
fn reward_mint_rejects_substitution_native_mint_wrong_owner_and_uninitialized_data() {
    let expected = Pubkey::new_from_array([0x31; 32]);
    let wrong = Pubkey::new_from_array([0x32; 32]);
    let wrong_owner = Pubkey::new_from_array([0x33; 32]);

    let valid = account_info(
        expected,
        SPL_TOKEN_PROGRAM_ID,
        mint_data(6, true),
        false,
        false,
        false,
    );
    assert_eq!(validate_reward_mint(valid, &expected).unwrap(), 6);

    let substituted = account_info(
        wrong,
        SPL_TOKEN_PROGRAM_ID,
        mint_data(6, true),
        false,
        false,
        false,
    );
    assert!(validate_reward_mint(substituted, &expected).is_err());

    let native = account_info(
        NATIVE_MINT_ID,
        SPL_TOKEN_PROGRAM_ID,
        mint_data(9, true),
        false,
        false,
        false,
    );
    assert!(validate_reward_mint(native, &NATIVE_MINT_ID).is_err());

    let foreign_owned = account_info(
        expected,
        wrong_owner,
        mint_data(6, true),
        false,
        false,
        false,
    );
    assert!(validate_reward_mint(foreign_owned, &expected).is_err());

    let uninitialized = account_info(
        expected,
        SPL_TOKEN_PROGRAM_ID,
        mint_data(6, false),
        false,
        false,
        false,
    );
    assert!(validate_reward_mint(uninitialized, &expected).is_err());

    let malformed = account_info(
        expected,
        SPL_TOKEN_PROGRAM_ID,
        vec![0u8; MINT_LEN - 1],
        false,
        false,
        false,
    );
    assert!(validate_reward_mint(malformed, &expected).is_err());
}

#[test]
fn token_account_rejects_owner_state_mint_and_authority_substitution() {
    let mint = Pubkey::new_from_array([0x41; 32]);
    let authority = Pubkey::new_from_array([0x42; 32]);
    let other_mint = Pubkey::new_from_array([0x43; 32]);
    let other_authority = Pubkey::new_from_array([0x44; 32]);
    let wrong_owner = Pubkey::new_from_array([0x45; 32]);
    let account_key = Pubkey::new_from_array([0x46; 32]);

    let valid = account_info(
        account_key,
        SPL_TOKEN_PROGRAM_ID,
        token_account_data(mint, authority, 500, 1),
        false,
        true,
        false,
    );
    let fields = validate_active_token_account(valid, &mint, &authority).unwrap();
    assert_eq!(fields.mint, mint);
    assert_eq!(fields.authority, authority);
    assert_eq!(fields.amount, 500);

    let foreign_owned = account_info(
        account_key,
        wrong_owner,
        token_account_data(mint, authority, 500, 1),
        false,
        true,
        false,
    );
    assert!(validate_active_token_account(foreign_owned, &mint, &authority).is_err());

    for state in [0u8, 2u8, 3u8] {
        let hostile = account_info(
            account_key,
            SPL_TOKEN_PROGRAM_ID,
            token_account_data(mint, authority, 500, state),
            false,
            true,
            false,
        );
        assert!(validate_active_token_account(hostile, &mint, &authority).is_err());
    }

    let wrong_mint = account_info(
        account_key,
        SPL_TOKEN_PROGRAM_ID,
        token_account_data(other_mint, authority, 500, 1),
        false,
        true,
        false,
    );
    assert!(validate_active_token_account(wrong_mint, &mint, &authority).is_err());

    let wrong_authority = account_info(
        account_key,
        SPL_TOKEN_PROGRAM_ID,
        token_account_data(mint, other_authority, 500, 1),
        false,
        true,
        false,
    );
    assert!(validate_active_token_account(wrong_authority, &mint, &authority).is_err());

    let malformed = account_info(
        account_key,
        SPL_TOKEN_PROGRAM_ID,
        vec![0u8; TOKEN_ACCOUNT_LEN - 1],
        false,
        true,
        false,
    );
    assert!(validate_active_token_account(malformed, &mint, &authority).is_err());
}

#[test]
fn canonical_reward_account_changes_on_authority_or_mint_substitution() {
    let authority = Pubkey::new_from_array([0x51; 32]);
    let wrong_authority = Pubkey::new_from_array([0x52; 32]);
    let mint = Pubkey::new_from_array([0x53; 32]);
    let wrong_mint = Pubkey::new_from_array([0x54; 32]);

    let expected = associated_token_address(&authority, &mint);

    assert_ne!(expected, associated_token_address(&wrong_authority, &mint),);
    assert_ne!(expected, associated_token_address(&authority, &wrong_mint),);
}
