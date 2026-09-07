package top.wanxiang.app.runtime.privilege

import android.content.Context
import org.json.JSONObject

/**
 * 运行在 Shizuku shell UID（或 Sui root UID）下的 UserService。
 *
 * 该类不能依赖常规 Android 应用 Context；它只负责执行受控 shell 并通过 Binder
 * 返回有界结果，权限选择、审批与审计全部留在应用进程。
 */
class ShizukuHostUserService : IShizukuHostService.Stub {
    private val runner = HostProcessRunner { command ->
        ProcessBuilder("/system/bin/sh", "-c", command).start()
    }
    @Suppress("unused")
    constructor()

    /** Shizuku 13+ 会优先使用带 Context 的构造器。 */
    @Suppress("unused")
    constructor(context: Context) : this()

    override fun execute(operationId: String, command: String): String = runner.execute(operationId, command).let { result ->
        encode(result.success, result.exitCode, result.stdout, result.stderr)
    }

    override fun cancel(operationId: String): Boolean = runner.cancel(operationId)

    override fun destroy() {
        System.exit(0)
    }

    private fun encode(success: Boolean, exitCode: Int, stdout: String, stderr: String): String =
        JSONObject()
            .put("success", success)
            .put("exitCode", exitCode)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .toString()

}
