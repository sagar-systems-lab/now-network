use anchor_lang::prelude::*;

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum StateKind {
    Binary,
    Numeric,
    Visual,
}

impl StateKind {
    pub const fn code(self) -> u8 {
        match self {
            Self::Binary => 0,
            Self::Numeric => 1,
            Self::Visual => 2,
        }
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum VerificationClass {
    Fast,
    Corroborated,
    Strict,
}

impl VerificationClass {
    pub const fn code(self) -> u8 {
        match self {
            Self::Fast => 0,
            Self::Corroborated => 1,
            Self::Strict => 2,
        }
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum PayoutRule {
    SingleWinnerAll,
    EqualSplitRequiredWitnesses,
}

impl PayoutRule {
    pub const fn code(self) -> u8 {
        match self {
            Self::SingleWinnerAll => 0,
            Self::EqualSplitRequiredWitnesses => 1,
        }
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum RefreshStatus {
    Open,
    Locked,
    Settled,
    Cancelled,
    Refunding,
    Closed,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClaimStatus {
    Empty,
    Claimed,
    Released,
}
