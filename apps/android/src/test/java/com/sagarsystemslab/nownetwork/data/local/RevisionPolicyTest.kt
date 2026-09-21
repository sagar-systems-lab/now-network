package com.sagarsystemslab.nownetwork.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RevisionPolicyTest {
    @Test
    fun revisionDecisionsAreDeterministic() {
        assertEquals(RevisionDecision.INSERT, decideRevision(null, 0))
        assertEquals(RevisionDecision.IGNORE_STALE, decideRevision(4, 3))
        assertEquals(RevisionDecision.IGNORE_DUPLICATE, decideRevision(4, 4))
        assertEquals(RevisionDecision.APPLY_NEXT, decideRevision(4, 5))
        assertEquals(RevisionDecision.REQUIRE_SNAPSHOT, decideRevision(4, 6))
    }

    @Test
    fun negativeRevisionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            decideRevision(0, -1)
        }
    }
}
