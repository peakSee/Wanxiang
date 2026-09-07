package top.wanxiang.app.core.tools

import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ManagedProcess
import top.wanxiang.app.runtime.shell.SessionConfig

/**
 * Runtime-facing contract for a manifest-backed tool.
 *
 * Install, verify, launch and optional local-service behavior stay together in
 * the adapter; managers and UI do not need a tool-id switch statement.
 */
interface ToolRuntimeAdapter : ToolInstallerAdapter {
    suspend fun launch(): CommandResult
    suspend fun verify(): CommandResult
    suspend fun uninstall(deleteData: Boolean = false): ToolActionResult
    suspend fun startService(): ManagedProcess? = null
    suspend fun interactiveSessionConfig(): SessionConfig? = null
}

data class ToolActionResult(
    val success: Boolean,
    val message: String,
)
