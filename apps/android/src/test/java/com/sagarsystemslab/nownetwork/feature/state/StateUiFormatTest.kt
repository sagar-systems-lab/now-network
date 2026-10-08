package com.sagarsystemslab.nownetwork.feature.state

import com.sagarsystemslab.nownetwork.model.StateSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class StateUiFormatTest {
    private val base =
        StateSummary(
            stateId = "11111111-1111-4111-8111-111111111111",
            title = "Parking Lot B",
            question = "How many spaces are available?",
            stateType = "NUMERIC",
            valueJson = "3",
            unitCode = "spaces",
            freshnessStatus = "LIVE",
            observedAtMillis = 1_000L,
            agingAtMillis = 11_000L,
            freshUntilMillis = 21_000L,
            verificationClass = "FAST",
            refreshStatus = null,
            conflictActive = false,
            distanceMeters = 40.0,
            revision = 1L,
        )

    @Test
    fun freshnessUsesServerTimeBoundaries() {
        assertEquals(FreshnessKind.LIVE, base.freshnessAt(10_999L))
        assertEquals(FreshnessKind.AGING, base.freshnessAt(11_000L))
        assertEquals(FreshnessKind.STALE, base.freshnessAt(21_000L))
    }

    @Test
    fun conflictOverridesFreshnessWindow() {
        assertEquals(
            FreshnessKind.CONFLICT,
            base.copy(conflictActive = true).freshnessAt(5_000L),
        )
    }

    @Test
    fun valueFormattingKeepsPrimaryUnitReadable() {
        assertEquals("3 spaces", formatStateValue("3", "spaces"))
        assertEquals(
            "2 spaces",
            formatStateValue(
                """{"scaled_value":"2","scale":0,"unit":"spaces"}""",
                "spaces",
            ),
        )
        assertEquals("Open", formatStateValue("\"Open\"", null))
    }

    @Test
    fun visualAnswersNeverExposeInternalIdentifiers() {
        assertEquals("Visual proof received", formatStateValue("""{"kind":"visual","evidence_id":"73a8e1d5-1a3d-48ad-b960-c2efb2ff0e74"}""", null))
        assertEquals("Verified observation", formatStateValue("""{"internal_id":"private"}""", null))
        assertEquals("Observation unavailable", formatStateValue("{broken", null))
    }

    @Test
    fun relativeTimeUsesObservedTimestamp() {
        assertEquals(
            "verified 45s ago",
            relativeObservedTime(
                observedAtMillis = 1_000L,
                nowMillis = 46_000L,
            ),
        )
    }
}
