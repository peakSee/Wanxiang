package top.wanxiang.app.runtime.tools

import top.wanxiang.app.core.common.files.SafeFileTree
import top.wanxiang.app.core.network.ChecksumVerifier
import top.wanxiang.app.core.network.DownloadRequest
import top.wanxiang.app.core.network.FileDownloader
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.RuntimePathManager
import top.wanxiang.app.runtime.rootfs.TarStreamExtractor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

/** Installs pinned official ARM64 runtime archives outside the replaceable rootfs. */
@Singleton
class RuntimeBinaryInstaller @Inject constructor(
    private val pathManager: RuntimePathManager,
    private val linuxRuntime: LinuxRuntime,
    private val fileDownloader: FileDownloader,
    private val checksumVerifier: ChecksumVerifier,
    private val tarStreamExtractor: TarStreamExtractor,
) {
    suspend fun installNode(): String = withContext(Dispatchers.IO) {
        val distroId = linuxRuntime.activeDistroId.value
        val version = NODE_VERSION
        val runtimeRoot = File(pathManager.wanxiangRuntimesDir(distroId), "node/$version")
        val nodeExecutable = File(runtimeRoot, "bin/node")
        if (!nodeExecutable.isFile) {
            pathManager.ensureDistroDirectories(distroId)
            val archive = File(pathManager.cacheDir, "node-$version-linux-arm64.tar.xz")
            if (archive.isFile && runCatching { checksumVerifier.verify(archive, NODE_SHA256) }.isFailure) {
                archive.delete()
            }
            if (!archive.isFile) {
                fileDownloader.download(
                    DownloadRequest(
                        url = NODE_URL,
                        destination = archive,
                        partialFile = File("${archive.absolutePath}.part"),
                        sha256 = NODE_SHA256,
                    ),
                ).collect { }
            }

            val staging = File(pathManager.wanxiangRootDir(distroId), ".staging-node-$version")
            SafeFileTree.delete(staging)
            staging.mkdirs()
            archive.inputStream().use { input ->
                XZInputStream(input).use { xz -> tarStreamExtractor.extract(xz, staging) }
            }
            val extractedRoot = staging.listFiles().orEmpty().singleOrNull { it.isDirectory }
                ?: error("Node archive layout is invalid")
            check(File(extractedRoot, "bin/node").isFile) { "Node archive has no ARM64 executable" }
            SafeFileTree.delete(runtimeRoot)
            runtimeRoot.parentFile?.mkdirs()
            check(extractedRoot.renameTo(runtimeRoot)) { "无法提交 Node Runtime" }
            archive.delete()
        }
        createWrappers(distroId)
        version
    }

    suspend fun removeNode() = withContext(Dispatchers.IO) {
        val distroId = linuxRuntime.activeDistroId.value
        SafeFileTree.delete(File(pathManager.wanxiangRuntimesDir(distroId), "node"))
        listOf("node", "npm", "npx").forEach { File(pathManager.wanxiangBinDir(distroId), "$it").delete() }
    }

    private fun createWrappers(distroId: String) {
        val bin = pathManager.wanxiangBinDir(distroId)
        bin.mkdirs()
        mapOf(
            "node" to "node",
            "npm" to "npm",
            "npx" to "npx",
        ).forEach { (name, target) ->
            val wrapper = File(bin, name)
            wrapper.writeText(
                "#!/bin/sh\nexec /opt/wanxiang/runtimes/node/$NODE_VERSION/bin/$target \"\$@\"\n",
            )
            wrapper.setExecutable(true, false)
        }
    }

    private companion object {
        const val NODE_VERSION = "22.22.3"
        const val NODE_URL = "https://nodejs.org/download/release/v22.22.3/node-v22.22.3-linux-arm64.tar.xz"
        const val NODE_SHA256 = "1c4a9933a5e45bc88f54f70b5f91232c127ec49f1a5989d23fb85824c7adf9b7"
    }
}
