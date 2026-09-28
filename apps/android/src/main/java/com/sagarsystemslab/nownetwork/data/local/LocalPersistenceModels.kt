package com.sagarsystemslab.nownetwork.data.local

enum class LocalOutboxStatus {
    PENDING,
    READY,
    SENDING,
    ACKNOWLEDGED,
    RETRY_WAIT,
    BLOCKED,
    EXPIRED,
    FAILED,
}

enum class PendingEvidenceStatus {
    CHALLENGE_ISSUED,
    CAPTURING,
    CAPTURED_LOCAL,
    HASHING,
    UPLOAD_READY,
    UPLOADING,
    UPLOADED,
    COMMITTING,
    COMMITTED,
    VERIFYING,
    VERIFIED,
    REJECTED,
    CONFLICT,
    EXPIRED,
    FAILED,
}

enum class RevisionDecision {
    INSERT,
    APPLY_NEXT,
    IGNORE_STALE,
    IGNORE_DUPLICATE,
    REQUIRE_SNAPSHOT,
}

enum class CacheApplyResult {
    INSERTED,
    UPDATED,
    IGNORED_STALE,
    IGNORED_DUPLICATE,
    SNAPSHOT_REQUIRED,
    REVISION_CONFLICT,
}

fun decideRevision(currentRevision: Long?, incomingRevision: Long): RevisionDecision {
    require(incomingRevision >= 0) { "incoming revision must be non-negative" }

    if (currentRevision == null) return RevisionDecision.INSERT
    require(currentRevision >= 0) { "current revision must be non-negative" }

    return when {
        incomingRevision < currentRevision -> RevisionDecision.IGNORE_STALE
        incomingRevision == currentRevision -> RevisionDecision.IGNORE_DUPLICATE
        incomingRevision == currentRevision + 1 -> RevisionDecision.APPLY_NEXT
        else -> RevisionDecision.REQUIRE_SNAPSHOT
    }
}

class IdempotencyCollisionException(
    val idempotencyKey: String,
) : IllegalStateException("idempotency key reused for a different operation")
