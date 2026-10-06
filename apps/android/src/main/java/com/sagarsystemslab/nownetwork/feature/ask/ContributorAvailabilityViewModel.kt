package com.sagarsystemslab.nownetwork.feature.ask

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationFailure
import com.sagarsystemslab.nownetwork.evidence.EvidenceLocationProvider
import com.sagarsystemslab.nownetwork.experience.number
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class AvailabilityStatus { OFF, LOCATING, AVAILABLE, LOW_ACCURACY, PERMISSION_REQUIRED, OFFLINE }
data class AvailabilityUiState(val enabled: Boolean=false, val status: AvailabilityStatus=AvailabilityStatus.OFF)

@HiltViewModel
class ContributorAvailabilityViewModel @Inject constructor(
    private val repository: AskRepository,
    private val locations: EvidenceLocationProvider,
) : ViewModel() {
    private val mutable=MutableStateFlow(AvailabilityUiState())
    val state=mutable.asStateFlow()
    private val writes=Mutex()
    private var heartbeat: Job?=null
    private var foreground=false
    private var actorId: String?=null
    fun actorChanged(value: String?) {
        if(actorId!=null && actorId!=value) disable()
        actorId=value
    }

    fun foreground(value: Boolean) {
        foreground=value
        if(value && mutable.value.enabled) start()
        else if(!value) pause()
    }
    fun enable() {
        if(!foreground) return
        mutable.value=AvailabilityUiState(true,AvailabilityStatus.LOCATING)
        start()
    }
    fun permissionDenied() {
        disable()
        mutable.value=AvailabilityUiState(false,AvailabilityStatus.PERMISSION_REQUIRED)
    }
    private fun pause() {
        heartbeat?.cancel(); heartbeat=null
    }
    fun disable() {
        pause()
        val wasEnabled=mutable.value.enabled
        mutable.value=AvailabilityUiState()
        if(wasEnabled) viewModelScope.launch {
            writes.withLock { try { withTimeout(10_000) { repository.unavailable() } } catch(_: Exception) { /* Server TTL still expires presence. */ } }
        }
    }
    private fun start() {
        if(heartbeat?.isActive==true) return
        heartbeat=viewModelScope.launch {
            var interval=60L
            while(isActive && foreground && mutable.value.enabled) {
                try {
                    val sample=withTimeout(20_000) { locations.currentSample() }
                    val accuracy=sample.accuracyMeters
                    val age=SystemClock.elapsedRealtime()-sample.observedElapsedRealtimeMs
                    if(accuracy==null || accuracy !in 0.0..100.0 || age !in 0L..60_000L || sample.mockSignal==true) {
                        mutable.value=AvailabilityUiState(true,AvailabilityStatus.LOW_ACCURACY)
                        writes.withLock { repository.unavailable() }
                    } else {
                        writes.withLock {
                            ensureActive()
                            val response=repository.available(buildJsonObject {
                                put("available",true); put("lat",sample.latitude); put("lng",sample.longitude)
                                put("accuracy_m",accuracy)
                            })
                            interval=response.number("heartbeat_seconds").coerceIn(30,120).toLong()
                        }
                        mutable.value=AvailabilityUiState(true,AvailabilityStatus.AVAILABLE)
                    }
                } catch(_: EvidenceLocationFailure.Permission) {
                    mutable.value=AvailabilityUiState(false,AvailabilityStatus.PERMISSION_REQUIRED)
                    writes.withLock { runCatching { repository.unavailable() } }
                    return@launch
                } catch(_: TimeoutCancellationException) {
                    mutable.value=AvailabilityUiState(true,AvailabilityStatus.OFFLINE)
                } catch(error: CancellationException) { throw error }
                catch(_: Exception) { mutable.value=AvailabilityUiState(true,AvailabilityStatus.OFFLINE) }
                delay(interval*1000)
            }
        }
    }
}
