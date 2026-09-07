package top.wanxiang.app.runtime.proot

import java.io.File

/**
 * Android 宿主会给 App 进程注入一组补充 GID（inet/everybody/OEM 组等），这些 GID
 * 在发行版 /etc/group 里没有条目，登录 shell 枚举补充组时 coreutils 会逐个打印
 * "groups: cannot find name for group ID xxx" 警告。把缺失的宿主 GID 幂等补写进
 * guest /etc/group 即可消除。纯函数逻辑放这里便于 JVM 单测覆盖。
 */
internal fun syncGuestGroups(rootfsDir: File, hostGroupIds: List<Int>) {
    if (hostGroupIds.isEmpty()) return
    val groupFile = File(rootfsDir, "etc/group")
    if (!groupFile.isFile) return
    val existingGids = groupFile.readLines()
        .mapNotNullTo(HashSet()) { line -> line.split(':').getOrNull(2)?.trim()?.toIntOrNull() }
    val missing = hostGroupIds.filterNot { it in existingGids }.distinct()
    if (missing.isEmpty()) return
    groupFile.appendText(missing.joinToString("") { gid -> "host_g$gid:x:$gid:\n" })
}
