use crate::{PayoutRule, VerificationClass};

pub fn witness_policy_is_valid(
    verification_class: VerificationClass,
    required_witnesses: u8,
    max_witnesses: u8,
    payout_rule: PayoutRule,
) -> bool {
    match verification_class {
        VerificationClass::Fast => {
            required_witnesses == 1
                && max_witnesses == 1
                && payout_rule == PayoutRule::SingleWinnerAll
        }
        VerificationClass::Corroborated => {
            required_witnesses == 2
                && (2..=3).contains(&max_witnesses)
                && payout_rule == PayoutRule::EqualSplitRequiredWitnesses
        }
        VerificationClass::Strict => {
            (2..=3).contains(&required_witnesses)
                && max_witnesses == required_witnesses
                && payout_rule == PayoutRule::EqualSplitRequiredWitnesses
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fast_terms_are_exact() {
        assert!(witness_policy_is_valid(
            VerificationClass::Fast,
            1,
            1,
            PayoutRule::SingleWinnerAll,
        ));
        assert!(!witness_policy_is_valid(
            VerificationClass::Fast,
            1,
            2,
            PayoutRule::SingleWinnerAll,
        ));
        assert!(!witness_policy_is_valid(
            VerificationClass::Fast,
            1,
            1,
            PayoutRule::EqualSplitRequiredWitnesses,
        ));
    }

    #[test]
    fn corroborated_and_strict_terms_are_bounded() {
        assert!(witness_policy_is_valid(
            VerificationClass::Corroborated,
            2,
            3,
            PayoutRule::EqualSplitRequiredWitnesses,
        ));
        assert!(witness_policy_is_valid(
            VerificationClass::Strict,
            3,
            3,
            PayoutRule::EqualSplitRequiredWitnesses,
        ));
        assert!(!witness_policy_is_valid(
            VerificationClass::Strict,
            2,
            3,
            PayoutRule::EqualSplitRequiredWitnesses,
        ));
    }
}
