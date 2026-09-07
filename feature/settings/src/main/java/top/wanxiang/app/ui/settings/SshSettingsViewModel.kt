package top.wanxiang.app.ui.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wanxiang.app.core.datastore.SshPreferences
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.SshRuntimeConfig
import top.wanxiang.app.runtime.SshServiceManager

data class SshSettingsUiState(
    val distroId: String = "ubuntu",
    val enabled: Boolean = false,
    val port: Int = SshRuntimeConfig.DEFAULT_SSH_PORT,
    val authorizedKeys: String = "",
    val passwordAuthEnabled: Boolean = false,
    val passwordConfigured: Boolean = false,
) {
    val connectionHost: String
        get() = SshServiceManager.localIpv4Address() ?: "<设备局域网IP>"
    val connectionCommand: String
        get() = "ssh -p $port root@$connectionHost"
}

@HiltViewModel
class SshSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val preferences: SshPreferences,
    private val manager: SshServiceManager,
) : ViewModel() {
    val serviceState = manager.state
    val logs = manager.logs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val settings: StateFlow<SshSettingsUiState> = linuxRuntime.activeDistroId
        .flatMapLatest { distroId ->
            combine(
                preferences.enabled(distroId),
                preferences.port(distroId),
                preferences.authorizedKeys(distroId),
                preferences.passwordAuthEnabled(distroId),
            ) { enabled, port, authorizedKeys, passwordAuthEnabled ->
                SshSettingsUiState(
                    distroId = distroId,
                    enabled = enabled,
                    port = port,
                    authorizedKeys = authorizedKeys,
                    passwordAuthEnabled = passwordAuthEnabled,
                )
            }.combine(preferences.passwordConfigured(distroId)) { state, passwordConfigured ->
                state.copy(passwordConfigured = passwordConfigured)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SshSettingsUiState())

    private val _operating = MutableStateFlow(false)
    val operating = _operating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** 当前 message 是否为失败结果（类型化标记，避免 UI 用字符串匹配判断样式）。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError = _messageIsError.asStateFlow()

    init {
        manager.startObserving()
        refresh()
        observeVpn()
    }

    fun toggleEnabled(enabled: Boolean) = runOperation {
        manager.setEnabled(enabled)
    }

    fun savePort(value: String) = runOperation(successMessage = "SSH 端口已保存") {
        val port = value.trim().toIntOrNull() ?: error("请输入有效的 SSH 端口")
        manager.setPort(port)
    }

    fun saveAuthorizedKeys(value: String) = runOperation(successMessage = "SSH 授权公钥已保存") {
        manager.setAuthorizedKeys(value)
    }

    fun savePassword(value: String) = runOperation(successMessage = "SSH 登录密码已保存") {
        manager.setPassword(value)
    }

    fun setPasswordAuthEnabled(enabled: Boolean) = runOperation {
        manager.setPasswordAuthEnabled(enabled)
    }

    fun clearPassword() = runOperation(successMessage = "SSH 登录密码已清除") {
        manager.clearPassword()
    }

    fun refresh() = runOperation(showProgress = false) {
        manager.refresh()
    }

    fun consumeMessage() {
        _message.value = null
        _messageIsError.value = false
    }

    private fun observeVpn() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _vpnActive.value = vpnActiveNow(cm)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                _vpnActive.value = vpnActiveNow(cm)
            }

            override fun onLost(network: Network) {
                _vpnActive.value = vpnActiveNow(cm)
            }
        }
        vpnCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { _vpnActive.value = vpnActiveNow(cm) }
        _vpnActive.value = vpnActiveNow(cm)
    }

    private fun vpnActiveNow(cm: ConnectivityManager): Boolean = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    override fun onCleared() {
        vpnCallback?.let { callback ->
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(callback)
            }
        }
        super.onCleared()
    }

    private fun runOperation(
        showProgress: Boolean = true,
        successMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            if (showProgress) _operating.value = true
            try {
                block()
                if (successMessage != null) {
                    _message.value = successMessage
                    _messageIsError.value = false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _message.value = throwable.message ?: "SSH 操作失败"
                _messageIsError.value = true
            } finally {
                if (showProgress) _operating.value = false
            }
        }
    }
}
