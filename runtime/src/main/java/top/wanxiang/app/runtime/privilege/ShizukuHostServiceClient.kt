package top.wanxiang.app.runtime.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** 应用进程侧的 Shizuku UserService 连接与 AIDL 调用器。 */
@Singleton
class ShizukuHostServiceClient @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuHostUserService::class.java.name),
    )
        .processNameSuffix("wanxiang_host")
        .tag("wanxiang-host-shell-v1")
        .version(1)
        .daemon(false)
        .debuggable(false)

    private val connectionMutex = Mutex()
    @Volatile private var service: IShizukuHostService? = null
    @Volatile private var pendingConnection: CompletableDeferred<IShizukuHostService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IShizukuHostService.Stub.asInterface(binder)
            service = connected
            pendingConnection?.complete(connected)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            pendingConnection?.completeExceptionally(IllegalStateException("Shizuku UserService 连接已断开"))
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            pendingConnection?.completeExceptionally(IllegalStateException("Shizuku UserService Binder 已失效"))
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            pendingConnection?.completeExceptionally(IllegalStateException("Shizuku UserService 未返回 Binder"))
        }
    }

    suspend fun execute(operationId: String, command: String): ShellExecResult = withContext(Dispatchers.IO) {
        val encoded = requireService().execute(operationId, command)
        val result = JSONObject(encoded)
        ShellExecResult(
            success = result.optBoolean("success", false),
            exitCode = result.optInt("exitCode", -1),
            stdout = result.optString("stdout", ""),
            stderr = result.optString("stderr", ""),
        )
    }

    fun cancel(operationId: String): Boolean = runCatching {
        service?.takeIf { it.asBinder().isBinderAlive }?.cancel(operationId) == true
    }.getOrDefault(false)

    private suspend fun requireService(): IShizukuHostService = connectionMutex.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        check(Shizuku.pingBinder()) { "Shizuku 服务未运行" }
        check(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Shizuku 未授权" }

        // UserService 由 Shizuku fork 独立进程，首次冷启动需加载 APK classloader + AIDL Stub，
        // 中低端设备可能超过 8 秒；放宽超时并允许一次重试，覆盖进程冷启动与 Binder 交付抖动。
        var lastError: Throwable? = null
        repeat(BIND_MAX_RETRIES) { attempt ->
            try {
                return@withLock bindOnce()
            } catch (throwable: Throwable) {
                lastError = throwable
                service = null
                runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
                if (attempt < BIND_MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(BIND_RETRY_DELAY_MS)
                }
            }
        }
        throw requireNotNull(lastError)
    }

    private suspend fun bindOnce(): IShizukuHostService {
        val deferred = CompletableDeferred<IShizukuHostService>()
        pendingConnection = deferred
        try {
            Shizuku.bindUserService(serviceArgs, connection)
            return withTimeout(CONNECTION_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingConnection = null
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val BIND_MAX_RETRIES = 2
        private const val BIND_RETRY_DELAY_MS = 1_000L
    }
}
