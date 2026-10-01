package com.sagarsystemslab.nownetwork.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sagarsystemslab.nownetwork.model.ActivityItem
import com.sagarsystemslab.nownetwork.repository.ActivityRepository
import com.sagarsystemslab.nownetwork.repository.ServerClock
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ActivityUiState(
    val active: List<ActivityItem> = emptyList(),
    val completed: List<ActivityItem> = emptyList(),
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    repository: Lazy<ActivityRepository>,
    private val serverClock: ServerClock,
) : ViewModel() {
    val state: StateFlow<ActivityUiState> =
        flow {
            emitAll(repository.get().observeActivity())
        }
            .map { items ->
                ActivityUiState(
                    active = items.filter(ActivityItem::active),
                    completed = items.filterNot(ActivityItem::active),
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ActivityUiState(),
            )

    fun serverNowMillis(): Long = serverClock.nowMillis()
}
