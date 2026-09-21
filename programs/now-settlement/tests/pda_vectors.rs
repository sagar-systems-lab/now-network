use std::str::FromStr;

use anchor_lang::prelude::Pubkey;
use now_settlement::{config_pda, contribution_pda, refresh_pda, ID};
use serde_json::Value;

fn vector() -> Value {
    serde_json::from_str(include_str!(
        "../../../test-vectors/solana-pda-v1.json"
    ))
    .expect("valid PDA vector JSON")
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn bytes32(hex: &str) -> [u8; 32] {
    assert_eq!(hex.len(), 64);
    let mut out = [0u8; 32];
    for (index, byte) in out.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&hex[index * 2..index * 2 + 2], 16)
            .expect("valid hex byte");
    }
    out
}

#[test]
fn pda_derivations_match_repository_vector() {
    let vector = vector();

    assert_eq!(vector["schema_version"].as_u64(), Some(1));
    let program_id = ID.to_string();
    assert_eq!(vector["program_id"].as_str(), Some(program_id.as_str()));

    let (config, config_bump) = config_pda(&ID);
    assert_eq!(config.to_string(), vector["config"]["pda"].as_str().unwrap());
    assert_eq!(
        encode_hex(&config.to_bytes()),
        vector["config"]["pda_hex"].as_str().unwrap()
    );
    assert_eq!(
        u64::from(config_bump),
        vector["config"]["bump"].as_u64().unwrap()
    );

    let refresh_id = bytes32(vector["refresh"]["refresh_id_hex"].as_str().unwrap());
    let (refresh, refresh_bump) = refresh_pda(&ID, &refresh_id);
    assert_eq!(refresh.to_string(), vector["refresh"]["pda"].as_str().unwrap());
    assert_eq!(
        encode_hex(&refresh.to_bytes()),
        vector["refresh"]["pda_hex"].as_str().unwrap()
    );
    assert_eq!(
        u64::from(refresh_bump),
        vector["refresh"]["bump"].as_u64().unwrap()
    );

    let funder = Pubkey::from_str(
        vector["contribution"]["funder_pubkey"].as_str().unwrap(),
    )
    .expect("valid funder pubkey");
    let (contribution, contribution_bump) = contribution_pda(&ID, &refresh, &funder);
    assert_eq!(
        contribution.to_string(),
        vector["contribution"]["pda"].as_str().unwrap()
    );
    assert_eq!(
        encode_hex(&contribution.to_bytes()),
        vector["contribution"]["pda_hex"].as_str().unwrap()
    );
    assert_eq!(
        u64::from(contribution_bump),
        vector["contribution"]["bump"].as_u64().unwrap()
    );
}
