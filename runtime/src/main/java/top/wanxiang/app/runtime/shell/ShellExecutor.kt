package top.wanxiang.app.runtime.shell

import java.io.File

interface ShellExecutor {
    suspend fun execute(
        command: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long = ShellCommand.DEFAULT_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null,
    ): CommandResult
}

