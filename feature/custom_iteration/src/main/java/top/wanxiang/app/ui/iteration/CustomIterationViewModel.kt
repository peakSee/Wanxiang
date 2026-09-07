package top.wanxiang.app.ui.iteration

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.wanxiang.app.feature.custom_iteration.R
import top.wanxiang.app.iteration.engine.CustomIterationBootstrap

@HiltViewModel
class CustomIterationViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    data class UiState(
        val isBusy: Boolean = false,
        val workspaceReady: Boolean = false,
        val workspacePath: String = "",
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun startCustomIteration(onSessionReady: (prompt: String) -> Unit) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, errorMessage = null)

        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val rootfsHome = File(app.filesDir, "runtime/rootfs/root")
                val result = CustomIterationBootstrap.bootstrap(app, rootfsHome)

                if (result.success) {
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        workspaceReady = true,
                        workspacePath = result.workspacePath
                    )
                    onSessionReady(result.prompt)
                } else {
                    // 引导失败的原始技术细节只进日志，UI 展示用户可理解的友好文案
                    Log.w(TAG, "bootstrap failed: ${result.errorMessage}")
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        errorMessage = app.getString(R.string.iteration_error_bootstrap)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "bootstrap custom iteration crashed", e)
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    errorMessage = app.getString(R.string.iteration_error_bootstrap)
                )
            }
        }
    }

    /** 用户关闭错误提示。 */
    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private companion object {
        const val TAG = "CustomIteration"
    }
}
