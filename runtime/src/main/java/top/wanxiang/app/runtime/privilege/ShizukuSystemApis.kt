package top.wanxiang.app.runtime.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Binder 直调的三态结果：通道不可用（回退 shell）与远端失败需区分。 */
sealed interface BinderOutcome {
    /** Binder 通道不可用或接口反射失败；调用方应回退 shell 执行。 */
    data object ChannelUnavailable : BinderOutcome

    /** 调用成功，success 为远端判定结果。 */
    data class Success(val success: Boolean) : BinderOutcome

    /** 通道可用但远端失败（SecurityException / RemoteException / IllegalArgumentException）。 */
    data class Failed(val message: String) : BinderOutcome
}

/**
 * 经 Shizuku Binder 直接调用系统服务的方法合集：
 * 免去拼 shell 命令的转义地狱，异常以类型化方式返回。
 *
 * 实现刻意选择【反射】而非自写 AIDL stub 子集：
 * AIDL 的 transaction code 按“接口文件内声明顺序”生成，自造子集与 framework
 * 全量接口几乎必然错位，会静默调用到错误的系统方法。而 [PackageManager] 等
 * framework 类自带的 `Stub.asInterface` 返回的代理对象，其事务码由 framework
 * 保证与服务端一致——反射获取该方法即可，零依赖、零错位。
 *
 * 仅当特权模式为 SHIZUKU 时调用才有意义；ROOT/PRoot 请走既有 shell 通路。
 */
@Singleton
class ShizukuSystemApis @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        // API 28+ 的 hidden API 强制限制会拦截 IPackageManager$Stub / $Stub$Proxy 的
        // 加载与方法反射。AndroidHiddenApiBypass 通过 ART 元数据技巧做定向豁免；
        // 只放行所需声明前缀，不做全局豁免。Android <28 无此限制，豁免失败可安全忽略。
        runCatching { HiddenApiBypass.setHiddenApiExemptions("Landroid/content/pm/IPackageManager") }
    }

    private val proxies = ConcurrentHashMap<String, Any>()

    private val intType: Class<*> = Integer.TYPE
    private val strType: Class<*> = String::class.java

    /**
     * 授予运行时权限，等价于 shell `pm grant <pkg> <permission>`。
     */
    suspend fun grantRuntimePermission(pkg: String, permission: String, userId: Int): BinderOutcome =
        withContext(Dispatchers.IO) {
            invokeOnPackageService(
                method = "grantRuntimePermission",
                parameterTypes = arrayOf(strType, strType, intType),
                args = arrayOf(pkg, permission, userId),
                interpret = { BinderOutcome.Success(true) },
            )
        }

    /**
     * 设置应用启用状态，等价于 shell `pm enable/disable-user`。
     * [enabled] 对应 COMPONENT_ENABLED_STATE_ENABLED / DISABLED_USER。
     */
    suspend fun setApplicationEnabledSetting(pkg: String, enabled: Boolean, userId: Int): BinderOutcome =
        withContext(Dispatchers.IO) {
            val newState =
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            invokeOnPackageService(
                method = "setApplicationEnabledSetting",
                parameterTypes = arrayOf(strType, intType, intType, intType, strType),
                args = arrayOf(pkg, newState, 0, userId, context.packageName),
                interpret = { BinderOutcome.Success(true) },
            )
        }

    private fun packageProxy(): Any? {
        // "package" 系统服务的 binder 经 Shizuku 包装后，以 shell 身份透传所有调用。
        proxies["package"]?.let { return it }
        val created = runCatching {
            val wrapper: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapper)
        }.getOrNull() ?: return null
        proxies["package"] = created
        return created
    }

    private fun invokeOnPackageService(
        method: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>,
        interpret: (Any?) -> BinderOutcome,
    ): BinderOutcome {
        val proxy = packageProxy() ?: return BinderOutcome.ChannelUnavailable
        return try {
            val target: Method = proxy.javaClass.methods.firstOrNull {
                it.name == method && it.parameterTypes.contentEquals(parameterTypes)
            } ?: return BinderOutcome.ChannelUnavailable
            interpret(target.invoke(proxy, *args))
        } catch (invocation: InvocationTargetException) {
            BinderOutcome.Failed(invocation.cause?.message ?: invocation.cause?.toString() ?: "未知宿主错误")
        } catch (reflective: ReflectiveOperationException) {
            BinderOutcome.ChannelUnavailable
        }
    }
}
