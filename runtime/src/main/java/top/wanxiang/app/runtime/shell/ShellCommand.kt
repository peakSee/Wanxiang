package top.wanxiang.app.runtime.shell

data class ShellCommand(
    val commandLine: String,
    val workingDirectory: String = "/root",
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val onOutput: ((String) -> Unit)? = null,
    /** Explicitly request the isolated x86_64 guest through ARM64 QEMU. */
    val useQemuCompatibility: Boolean = false,
    /**
     * Force a real TTY (Debian `script`) for long-running build commands so
     * Gradle/JVM/flutter line-buffer their output instead of fully buffering it
     * on a pipe; without it the app stops receiving build logs after a few lines.
     */
    val forcePty: Boolean = false,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}