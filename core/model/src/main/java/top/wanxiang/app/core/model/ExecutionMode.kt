package top.wanxiang.app.core.model

/**
 * 万象支持的系统运行与特权模式。
 */
enum class ExecutionMode(
    val id: String,
    val shortLabel: String,
    val title: String,
    val summary: String,
    val requiredPrivilege: String,
    val capabilities: List<String>,
) {
    PROOT(
        id = "PROOT",
        shortLabel = "PRoot",
        title = "PRoot 用户态沙箱 (默认)",
        summary = "无需任何特殊权限，开箱即用。通过 PRoot 模拟 Linux 用户态环境。",
        requiredPrivilege = "无（普通应用权限）",
        capabilities = listOf(
            "100% 免 Root 运行完整的 Debian / Ubuntu",
            "支持 APT / APK 等包管理生态",
            "支持 PTY 交互终端与 Agent 工具链",
        ),
    ),
    SHIZUKU(
        id = "SHIZUKU",
        shortLabel = "Shizuku",
        title = "Shizuku / ADB 提权模式",
        summary = "通过 Shizuku 服务获得 ADB 权限，解除 Android 系统限制并开启整机自动化。",
        requiredPrivilege = "Shizuku 服务授权 (UID 2000 shell)",
        capabilities = listOf(
            "解除 Android 12+ 幽灵进程 32 上限 (Phantom Process Killer)",
            "深度后台保活白名单豁免",
            "Agent 宿主自动化操控 (input / 截屏 / logcat)",
            "突破 /sdcard/Android/data 文件访问限制",
        ),
    ),
    ROOT(
        id = "ROOT",
        shortLabel = "Root",
        title = "Root 原生性能模式",
        summary = "请求 SU 权限，释放 100% 硬件算力，彻底避免 PRoot 系统调用拦截损耗。",
        requiredPrivilege = "Root 权限 (Magisk / KernelSU / APatch / UID 0)",
        capabilities = listOf(
            "100% 原生 Linux 性能（零 syscall 拦截开销）",
            "原生 ext4 语义（彻底解决 perl/dpkg 硬链接问题）",
            "支持 Docker / Podman 容器与镜像运行",
            "直接访问 GPU/NPU 节点 (/dev/kgsl-3d0 等) 硬件加速",
            "支持监听 80/443 特权端口与 iptables",
        ),
    );

    companion object {
        /** 兼容历史持久化值：已下线的 ADB 等未知 id 会安全回落到 PROOT。 */
        fun fromId(id: String): ExecutionMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PROOT
    }
}
