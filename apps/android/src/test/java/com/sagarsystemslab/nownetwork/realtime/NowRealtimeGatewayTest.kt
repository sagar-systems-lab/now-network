package com.sagarsystemslab.nownetwork.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowRealtimeGatewayTest {
    @Test
    fun parsesSanitizedRealtimeEventContract() {
        val record = Json.parseToJsonElement(
            """
            {
              "realtime_event_id":"11111111-1111-4111-8111-111111111111",
              "event_type":"STATE_UPDATED",
              "entity_type":"state",
              "entity_id":"22222222-2222-4222-8222-222222222222",
              "entity_revision":8,
              "payload":{"source_event_id":"33333333-3333-4333-8333-333333333333"}
            }
            """.trimIndent(),
        ).jsonObject

        val event = requireNotNull(parseRealtimeEvent(record))

        assertEquals("STATE_UPDATED", event.eventType)
        assertEquals("state", event.entityType)
        assertEquals("22222222-2222-4222-8222-222222222222", event.entityId)
        assertEquals(8L, event.entityRevision)
    }

    @Test
    fun malformedRealtimeEventFailsClosed() {
        val record = Json.parseToJsonElement(
            """{"event_type":"STATE_UPDATED","entity_type":"state"}""",
        ).jsonObject

        assertNull(parseRealtimeEvent(record))
    }
}
