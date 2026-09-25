use anchor_lang::prelude::{pubkey, Pubkey};

pub const CONFIG_SEED: &[u8] = b"config";
pub const REFRESH_SEED: &[u8] = b"refresh";
pub const CONTRIBUTION_SEED: &[u8] = b"contribution";

pub const PROTOCOL_VERSION_V1: u16 = 1;
pub const INTENT_SCHEMA_VERSION_V1: u16 = 1;
pub const MAX_WITNESSES_V1: usize = 3;

pub const SPL_TOKEN_PROGRAM_ID: Pubkey =
    pubkey!("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
pub const ASSOCIATED_TOKEN_PROGRAM_ID: Pubkey =
    pubkey!("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
pub const NATIVE_MINT_ID: Pubkey =
    pubkey!("So11111111111111111111111111111111111111112");
