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
    @Test fun aNewAddressCannotKeepThePreviousPin() {
        val selected = AskDraft("Local lot", GeoCenter(30.3, 76.8), AskNeed.PARKING)
        val searching = selected.searching()
        assertFalse(searching.targetReady)
        assertNull(searching.target)
        assertEquals("", searching.name)
        assertEquals(AskNeed.PARKING, searching.need)
    }
    @Test fun otherQuestionRequiresDetailsAndUsesPhotoProof() {
        val draft = AskDraft("Nehru Place", GeoCenter(28.55,77.25), AskNeed.OTHER)
        assertFalse(draft.needReady)
        val ready = draft.copy(customQuestion = "How busy is the main entrance?")
        assertTrue(ready.needReady)
        assertEquals(ready.question, ready.payload().getValue("custom_question").jsonPrimitive.content)
        assertEquals("visual.current_condition.v1", ready.payload().getValue("policy_template_key").jsonPrimitive.content)
        assertFalse(draft.copy(customQuestion = "x".repeat(201)).needReady)
    }
}
