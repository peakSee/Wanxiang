package top.wanxiang.app.runtime.pty

/**
 * JNI bindings for the native forkpty backend (see app/src/main/cpp/pty_native.c).
 * The native library is loaded lazily; loading failure is detected by
 * [NativePtyManager.nativeAvailable] and the script-based backend is used instead.
 */
internal object NativePty {
    fun tryLoad(): Boolean = try {
        System.loadLibrary("pty_native")
        true
    } catch (throwable: UnsatisfiedLinkError) {
        false
    } catch (throwable: Throwable) {
        false
    }

    external fun openAndExec(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        columns: Int,
        rows: Int,
    ): IntArray

    external fun readFd(fd: Int, buffer: ByteArray): Int

    external fun writeFd(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    external fun resizeFd(fd: Int, columns: Int, rows: Int): Int

    external fun killPid(pid: Int, signal: Int): Int

    external fun waitPid(pid: Int)

    external fun closeFd(fd: Int)
}
