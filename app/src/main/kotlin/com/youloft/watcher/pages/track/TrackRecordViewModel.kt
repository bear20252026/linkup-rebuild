package com.youloft.watcher.pages.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youloft.linkup.api.dto.FriendLocation
import com.youloft.linkup.api.dto.PermissionUpdateBean
import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.watcher.data.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TrackUiState(
    val loading: Boolean = false,
    val rows: List<TrackRow> = emptyList(),
    val emptyVisible: Boolean = false,
    val message: String? = null,
    val permission: PermissionUpdateBean? = null,
    val friendLocation: FriendLocation? = null,
    val selectedTimePeriod: Long? = null,
    val dates: List<String> = emptyList(),
    val selectedDate: String? = null,
    val recordedDates: Set<String> = emptySet(),
    val daysMessage: String? = null,
)

class TrackRecordViewModel(
    private val repository: TrackRepository,
    private val rowBuilder: TrackRowBuilder,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TrackUiState())
    val state: StateFlow<TrackUiState> = mutableState
    private var recordJob: Job? = null
    private var daysJob: Job? = null

    fun loadDays(userId: Long, today: LocalDate = LocalDate.now()) {
        daysJob?.cancel()
        mutableState.update {
            it.copy(dates = (0L..14L).map { offset -> today.minusDays(offset).toString() }, daysMessage = null)
        }
        daysJob = viewModelScope.launch {
            try {
                val response = repository.days(userId)
                ensureActive()
                mutableState.update {
                    it.copy(
                        recordedDates = if (response.isSuccess) response.data.orEmpty().toSet() else emptySet(),
                        daysMessage = if (response.isSuccess) null else response.msg ?: "轨迹日期加载失败",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update { it.copy(recordedDates = emptySet(), daysMessage = "轨迹日期加载失败") }
            }
        }
    }

    fun load(userId: Long, date: String) {
        recordJob?.cancel()
        mutableState.update {
            it.copy(
                loading = true, rows = emptyList(), emptyVisible = false, message = null,
                permission = null, friendLocation = null, selectedTimePeriod = null, selectedDate = date,
            )
        }
        recordJob = viewModelScope.launch {
            try {
                val response = repository.record(userId, date)
                ensureActive()
                val record = response.data
                if (!response.isSuccess || record == null) {
                    mutableState.update {
                        it.copy(loading = false, emptyVisible = true, message = response.msg ?: "轨迹加载失败")
                    }
                } else {
                    mutableState.update {
                        it.copy(
                            loading = false,
                            rows = rowBuilder.build(record),
                            emptyVisible = record.tracks.orEmpty().none { track -> track != null },
                            permission = record.permission,
                            friendLocation = record.friendLocation,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                mutableState.update { it.copy(loading = false, emptyVisible = true, message = "轨迹加载失败，请重试") }
            }
        }
    }

    fun select(track: TrackRecordBean.Track) {
        mutableState.update {
            val selectable = it.rows.filterIsInstance<TrackRow.Point>()
                .any { row -> row.view.track == track && row.view.selectable }
            it.copy(selectedTimePeriod = if (selectable && it.selectedTimePeriod != track.timePeriod) track.timePeriod else null)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selectedTimePeriod = null) }
    }

    companion object {
        fun factory(repository: TrackRepository, rowBuilder: TrackRowBuilder) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TrackRecordViewModel(repository, rowBuilder) as T
            }
    }
}
