package top.wanxiang.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SponsorListUiState {
    data object Loading : SponsorListUiState
    data class Success(val entries: List<SponsorEntry>) : SponsorListUiState
    data class Error(val message: String) : SponsorListUiState
}

/**
 * 赞助者/贡献者名单 ViewModel：随赞助页面创建而初始化（仅进入页面时拉取一次），
 * 支持手动刷新（顶栏刷新按钮 / 失败重试）。
 */
@HiltViewModel
class SponsorListViewModel @Inject constructor(
    private val repository: SponsorListRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SponsorListUiState>(SponsorListUiState.Loading)
    val state: StateFlow<SponsorListUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = SponsorListUiState.Loading
        viewModelScope.launch {
            _state.value = repository.loadEntries().fold(
                onSuccess = { SponsorListUiState.Success(it) },
                onFailure = { SponsorListUiState.Error(it.message ?: "名单加载失败") },
            )
        }
    }
}
