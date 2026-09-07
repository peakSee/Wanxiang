package top.wanxiang.app.runtime.shell

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
) {
    val isSuccess: Boolean get() = exitCode == 0
}
