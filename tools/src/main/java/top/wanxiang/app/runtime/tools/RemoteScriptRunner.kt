package top.wanxiang.app.runtime.tools

import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URI
import kotlinx.coroutines.delay

/**
 * Downloads an official installer into the Linux runtime before executing it.
 *
 * The script is never piped directly from curl into a shell: it is stored with
 * mode 0700, checked for non-empty content, and removed through a shell trap
 * on every exit path. URLs are deliberately allow-listed because these are
 * application-owned installers, not arbitrary manifest commands.
 */
@Singleton
class RemoteScriptRunner @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun run(
        spec: RemoteScriptSpec,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        val uri = runCatching { URI(spec.url) }.getOrElse {
            throw IllegalArgumentException("官方安装脚本 URL 无效", it)
        }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "官方安装脚本必须使用 HTTPS"
        }
        require(uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) {
            "官方安装脚本 URL 不允许用户信息、片段或非标准端口"
        }
        require(uri.host.orEmpty().lowercase() in ALLOWED_HOSTS) {
            "不允许执行未登记的安装脚本来源：${uri.host.orEmpty()}"
        }
        require(SAFE_NAME.matches(spec.name)) { "安装脚本名称无效" }

        // 安装脚本内含大体积下载（如 Hermes 236MB 的 git 浅克隆），移动网络/代理
        // 截断属于偶发故障；官方脚本对中断克隆有清理逻辑，重试是安全的。
        val attempts = spec.retries + 1
        for (attempt in 0 until attempts) {
            val result = linuxRuntime.execute(
                ShellCommand(
                    commandLine = buildCommand(spec),
                    environment = environment,
                    timeoutMs = INSTALLER_TIMEOUT_MS,
                ),
            )
            if (result.isSuccess || attempt == attempts - 1) return result
            delay(RETRY_DELAY_MS)
        }
        error("unreachable")
    }

    private fun buildCommand(spec: RemoteScriptSpec): String {
        val scriptPath = "/tmp/wanxiang-installer-${spec.name}.sh"
        val quotedUrl = shellQuote(spec.url)
        val quotedPath = shellQuote(scriptPath)
        val arguments = spec.arguments.joinToString(" ") { shellQuote(it) }
        return buildString {
            append("set -eu; umask 077; ")
            append("script_path=$quotedPath; ")
            append("trap 'rm -f \"\$script_path\"' EXIT HUP INT TERM; ")
            append("curl -fsSL --connect-timeout 10 --max-time 60 --max-redirs 0 --proto '=https' --tlsv1.2 $quotedUrl -o \"\$script_path\"; ")
            append("test -s \"\$script_path\"; chmod 700 \"\$script_path\"; ")
            spec.sha256?.let { checksum ->
                require(SHA256.matches(checksum)) { "安装脚本 SHA-256 格式无效" }
                append("printf '%s  %s\\n' ${shellQuote(checksum)} \"\$script_path\" | sha256sum -c -; ")
            }
            append("bash \"\$script_path\"")
            if (arguments.isNotBlank()) append(" $arguments")
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    companion object {
        // 官方安装脚本需要 npm install / 下载二进制（OpenClaw 依赖较多，走代理
        // 可能超过 10 分钟），远超一次普通命令的 30 秒默认超时。
        private const val INSTALLER_TIMEOUT_MS = 20 * 60 * 1000L
        private const val RETRY_DELAY_MS = 3_000L
        private val SAFE_NAME = Regex("[a-z0-9-]{1,32}")
        private val SHA256 = Regex("[a-fA-F0-9]{64}")
        private val ALLOWED_HOSTS = setOf(
            "chatgpt.com",
            "openclaw.ai",
            "hermes-agent.nousresearch.com",
        )
    }
}

data class RemoteScriptSpec(
    val name: String,
    val url: String,
    val arguments: List<String> = emptyList(),
    val sha256: String? = null,
    val retries: Int = 0,
) {
    val host: String
        get() = runCatching {
            java.net.URI(url).host.orEmpty().lowercase()
        }.getOrDefault("")
}
