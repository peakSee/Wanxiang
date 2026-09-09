package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

/**
 * 🧩 插件子组件定义 (Plugin Sub-Component)
 * 隶属于某个聚合插件大套件下的原子能力组件。
 */
@Serializable
data class PluginComponent(
    val id: String,
    val name: String,
    val description: String,
    val isRequired: Boolean = false, // 是否为必选核心组件（不可取消勾选）
    val aptPackages: List<String> = emptyList(),
    val postInstallSteps: List<String> = emptyList(),
    val checkCommand: String, // 状态探针命令，返回 0 表示已就绪
)

/**
 * 📦 聚合插件大套件定义 (Plugin Bundle)
 * 将领域相关的能力（基础环境、扩展工具、逆向调试等）深度聚合成单一插件大类。
 */
@Serializable
data class PluginBundle(
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val iconName: String = "Code",
    val category: String = "开发套件",
    val components: List<PluginComponent> = emptyList(),
)

object BuiltinPluginBundles {
    /** 基础核心包：始终隐式自动预装，保证 Linux 基础终端与工具可用 */
    val baseRequiredPackages: List<String> = listOf(
        "curl", "wget", "git", "python3", "ca-certificates", "ripgrep", "fd-find", "fzf", "bat", "jq", "tmux", "tar", "gzip", "xz-utils",
    )

