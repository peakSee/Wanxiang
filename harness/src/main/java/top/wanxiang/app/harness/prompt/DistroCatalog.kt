package top.wanxiang.app.harness.prompt

/** 发行版显示名与包管理器命令的静态映射。 */
object DistroCatalog {

    fun displayName(distroId: String): String = when (distroId.lowercase()) {
        "ubuntu" -> "Ubuntu 24.04 (Noble Numbat)"
        "debian" -> "Debian 12 (Bookworm)"
        "alpine" -> "Alpine Linux 3.19"
        "archlinux", "arch" -> "Arch Linux"
        "fedora" -> "Fedora 40"
        "void" -> "Void Linux"
        else -> "$distroId Linux"
    }

    fun packageManagerCommand(distroId: String): String = when (distroId.lowercase()) {
        "alpine" -> "apk add <package>"
        "archlinux", "arch" -> "pacman -S <package>"
        "fedora" -> "dnf install -y <package>"
        "void" -> "xbps-install -S <package>"
        else -> "apt-get install -y <package>"
    }
}
