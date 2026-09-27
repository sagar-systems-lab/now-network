package com.sagarsystemslab.nownetwork.realtime

import com.sagarsystemslab.nownetwork.auth.SupabaseRuntimeClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface NowRealtimeSignal {
    data object Connected : NowRealtimeSignal
    data object Disconnected : NowRealtimeSignal
    data object Unavailable : NowRealtimeSignal

    data class Event(
        val eventId: String,
        val eventType: String,
        val entityType: String,
        val entityId: String,
        val entityRevision: Long?,
    ) : NowRealtimeSignal
}

interface NowRealtimeGateway {
    fun signals(): Flow<NowRealtimeSignal>
}

@Singleton
class SupabaseNowRealtimeGateway @Inject constructor(
    private val runtimeClient: SupabaseRuntimeClient,
) : NowRealtimeGateway {
    override fun signals(): Flow<NowRealtimeSignal> = channelFlow {
        val client = runtimeClient.client
        if (client == null) {
            send(NowRealtimeSignal.Unavailable)
            return@channelFlow
        }

        val channel = client.channel(
            "now-realtime-events-${UUID.randomUUID()}",
        )
        val changes = channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public",
        ) {
            table = "realtime_events_v1"
        }

        val connectionJob = launch {
            client.realtime.status.collectLatest { status ->
                send(
                    if (status == Realtime.Status.CONNECTED) {
                        NowRealtimeSignal.Connected
                    } else {
                        NowRealtimeSignal.Disconnected
                    },
                )
            }
        }

        val eventJob = launch {
            changes.collect { action ->
                parseRealtimeEvent(action.record)?.let { send(it) }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } finally {
            connectionJob.cancel()
            eventJob.cancel()
            withContext(NonCancellable) {
                runCatching { channel.unsubscribe() }
                runCatching { client.realtime.removeChannel(channel) }
            }
        }
    }
}

internal fun parseRealtimeEvent(
    record: kotlinx.serialization.json.JsonObject,
): NowRealtimeSignal.Event? {
    val eventId = record["realtime_event_id"]?.jsonPrimitive?.contentOrNull ?: return null
    val eventType = record["event_type"]?.jsonPrimitive?.contentOrNull ?: return null
    val entityType = record["entity_type"]?.jsonPrimitive?.contentOrNull ?: return null
    val entityId = record["entity_id"]?.jsonPrimitive?.contentOrNull ?: return null
    val revision = record["entity_revision"]?.jsonPrimitive?.longOrNull

    return NowRealtimeSignal.Event(
        eventId = eventId,
        eventType = eventType,
        entityType = entityType,
        entityId = entityId,
        entityRevision = revision,
    )
}
