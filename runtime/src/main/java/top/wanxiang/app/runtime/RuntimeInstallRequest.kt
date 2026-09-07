package top.wanxiang.app.runtime

data class RuntimeInstallRequest(
    val distributionId: String,
    val registryRoute: RegistryRoute = RegistryRoute.AUTO,
)

data class RootfsUpdateInfo(
    val distroId: String,
    val imageReference: String,
    val currentVersion: String?,
    val currentDigest: String?,
    val latestDigest: String,
    val hasUpdate: Boolean,
)

enum class RegistryRoute { AUTO, OFFICIAL, CHINA_ACCELERATED }

data class DistributionSpec(
    val id: String,
    val displayName: String,
    val version: String,
    val imageReference: String,
) {
    /** 界面展示用完整名称：名称 + 版本号（如 "Ubuntu 24.04 LTS"）。 */
    val displayWithVersion: String
        get() = if (version.isBlank()) displayName else "$displayName $version"
}

object DistributionCatalog {
    val supported = listOf(
        DistributionSpec("ubuntu", "Ubuntu", "24.04 LTS", "buildpack-deps:noble-scm"),
        DistributionSpec("debian", "Debian", "12 (Bookworm)", "buildpack-deps:bookworm-scm"),
        DistributionSpec("kali", "Kali Linux", "Rolling", "kalilinux/kali-rolling:latest"),
        DistributionSpec("arch", "Arch Linux", "Rolling (ARM)", "archlinux:latest"),
        DistributionSpec("fedora", "Fedora", "40", "fedora:40"),
        DistributionSpec("alpine", "Alpine Linux", "3.19", "alpine:3.19"),
        DistributionSpec("almalinux", "AlmaLinux", "9", "almalinux:9"),
        DistributionSpec("rocky", "Rocky Linux", "9", "rockylinux/rockylinux:9"),
        DistributionSpec("opensuse", "openSUSE", "Tumbleweed", "opensuse/tumbleweed:latest"),
        DistributionSpec("manjaro", "Manjaro", "Rolling", "manjarolinux/base:latest"),
    )

    fun require(id: String): DistributionSpec = supported.firstOrNull { it.id.equals(id, ignoreCase = true) }
        ?: supported.first()
}