    /** 核心聚合大插件清单 */
    val bundles: List<PluginBundle> = listOf(
        PluginBundle(
            id = "android-suite",
            name = "Android & 移动全栈开发套件",
            summary = "Gradle 8.14、ARM64 AAPT2/NDK 与可选 Flutter、逆向审计",
            description = "集成 OpenJDK 17、Gradle 8.14.2、固定摘要的 ARM64 AAPT2 与 lzhiyong/termux-ndk，并提供可选 Flutter、C/C++ 和 JADX/APKTool 工具链。构建期禁止自动下载官方 x86_64 主机工具。",
            iconName = "Android",
            category = "移动开发",
            components = listOf(
                PluginComponent(
                    id = "android-core",
                    name = "Android 核心基础环境",
                    description = "OpenJDK 17、Android 34、Build Tools 35、Gradle 8.14.2、不可变 ARM64 AAPT2、lzhiyong NDK r29、ADB 与国内 Maven 镜像，全部在装配期一次性就位",
                    isRequired = true,
                    // aapt/zipalign/apksigner come from the downloaded Google
                    // Build-Tools archive. Installing Ubuntu's similarly named
                    // packages pulls GUI/D-Bus/OpenJDK 21 dependencies that
                    // are unnecessary and fragile inside PRoot.
                    aptPackages = listOf("openjdk-17-jdk-headless", "ca-certificates-java", "adb", "curl", "ca-certificates", "util-linux"),
                    postInstallSteps = listOf(
                        "/bin/sh /opt/wanxiang/scripts/setup_android_core.sh",
                    ),
                    checkCommand = ". /etc/profile.d/wanxiang-android.sh 2>/dev/null || true; JAVA_BIN=\"\${JAVA_HOME:-/opt/wanxiang/toolchains/android/jdk}/bin/java\"; (test -x \"\$JAVA_BIN\" || test -x /opt/wanxiang/bin/java || command -v java >/dev/null 2>&1) && test -f /opt/android-sdk/platforms/android-34/android.jar && test -f /opt/android-sdk/build-tools/35.0.0/lib/d8.jar && (test -f /opt/gradle-8.14.2/lib/gradle-launcher-8.14.2.jar || test -x /opt/wanxiang/bin/gradle || command -v gradle >/dev/null 2>&1) && (test -x \"\${WANXIANG_AAPT2_PATH:-/opt/android-sdk/build-tools/35.0.0/aapt2}\" || test -x /opt/android-sdk/build-tools/35.0.0/aapt2 || test -x /opt/wanxiang/bin/aapt2) && (test -f \"\${WANXIANG_NDK_PATH:-/opt/wanxiang/toolchains/android/ndk}/source.properties\" || test -f /opt/wanxiang/toolchains/android/ndk/source.properties)",
                ),
                PluginComponent(
                    id = "flutter",
                    name = "Flutter 跨平台开发环境",
                    description = "Flutter ARM64 SDK、Dart 运行时与 Android APK 构建依赖（需要 Android 核心基础环境）",
                    isRequired = false,
                    // Archives are extracted by the setup script (Python/BusyBox);
                    // Ubuntu's unzip package is unreliable in PRoot during dpkg
                    // ownership updates (zipinfo.dpkg-new).
                    aptPackages = listOf("git", "curl", "ca-certificates", "xz-utils"),
                    postInstallSteps = listOf(
                        "/bin/sh /opt/wanxiang/scripts/setup_flutter.sh",
                    ),
                    checkCommand = ". /etc/profile.d/wanxiang-android.sh 2>/dev/null || true; (test -x /opt/flutter/bin/flutter || test -x /opt/wanxiang/bin/flutter || command -v flutter >/dev/null 2>&1) && test -f /opt/android-sdk/platforms/android-34/android.jar && test -f /opt/android-sdk/build-tools/35.0.0/lib/d8.jar",
                ),
                PluginComponent(
                    id = "android-ndk",
                    name = "C/C++ & NDK 原生构建链",
                    description = "lzhiyong/termux-ndk r29 Linux AArch64 工具链，以及 CMake、Ninja、GCC/G++、Clang 等原生构建辅助工具",
                    isRequired = false,
                    aptPackages = listOf("cmake", "ninja-build", "gcc", "g++", "clang", "make", "pkg-config", "util-linux"),
                    postInstallSteps = listOf(
                        "/bin/sh /opt/wanxiang/scripts/setup_termux_ndk.sh",
                    ),
                    checkCommand = ". /etc/profile.d/wanxiang-android.sh 2>/dev/null || . /opt/wanxiang/toolchains/android/ndk/wanxiang-ndk.env 2>/dev/null || true; (command -v cmake >/dev/null 2>&1 || test -x /opt/wanxiang/bin/cmake || test -x /opt/wanxiang/tools/android-suite-offline/cmake/bin/cmake || test -x /usr/bin/cmake) && (test -f \"\${WANXIANG_NDK_PATH:-/opt/wanxiang/toolchains/android/ndk}/source.properties\" || test -f /opt/wanxiang/toolchains/android/ndk/source.properties)",
                ),
                PluginComponent(
                    id = "android-re",
                    name = "Android 逆向分析与代码审计",
                    description = "APKTool 资源回编译、JADX-CLI Java 源码反编译器与内置 APK 逆向 MCP 服务（python3 为其运行依赖）",
                    isRequired = false,
                    aptPackages = listOf("openjdk-17-jdk-headless", "curl", "apktool", "python3"),
                    postInstallSteps = listOf(
                        "/bin/sh /opt/wanxiang/scripts/setup_jadx.sh",
                    ),
                    checkCommand = ". /etc/profile.d/wanxiang-android.sh 2>/dev/null || true; (command -v apktool >/dev/null 2>&1 || test -x /opt/wanxiang/bin/apktool || test -f /opt/wanxiang/tools/android-suite-offline/lib/apktool.jar || command -v jadx >/dev/null 2>&1 || test -x /opt/wanxiang/bin/jadx || test -x /opt/jadx/bin/jadx || test -x /opt/wanxiang/tools/android-suite-offline/jadx/bin/jadx)",
                ),
                PluginComponent(
                    id = "rust-dev",
                    name = "Rust 系统与 Android JNI 交叉编译链",
                    description = "Rust 1.85+ ARM64 独立开发工具链、Cargo 包管理、Android ARM64 原生架构交叉编译标准库及 NDK Clang 链接器绑定",
                    isRequired = false,
                    aptPackages = listOf("curl", "ca-certificates", "build-essential"),
                    postInstallSteps = listOf(
                        "/bin/sh /opt/wanxiang/scripts/setup_rust.sh",
                    ),
                    checkCommand = ". /etc/profile.d/wanxiang-android.sh 2>/dev/null || true; (command -v rustc >/dev/null 2>&1 || test -x /opt/wanxiang/bin/rustc || test -x /opt/wanxiang/toolchains/rust/bin/rustc) && (command -v cargo >/dev/null 2>&1 || test -x /opt/wanxiang/bin/cargo || test -x /opt/wanxiang/toolchains/rust/bin/cargo)",
                ),
            ),
        ),
        PluginBundle(
            id = "code-search-suite",
            name = "代码检索与终端效率套件",
            summary = "rg、fd、fzf、bat 代码检索四件套",
            description = "集成 ripgrep 全文检索、fd 文件查找、fzf 模糊筛选与 bat 语法高亮预览，并统一 Debian/Ubuntu 下的命令名称。",
            iconName = "Search",
            category = "开发效率",
            components = listOf(
                PluginComponent(
                    id = "code-search-toolkit",
                    name = "代码检索四件套 (rg / fd / fzf / bat)",
                    description = "高速全文检索、文件发现、交互式模糊筛选与带语法高亮的源码预览",
                    isRequired = true,
                    aptPackages = listOf("ripgrep", "fd-find", "fzf", "bat"),
                    checkCommand = "(command -v rg >/dev/null 2>&1 || test -x /opt/wanxiang/bin/rg) && (command -v fd >/dev/null 2>&1 || command -v fdfind >/dev/null 2>&1 || test -x /usr/local/bin/fd) && (command -v fzf >/dev/null 2>&1 || test -x /usr/bin/fzf) && (command -v bat >/dev/null 2>&1 || command -v batcat >/dev/null 2>&1 || test -x /usr/local/bin/bat)",
                ),
            ),
        ),
        PluginBundle(
            id = "python-suite",
            name = "Python & AI 开发者套件",
            summary = "Python 3 运行时、pip、venv 虚拟环境与 AI 科学计算编译依赖",
            description = "包含完整的 Python 3 运行环境、pip 包管理、venv 隔离环境以及编译 Python C 扩展轮子所需的 build-essential 基础库。",
            iconName = "Code",
            category = "AI 与脚本",
            components = listOf(
                PluginComponent(
                    id = "python-core",
                    name = "Python 3 核心运行基座",
                    description = "Python 3 解释器、pip 包管理器与 venv 虚拟环境工具",
                    isRequired = true,
                    aptPackages = listOf("python3", "python3-pip", "python3-venv"),
                    checkCommand = "command -v python3 && command -v pip3",
                ),
                PluginComponent(
                    id = "python-ai-dev",
                    name = "AI 科学计算与 C 扩展编译库",
                    description = "python3-dev、build-essential、pkg-config 与底层系统头文件",
                    isRequired = false,
                    aptPackages = listOf("python3-dev", "build-essential", "pkg-config", "libffi-dev"),
                    checkCommand = "dpkg -s python3-dev 2>/dev/null || test -f /usr/include/python3*/Python.h",
                ),
            ),
        ),
        PluginBundle(
            id = "nodejs-suite",
            name = "Node.js & Web 全栈套件",
            summary = "Node.js 运行时、npm、pnpm 与现代前端全栈生态",
            description = "集成 Node.js 现代 LTS 运行时、npm 包管理器，支持 pnpm 等现代包管理与 JavaScript / TypeScript 全栈开发。",
            iconName = "Globe",
            category = "全栈开发",
            components = listOf(
                PluginComponent(
                    id = "nodejs-core",
                    name = "Node.js 核心运行时",
                    description = "Node.js 运行时与 npm 包管理器",
                    isRequired = true,
                    aptPackages = listOf("nodejs", "npm"),
                    checkCommand = "command -v node && command -v npm",
                ),
                PluginComponent(
                    id = "nodejs-pkg",
                    name = "现代包管理器与编译加速 (pnpm / yarn)",
                    description = "pnpm 与 yarn 高性能本地包缓存管理器",
                    isRequired = false,
                    postInstallSteps = listOf(
                        "/bin/sh /opt/wanxiang/scripts/setup_pnpm.sh",
                    ),
                    checkCommand = "command -v pnpm || command -v yarn",
                ),
            ),
        ),
    )

