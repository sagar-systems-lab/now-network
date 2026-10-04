package com.sagarsystemslab.nownetwork.feature.ask

import com.sagarsystemslab.nownetwork.model.GeoCenter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AskDraftTest {
    @Test fun remoteTargetDoesNotRequireRequesterGps() {
        val draft=AskDraft("Remote lot",GeoCenter(28.6,77.2),AskNeed.PARKING)
        assertTrue(draft.targetReady)
        val payload=draft.payload()
        assertFalse(payload.containsKey("requester_location"))
        assertEquals(28.6,payload.getValue("location").jsonObject.getValue("lat").jsonPrimitive.double,0.0)
    }
    @Test fun retryKeepsPayloadAndIdempotencyIdentity() {
        val draft=AskDraft("Gate",GeoCenter(28.6,77.2),AskNeed.GATE)
        val restored=draft.copy()
        assertEquals(draft.key,restored.key)
        assertEquals(draft.payload(),restored.payload())
        assertEquals("gate.open_closed.v1",draft.payload().getValue("policy_template_key").jsonPrimitive.content)
    }
    @Test fun invalidTargetsCannotReachReward() {
        assertFalse(AskDraft("",GeoCenter(28.6,77.2)).targetReady)
        assertFalse(AskDraft("Place",GeoCenter(Double.NaN,77.2)).targetReady)
        assertFalse(AskDraft("Place").targetReady)
    }
}
