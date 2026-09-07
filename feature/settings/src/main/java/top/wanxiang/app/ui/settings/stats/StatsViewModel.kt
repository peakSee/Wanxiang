package top.wanxiang.app.ui.settings.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.wanxiang.app.core.model.StatsDateRange
import top.wanxiang.app.core.model.StatsDateRangePreset
import top.wanxiang.app.core.model.StatsSnapshot
import java.time.LocalDate
import javax.inject.Inject

data class StatsUiState(
    val range: StatsDateRange = StatsDateRange.allTime(),
    val snapshot: StatsSnapshot? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadSnapshot(_uiState.value.range)
    }

    fun setDateRangePreset(preset: StatsDateRangePreset) {
        val now = LocalDate.now()
        val newRange = when (preset) {
            StatsDateRangePreset.ALL_TIME -> StatsDateRange.allTime()
            StatsDateRangePreset.LAST_30_DAYS -> StatsDateRange.last30Days(now)
            StatsDateRangePreset.PREVIOUS_MONTH -> StatsDateRange.previousMonth(now)
            StatsDateRangePreset.PREVIOUS_QUARTER -> StatsDateRange.previousQuarter(now)
            StatsDateRangePreset.CUSTOM -> _uiState.value.range
        }
        loadSnapshot(newRange)
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        val newRange = StatsDateRange.custom(start, end)
        loadSnapshot(newRange)
    }

    fun refresh() {
        loadSnapshot(_uiState.value.range)
    }

    private fun loadSnapshot(range: StatsDateRange) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(range = range, isLoading = true, error = null)
            try {
                val snapshot = statsRepository.buildSnapshot(range)
                _uiState.value = _uiState.value.copy(
                    range = range,
                    snapshot = snapshot,
                    isLoading = false,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StatsViewModel", "Failed to build stats snapshot: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "统计数据加载失败，请点击重试",
                )
            }
        }
    }
}
