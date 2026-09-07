package top.wanxiang.app.runtime.proot

import top.wanxiang.app.core.common.logging.AppLogger
import top.wanxiang.app.core.common.result.AppError
import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.common.result.ErrorCode
import top.wanxiang.app.runtime.ElfInspector
import top.wanxiang.app.runtime.RuntimePathManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verifies the APK-bundled PRoot runtime.
 *
 * Android 10+ does not allow this target-SDK-34 app to execute code downloaded
 * into filesDir. Both the tracer and its external loader therefore have to be
 * packaged as extracted native libraries; the selected Linux RootFS is downloaded through OCI.
 */
@Singleton
class ProotInstaller @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val elfInspector: ElfInspector,
    private val logger: AppLogger,
) {
    suspend fun install(): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            pathManager.ensureDirectories()
            val proot = pathManager.bundledProotFile
            val loader = pathManager.bundledProotLoaderFile
            require(proot.isFile && proot.length() > MIN_RUNTIME_COMPONENT_BYTES) {
                "APK 缺少 ARM64 PRoot 主程序（libproot.so）"
            }
            require(
                loader.isFile &&
                    loader.length() > MIN_RUNTIME_COMPONENT_BYTES &&
                    loader.length() <= MAX_LOADER_BYTES,
            ) {
                "APK 缺少 PRoot ARM64 loader（libproot-loader.so）。" +
                    "Termux 版 PRoot 没有内置 loader，不能只复制 proot 主程序。"
            }
            require(loader.canExecute()) {
                "PRoot loader 不可执行；必须从 APK nativeLibraryDir 解压，不能放在 filesDir"
            }
            elfInspector.requireAarch64(proot)
            elfInspector.requireAarch64(loader)
            validateExecutable(proot)
            logger.i("APK-bundled PRoot tracer and loader validated")
            AppResult.Success(proot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.e("PRoot runtime validation failed", throwable)
            AppResult.Failure(
                AppError(
                    code = ErrorCode.INSTALLATION_FAILED,
                    message = "PRoot 运行组件校验失败：${throwable.message ?: "未知错误"}",
                    cause = throwable,
                ),
            )
        }
    }

    private fun validateExecutable(file: File) {
        val process = try {
            ProcessBuilder(file.absolutePath, "--version")
                .apply {
                    environment().clear()
                    environment().putAll(pathManager.hostProcessEnvironment())
                }
                .redirectErrorStream(true)
                .start()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "无法启动 ${file.absolutePath}：${throwable.message}",
                throwable,
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "PRoot 主程序执行失败（exit=$exitCode）：${output.trim().ifBlank { "无输出" }}"
        }
        require(output.contains(EXPECTED_PROOT_VERSION)) {
            "PRoot 主程序版本不匹配，期望 $EXPECTED_PROOT_VERSION"
        }
    }

    private companion object {
        const val MIN_RUNTIME_COMPONENT_BYTES = 4096L
        const val MAX_LOADER_BYTES = 4L * 1024L * 1024L
        const val EXPECTED_PROOT_VERSION = "5.1.107.92"
    }
}
