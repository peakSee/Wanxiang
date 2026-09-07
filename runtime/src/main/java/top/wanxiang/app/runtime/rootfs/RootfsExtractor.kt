package top.wanxiang.app.runtime.rootfs

import java.io.File
import java.io.InputStream

interface RootfsExtractor {
    suspend fun extract(input: InputStream, destination: File, handleWhiteouts: Boolean = false)
}