    /**
     * 批量聚合生成单条安全、极速的安装脚本流水线
     */
    fun buildBatchInstallScript(selectedComponentIds: Set<String>): List<String> {
        val allComponents = bundles.flatMap { it.components }.filter { it.id in selectedComponentIds }
        val allAptPackages = (baseRequiredPackages + allComponents.flatMap { it.aptPackages }).distinct()

        val steps = mutableListOf<String>()
        // 1. dpkg 锁与环境自愈
        steps.add("mkdir -p /etc/dpkg/dpkg.cfg.d /usr/bin /usr/sbin /usr/lib 2>/dev/null || true")
        steps.add("printf 'force-unsafe-io\\nforce-overwrite\\n' > /etc/dpkg/dpkg.cfg.d/wanxiang-proot 2>/dev/null || true")
        steps.add("rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* /var/lib/apt/lists/lock /var/cache/apt/archives/lock /usr/bin/*.dpkg-new /usr/sbin/*.dpkg-new /usr/lib/*.dpkg-new 2>/dev/null || true")
        // A previously interrupted unzip/java-wrappers transaction can never
        // complete in PRoot because dpkg cannot chown zipinfo.dpkg-new. These
        // optional helpers are not needed: the APK supplies its own JAR-backed
        // unzip command and setup_android_core.sh links it into PATH.
        steps.add("DEBIAN_FRONTEND=noninteractive dpkg --remove --force-remove-reinstreq --force-depends unzip java-wrappers 2>/dev/null || true")
        steps.add("DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true")

        // 2. 批量聚合 APT 安装（仅执行 1 次 update 和 1 次 install；
        //    整批失败时降级为 --ignore-missing，避免个别发行版缺包导致全部装不上）
        if (allAptPackages.isNotEmpty()) {
            val packageArg = allAptPackages.joinToString(" ")
            // Runtime configures domestic mirrors (aliyun default). Keep apt
            // retries bounded so a slow mirror does not stall the whole suite.
            // ForceIPv4：手机 IPv6 半残时 apt 静默干等的头号元凶；Languages=en 跳过翻译文件。
            val aptOpts = "-o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::ForceIPv4=true -o Acquire::Languages=en"
            steps.add("DEBIAN_FRONTEND=noninteractive apt-get $aptOpts update -y || true")
            steps.add("DEBIAN_FRONTEND=noninteractive apt-get $aptOpts install -y --no-install-recommends $packageArg || DEBIAN_FRONTEND=noninteractive apt-get $aptOpts -f install -y --no-install-recommends && DEBIAN_FRONTEND=noninteractive apt-get $aptOpts install -y --no-install-recommends $packageArg")
        }

        // 3. Debian/Ubuntu 将 fd、bat 分别命名为 fdfind、batcat；统一暴露常用命令名。
        steps.add("mkdir -p /usr/local/bin; if ! command -v fd >/dev/null 2>&1 && command -v fdfind >/dev/null 2>&1; then ln -sf \"${'$'}(command -v fdfind)\" /usr/local/bin/fd; fi")
        steps.add("mkdir -p /usr/local/bin; if ! command -v bat >/dev/null 2>&1 && command -v batcat >/dev/null 2>&1; then ln -sf \"${'$'}(command -v batcat)\" /usr/local/bin/bat; fi")

        // 4. 各子组件后置处理
        allComponents.forEach { comp ->
            steps.addAll(comp.postInstallSteps)
        }

        return steps
    }
}
