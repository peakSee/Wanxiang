package top.wanxiang.app.runtime.tools

sealed interface InstallEvent {
    enum class Phase {
        PREPARING,
        DOWNLOADING,
        VERIFYING,
        INSTALLING_DEPENDENCY,
        RUNNING_INSTALLER,
        VERIFYING_INSTALLATION,
        COMPLETED,
        FAILED,
        ROLLED_BACK,
        CANCELLED,
    }

    data class Started(val toolId: String) : InstallEvent
    data class Progress(
        val toolId: String,
        val message: String,
        val progress: Float? = null,
        val phase: Phase = Phase.RUNNING_INSTALLER,
    ) : InstallEvent
    data class Output(val toolId: String, val line: String) : InstallEvent
    data class Completed(val toolId: String, val version: String? = null) : InstallEvent
    data class Failed(val toolId: String, val message: String) : InstallEvent
    data class RolledBack(val toolId: String) : InstallEvent
    data class Cancelled(val toolId: String) : InstallEvent
}
