package top.wanxiang.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wanxiang.app.core.datastore.WorkshopKeystore
import top.wanxiang.app.runtime.build.WorkshopSigningManager

data class WorkshopSigningCreationDraft(
    val name: String = "",
    val alias: String = "",
    val storePassword: String = "",
    val keyPassword: String = "",
    val validityYears: Int = WorkshopKeystore.DEFAULT_VALIDITY_YEARS,
    val organization: String = "",
)

data class WorkshopSigningImportDraft(
    val uri: String = "",
    val displayName: String = "",
    val name: String = "",
    val alias: String = "",
    val storePassword: String = "",
    val keyPassword: String = "",
)

@HiltViewModel
class WorkshopSigningViewModel @Inject constructor(
    private val signingManager: WorkshopSigningManager,
) : ViewModel() {

    val keystores: StateFlow<List<WorkshopKeystore>> = signingManager.keystores
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun createKeystore(
        draft: WorkshopSigningCreationDraft,
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            val result = signingManager.createKeystore(
                name = draft.name,
                alias = draft.alias,
                storePassword = draft.storePassword,
                keyPassword = draft.keyPassword,
                validityYears = draft.validityYears,
                organization = draft.organization,
            )
            val errMsg = result.errorOrNull()?.message
            _message.value = errMsg ?: "签名创建成功：${draft.name.trim()}"
            _busy.value = false
            if (result.isSuccess) {
                onSuccess()
            } else if (errMsg != null) {
                onError(errMsg)
            }
        }
    }

    fun importKeystore(
        draft: WorkshopSigningImportDraft,
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            val result = signingManager.importKeystore(
                uri = draft.uri,
                name = draft.name,
                alias = draft.alias,
                storePassword = draft.storePassword,
                keyPassword = draft.keyPassword,
            )
            val errMsg = result.errorOrNull()?.message
            _message.value = errMsg ?: "签名导入成功：${draft.name.trim()}"
            _busy.value = false
            if (result.isSuccess) {
                onSuccess()
            } else if (errMsg != null) {
                onError(errMsg)
            }
        }
    }

    fun deleteKeystore(keystore: WorkshopKeystore) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            val result = signingManager.deleteKeystore(keystore.id)
            _message.value = result.errorOrNull()?.message ?: "已删除签名：${keystore.name}"
            _busy.value = false
        }
    }
}
