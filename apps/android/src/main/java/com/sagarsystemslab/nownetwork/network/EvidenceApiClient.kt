package com.sagarsystemslab.nownetwork.network

interface EvidenceApiClient {
    suspend fun issueEvidenceChallenge(
        acceptanceId: String,
        accessToken: String,
    ): EvidenceChallengeDto

    suspend fun authorizeEvidenceUpload(
        challengeId: String,
        request: EvidenceUploadAuthorizeRequest,
        accessToken: String,
    ): EvidenceUploadAuthorizationDto

    suspend fun commitEvidence(
        evidenceId: String,
        request: EvidenceCommitRequest,
        idempotencyKey: String,
        accessToken: String,
    ): EvidenceCommitDto
}
