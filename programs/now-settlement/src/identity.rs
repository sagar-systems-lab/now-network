use anchor_lang::prelude::*;
use solana_sha256_hasher::hashv;

use crate::constants::{CONFIG_SEED, CONTRIBUTION_SEED, REFRESH_SEED};
use crate::state::{PayoutRule, StateKind, VerificationClass};

pub type Digest32 = [u8; 32];
pub type OperationId = [u8; 32];
pub type RefreshId = [u8; 32];

pub const INTENT_CORE_V1_CANONICAL_LEN: usize = 183;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct IntentCoreV1 {
    pub intent_schema_version: u16,
    pub state_id_digest: Digest32,
    pub state_definition_version: u32,
    pub location_scope_digest: Digest32,
    pub state_kind: StateKind,
    pub answer_schema_digest: Digest32,
    pub freshness_ttl_seconds: u32,
    pub proof_policy_digest: Digest32,
    pub verification_class: VerificationClass,
    pub required_witnesses: u8,
    pub max_witnesses: u8,
    pub payout_rule: PayoutRule,
    pub refresh_expires_at: i64,
    pub reward_mint: Pubkey,
}

impl IntentCoreV1 {
    pub fn canonical_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(INTENT_CORE_V1_CANONICAL_LEN);

        bytes.extend_from_slice(&self.intent_schema_version.to_le_bytes());
        bytes.extend_from_slice(&self.state_id_digest);
        bytes.extend_from_slice(&self.state_definition_version.to_le_bytes());
        bytes.extend_from_slice(&self.location_scope_digest);
        bytes.push(self.state_kind.code());
        bytes.extend_from_slice(&self.answer_schema_digest);
        bytes.extend_from_slice(&self.freshness_ttl_seconds.to_le_bytes());
        bytes.extend_from_slice(&self.proof_policy_digest);
        bytes.push(self.verification_class.code());
        bytes.push(self.required_witnesses);
        bytes.push(self.max_witnesses);
        bytes.push(self.payout_rule.code());
        bytes.extend_from_slice(&self.refresh_expires_at.to_le_bytes());
        bytes.extend_from_slice(self.reward_mint.as_ref());

        debug_assert_eq!(bytes.len(), INTENT_CORE_V1_CANONICAL_LEN);
        bytes
    }

    pub fn digest(&self) -> Digest32 {
        sha256(&[&self.canonical_bytes()])
    }
}

pub fn execution_hash(
    intent_core_hash: &Digest32,
    locked_reward_amount: u64,
    refresh_pda: &Pubkey,
) -> Digest32 {
    let amount = locked_reward_amount.to_le_bytes();
    sha256(&[intent_core_hash, &amount, refresh_pda.as_ref()])
}

pub fn settlement_operation_hash(
    refresh_pda: &Pubkey,
    execution_hash: &Digest32,
    operation_id: &OperationId,
) -> Digest32 {
    sha256(&[refresh_pda.as_ref(), execution_hash, operation_id])
}

pub fn config_pda(program_id: &Pubkey) -> (Pubkey, u8) {
    Pubkey::find_program_address(&[CONFIG_SEED], program_id)
}

pub fn refresh_pda(program_id: &Pubkey, refresh_id: &RefreshId) -> (Pubkey, u8) {
    Pubkey::find_program_address(&[REFRESH_SEED, refresh_id], program_id)
}

pub fn contribution_pda(program_id: &Pubkey, refresh: &Pubkey, funder: &Pubkey) -> (Pubkey, u8) {
    Pubkey::find_program_address(
        &[CONTRIBUTION_SEED, refresh.as_ref(), funder.as_ref()],
        program_id,
    )
}

fn sha256(parts: &[&[u8]]) -> Digest32 {
    hashv(parts).to_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constants::INTENT_SCHEMA_VERSION_V1;

    fn sample_intent() -> IntentCoreV1 {
        IntentCoreV1 {
            intent_schema_version: INTENT_SCHEMA_VERSION_V1,
            state_id_digest: [0x11; 32],
            state_definition_version: 7,
            location_scope_digest: [0x22; 32],
            state_kind: StateKind::Numeric,
            answer_schema_digest: [0x33; 32],
            freshness_ttl_seconds: 600,
            proof_policy_digest: [0x44; 32],
            verification_class: VerificationClass::Fast,
            required_witnesses: 1,
            max_witnesses: 1,
            payout_rule: PayoutRule::SingleWinnerAll,
            refresh_expires_at: 1_900_000_000,
            reward_mint: Pubkey::new_from_array([0x55; 32]),
        }
    }

    #[test]
    fn canonical_intent_encoding_is_fixed_width_and_deterministic() {
        let intent = sample_intent();
        let first = intent.canonical_bytes();
        let second = intent.canonical_bytes();

        assert_eq!(first.len(), INTENT_CORE_V1_CANONICAL_LEN);
        assert_eq!(first, second);
        assert_eq!(&first[0..2], &INTENT_SCHEMA_VERSION_V1.to_le_bytes());
        assert_eq!(&first[first.len() - 32..], intent.reward_mint.as_ref());
    }

    #[test]
    fn intent_hash_changes_when_semantic_terms_change() {
        let original = sample_intent();
        let mut changed = original;
        changed.refresh_expires_at += 1;

        assert_ne!(original.digest(), changed.digest());
    }

    #[test]
    fn execution_hash_binds_locked_amount_and_refresh() {
        let intent_hash = sample_intent().digest();
        let refresh = Pubkey::new_from_array([0x66; 32]);
        let other_refresh = Pubkey::new_from_array([0x67; 32]);

        let baseline = execution_hash(&intent_hash, 1_000_000, &refresh);

        assert_ne!(baseline, execution_hash(&intent_hash, 1_000_001, &refresh));
        assert_ne!(
            baseline,
            execution_hash(&intent_hash, 1_000_000, &other_refresh)
        );
    }

    #[test]
    fn settlement_operation_hash_binds_logical_operation() {
        let intent_hash = sample_intent().digest();
        let refresh = Pubkey::new_from_array([0x66; 32]);
        let execution = execution_hash(&intent_hash, 1_000_000, &refresh);
        let operation = [0x77; 32];
        let other_operation = [0x78; 32];

        let baseline = settlement_operation_hash(&refresh, &execution, &operation);

        assert_eq!(
            baseline,
            settlement_operation_hash(&refresh, &execution, &operation),
        );
        assert_ne!(
            baseline,
            settlement_operation_hash(&refresh, &execution, &other_operation),
        );
    }

    #[test]
    fn protocol_pdas_are_deterministic_and_domain_separated() {
        let refresh_id = [0x88; 32];
        let funder = Pubkey::new_from_array([0x99; 32]);

        let (config, config_bump) = config_pda(&crate::ID);
        let (refresh, refresh_bump) = refresh_pda(&crate::ID, &refresh_id);
        let (contribution, contribution_bump) = contribution_pda(&crate::ID, &refresh, &funder);

        assert_eq!((config, config_bump), config_pda(&crate::ID));
        assert_eq!(
            (refresh, refresh_bump),
            refresh_pda(&crate::ID, &refresh_id)
        );
        assert_eq!(
            (contribution, contribution_bump),
            contribution_pda(&crate::ID, &refresh, &funder),
        );

        assert_ne!(config, refresh);
        assert_ne!(refresh, contribution);
        assert_ne!(config, contribution);
    }
}
