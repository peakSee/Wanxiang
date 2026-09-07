package top.wanxiang.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val triggerCommand: String? = null,
    val iconName: String = "Code",
    val isEnabled: Boolean = true,
    val isBuiltin: Boolean = true,
    val isImmutable: Boolean = false,
    val category: String = "通用",
    /** 私有 Skill 包解压目录；为空表示仅包含提示词。 */
    val resourcePath: String? = null,
)

object BuiltinSkills {
    val presets: List<AgentSkill> = listOf(
        AgentSkill(
            id = "agent_context",
            name = "上下文与任务记忆规划",
            description = "万象核心系统能力：提供长期事实记忆 (memory)、任务执行规划 (plan) 与工作草稿便签 (scratchpad)",
            systemPrompt = """
                【Agent 上下文与任务记忆规划核心指导】：
                1. 长期记忆 (memory)：当用户表达偏好、架构规范或重要事实时，主动调用 memory(action="save", key=..., value=..., kind="preference"|"rule"|"fact") 持久化存储；
                2. 任务规划 (plan)：对于复杂多步骤任务，第一时间调用 plan(action="replace_active", goal=..., steps=[...]) 拆解子步骤，并在完成每一步时更新推进进度；
                3. 工作便签 (scratchpad)：临时分析草稿、排查假说与子目标使用 scratchpad 记录。
            """.trimIndent(),
            triggerCommand = "/context",
            iconName = "Star",
            isEnabled = true,
            isBuiltin = true,
            isImmutable = true,
            category = "核心系统",
        ),
        AgentSkill(
            id = "linux_ops",
            name = "Linux 沙箱运维专精",
            description = "深入理解 PRoot 特性、Debian dpkg setuid 残留修复、前台常驻进程与网络诊断",
            systemPrompt = """
                【Linux 沙箱运维专精指导】：
                1. PRoot 环境中没有真实 root 权限，避免执行破坏性内核命令（如 mount、sysctl、chown）。
                2. dpkg 安装/升级时若提示 unable to securely remove .dpkg-tmp，先清理临时文件并使用 chmod u-s 降低 setuid 属性后再重试。
                3. 无 systemd 支持；需常驻的后台服务必须通过 WanXiang process 工具注册，并保持前台运行。不要在普通 base 命令中使用 nohup 或 &，PRoot 退出时会回收未托管子进程。
            """.trimIndent(),
            triggerCommand = "/ops",
            iconName = "Terminal",
            isEnabled = true,
            isBuiltin = true,
            category = "系统运维",
        ),
        AgentSkill(
            id = "code_refactor",
            name = "代码重构与审查",
            description = "精细化代码审查、遵循架构模式、利用 edit 工具进行小步无损重构",
            systemPrompt = """
                【代码重构与审查指导】：
                1. 优先使用 edit 进行局部精准修改，避免无意义的大范围整文件重写。
                2. edit 时 oldText 必须精确匹配且上下文唯一；修改前后必须核对语法一致性。
                3. 遵循现存项目的代码规范（命名、注释、架构分层），避免引入不必要的新依赖。
            """.trimIndent(),
            triggerCommand = "/refactor",
            iconName = "Check",
            isEnabled = true,
            isBuiltin = true,
            category = "编程开发",
        ),
        AgentSkill(
            id = "git_workflow",
            name = "Git 敏捷工作流",
            description = "自动化 Git 状态分析、分支管理、原子提交信息规范生成与冲突诊断",
            systemPrompt = """
                【Git 敏捷工作流指导】：
                1. 执行 git 提交前务必通过 base 运行 git status 和 git diff 确认改动范围。
                2. 提交信息（commit message）遵循规范：feat / fix / refactor / docs / chore 等。
                3. 遇到冲突时，先通过 read 读取带冲突标记的文件，分析原因后再行修复。
            """.trimIndent(),
            triggerCommand = "/git",
            iconName = "Code",
            isEnabled = true,
            isBuiltin = true,
            category = "版本控制",
        ),
        AgentSkill(
            id = "build_debugger",
            name = "全栈构建与排错",
            description = "快速定位 Python venv、Node.js/npm、C/C++ makefile 等构建报错与依赖解析",
            systemPrompt = """
                【全栈构建与排错指导】：
                1. Python 项目优先推荐创建和激活 venv 虚拟环境，避免污染全局 python 库。
                2. Node.js/npm 安装依赖时若遇网络或编译错误，先检查 package.json 与 node-gyp 依赖。
                3. C/C++ 编译优先使用 gcc/g++ 或 cmake，并注意 Android/ARM64 平台的架构兼容性。
            """.trimIndent(),
            triggerCommand = "/debug",
            iconName = "Alert",
            isEnabled = true,
            isBuiltin = true,
            category = "编程开发",
        ),
        AgentSkill(
            id = "mobile_build_guard",
            name = "移动端构建环境守卫",
            description = "Android/Flutter 构建前强制自检，优先使用万象 ARM64 工具链，并在必要时显式切换 QEMU 兼容会话",
            systemPrompt = """
                【移动端构建环境守卫】：
                1. 在执行 Android 或 Flutter 构建前，先运行 `wanxiang-build doctor <项目路径>`；不得跳过自检后直接下载或执行未知主机架构的工具。
                2. 自检通过后统一使用 `wanxiang-build android <项目路径> [Gradle任务]` 或 `wanxiang-build flutter <项目路径> [Flutter参数]`。优先使用万象内置 JDK、Android SDK、Gradle、AAPT2、NDK 与 Flutter SDK。
                3. 不要用 `apt`、`sdkmanager`、Gradle 自动下载或 Flutter 自动修复去覆盖万象工具链；不要下载 x86/x86_64 AAPT2、JDK、NDK 或 Android 主机工具替代 ARM64 核心资源。
                4. 第三方项目版本不匹配时，先读取 Gradle wrapper、AGP、Kotlin、compileSdk、NDK 与 Flutter/Dart 约束，再以当前工具链为基准做最小项目对齐。修改前向用户说明需要升级或降级的文件与版本。
                5. 只有自检明确报告“x86_64 主机 ELF/Exec format error”且 QEMU 兼容环境已经就绪时，才使用 `wanxiang-build ... --qemu`；QEMU 只运行隔离 x86_64 用户态工具，最终 APK 仍必须只面向 ARM64。
                6. 如果用户直接要求 `./gradlew` 或 `flutter build apk`，仍先执行 doctor；构建失败时保留原始日志，区分项目源码错误、依赖版本错误、网络错误与主机架构错误，禁止把所有失败都盲目转入 QEMU。
            """.trimIndent(),
            triggerCommand = "/buildguard",
            iconName = "Shield",
            isEnabled = true,
            isBuiltin = true,
            category = "移动端开发",
        ),
        AgentSkill(
            id = "mobile_project_align",
            name = "移动端项目兼容对齐",
            description = "分析第三方 Android/Flutter 项目与 WanXiang ARM64 工具链的差异，生成变更计划并在确认后执行最小调整",
            systemPrompt = """
                【移动端项目兼容对齐】：
                1. 这是只读分析优先的 Skill。先运行 `wanxiang-build analyze <项目路径>`，必要时加 `--offline`，并读取输出中的 compileSdk、Gradle Wrapper、AGP、Kotlin、ABI 与缓存结论。
                2. 未获得用户确认前，不得修改第三方项目的 Gradle、pubspec、AndroidManifest、ABI 或 Wrapper 文件；先给出明确的变更清单、当前值、目标值和风险。
                3. WanXiang 默认基准是 ARM64、Android Platform 34、Build-Tools 35、Gradle 8.14.2、Flutter Android arm64。不要为了迁就项目静默下载 x86/x86_64 JDK、AAPT2、NDK 或其他主机工具。
                4. 用户确认对齐后，只做最小修改：优先修改 compileSdk/targetSdk、Wrapper 调度和 ABI 声明；保留业务代码与用户自定义仓库；每个文件修改后立即检查 diff。
                5. 如果项目依赖版本无法在当前 ARM64/离线缓存中满足，提供三种选择：补齐离线缓存、显式使用 QEMU x86_64 会话、取消构建。不要把网络错误伪装成架构错误。
                6. 对齐或构建完成后必须运行 `wanxiang-build doctor <项目路径>`，构建 APK 后再运行统一 ABI 验证；最终只接受 arm64-v8a，不接受 x86/x86_64 产物。
            """.trimIndent(),
            triggerCommand = "/alignmobile",
            iconName = "Adjustments",
            isEnabled = true,
            isBuiltin = true,
            isImmutable = true,
            category = "移动端开发",
        ),
        AgentSkill(
            id = "android_cli",
            name = "Google Android 原生开发助手",
            description = "精通 Android SDK 工具链、Jetpack Compose 与现代 Gradle 流水线",
            systemPrompt = """
                【Google Android 原生开发助手指导】：
                1. 立即行动与直接交付：当用户需要创建或初始化 Android 项目时，直接在当前工作区中使用 write 工具生成完整的标准工程骨架文件（settings.gradle.kts、build.gradle.kts、app/build.gradle.kts、AndroidManifest.xml、MainActivity.kt 与 Compose UI 页面代码）。严禁调用已移除的 `android` CLI，也不要在无必要时反复探测环境或询问多余问题！
                2. 架构规范（与最新工业与官方标准完全拉齐）：
                   - 官方生态：遵循 Google Android Agents 规范 (https://developer.android.com/tools/agents)，支持 AGENTS.md 与官方 Android Skills；
                   - 语言与编译器：Kotlin 2.x (2.4.x) + Java 17 (compileSdk = 34/35, minSdk = 26)；
                   - UI 框架：Jetpack Compose + Material3 现代化声明式 UI，模块化目录结构；
                   - 构建工具链：Gradle 8.14.2 (Kotlin DSL *.gradle.kts)；
                3. 构建与运行指南：
                   - 系统已预装 OpenJDK 17、adb、Gradle 8.14.2、不可变 ARM64 AAPT2 与 lzhiyong/termux-ndk r29；Android 34 平台包由插件装配期就位于 /opt/android-sdk，不要假设存在官方 `android` CLI；
                   - 全局 Gradle 已注入阿里云 Maven 镜像 (/root/.gradle/init.gradle)，依赖下载自动走国内加速，无需手工配置；
                   - 构建排错时先运行 `wanxiang-build doctor <工作区>`，再使用 `wanxiang-build android <工作区> assembleDebug`；
                   - 诊断环境使用 `java -version`、`adb version`、`gradle --version` 和实际 Gradle/AAPT2 错误输出，不要调用 `android doctor` 或 `android skills`；
                    - 【ARM64 沙箱构建核心铁律】：AAPT2 与 NDK 路径由插件写入 Gradle 用户级策略并锁定到 SHA-256 不可变目录；不要在项目中另写 AAPT2/NDK 路径。项目 `gradle.properties` 只需保留：
                      ```properties
                      android.builder.sdkDownload=false
                      org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8
                      org.gradle.daemon=false
                      org.gradle.parallel=false
                      org.gradle.workers.max=2
                      org.gradle.caching=true
                      kotlin.daemon.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m
                      ```
                      这样可禁止 AGP 自动下载官方 x86_64 SDK/NDK 主机工具，并限制 PRoot 内 Gradle 的并发内存峰值。
                   - 若需要更新 Gradle，使用腾讯云国内镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.2-bin.zip` 秒级满速部署！
                4. 安装到本手机（极速交付流程）：
                   - 当用户要求“安装到手机 / 运行到本机 / 装上看看”时：
                     a. 检查 APK 是否已构建，若未构建先执行上述 Gradle 构建脚本；
                     b. 通过当前工作区的宿主安装流程执行 `wanxiang-host install-apk <apk路径>`；
                     c. 若检测到 ADB 已连接（如无线调试 127.0.0.1），可优先执行 `adb install -r <apk路径>` 实现直装；
                     d. 汇报安装/导出成功，提示用户已就绪！
                5. 沙箱环境配置：Java 环境为 OpenJDK 17 (JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64)；具体工具状态以当前会话注入的环境段和命令自检结果为准。
            """.trimIndent(),
            triggerCommand = "/android",
            iconName = "Play",
            isEnabled = true,
            isBuiltin = true,
            category = "安卓开发",
        ),
        AgentSkill(
            id = "android_reverse",
            name = "Android 逆向与代码审计",
            description = "精通 APK 解包、JADX-CLI 全自动 Java 源码反编译、Ripgrep 毫秒级检索、反射图谱与漏洞分析",
            systemPrompt = """
                【Android 逆向与代码审计指导】：
                1. 强制规划先行：第一轮工具调用必须先调用 plan(action="replace_active", ...) 建立执行看板，分阶段推进（结构感知 -> JADX 反编译 -> rg 检索 -> 反射追踪 -> 结论输出），每步完成后调用 advance。
                2. 优先使用 jadx-cli 进行全自动反编译为 Java 源码工程：
                   - 执行命令：`jadx -d out/java <APK/DEX文件路径>`
                   - 输出为完整的 Java 源码，直接阅读 `out/java/sources/`，效率远高于阅读 Smali。
                3. 毫秒级极速检索：
                   - 严禁使用 `find | xargs grep` 全量扫描！一律使用沙箱内置的 `rg` (ripgrep) 秒级搜索。
                   - 例：`rg "quickPhoneLogin" out/java/` 或 `rg -g "*.smali" "invoke-static" unpacked/`。
                4. 插件化与反射架构应对：
                   - 追踪 `Class.forName`、`getMethod`、`invoke`、`ReflectUtils`、`DexClassLoader`。
                   - 通过 `rg` 搜索全类名、方法名常量字符串定位动态代理与反射调用源头。
                5. 大文件 Smali 精准定位：
                   - 数千行大文件先用 `rg -n '^\.method' Path.smali` 获取函数行号大纲，再用 `read(offset=..., limit=...)` 精准分片读取，禁止盲目全量读取。
                6. 资源与 Smali 修改：
                   - 解包：`apktool d <APK路径> -o unpacked/`
                   - 回编译：`apktool b <解包目录> -o <新APK路径>`
                   - 优先分析 AndroidManifest.xml 获取入口 Activity、Service、自定义 Application 与权限。
            """.trimIndent(),
            triggerCommand = "/re",
            iconName = "Search",
            isEnabled = true,
            isBuiltin = true,
            category = "安卓开发",
        ),
        AgentSkill(
            id = "flutter_dev",
            name = "Flutter 跨平台开发助手",
            description = "精通 Dart 与 Flutter 3.x 跨平台架构、国内镜像构建与 APK 手机极速部署",
            systemPrompt = """
                【Flutter 跨平台开发助手指导】：
                1. 立即行动与直接交付：当用户需要创建或编辑 Flutter 跨平台项目时，直接在当前工作区生成或修改 pubspec.yaml、lib/main.dart 等标准 Dart/Flutter 代码文件，严禁多余推诿！
                2. 国内镜像与环境：
                   - 已预设环境变量 PUB_HOSTED_URL=https://pub.flutter-io.cn 与 FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn；
                   - 依赖拉取优先执行：`flutter pub get`；
                3. 构建与编译指南：
                   - 构建前先运行 `wanxiang-build doctor <工作区>`；构建 Debug APK 使用 `wanxiang-build flutter <工作区> apk --debug`；
                   - 若遇 Gradle 插件下载超时，优先在 android/build.gradle 中使用国内阿里云/腾讯云 Maven 镜像替代海外源；
                4. 一键安装到手机（极速闭环）：
                   - 编译完成后，立即拷贝生成的 APK 至宿主 Download 目录：`cp build/app/outputs/flutter-apk/*.apk /sdcard/Download/<项目名>.apk`；
                   - 若检测到无线 ADB 连接，直接执行 `adb install -r <apk路径>` 实现免确认直装。
            """.trimIndent(),
            triggerCommand = "/flutter",
            iconName = "Play",
            isEnabled = true,
            isBuiltin = true,
            category = "跨端开发",
        ),
        AgentSkill(
            id = "code_graph",
            name = "CodeGraph 代码知识图谱",
            description = "精通利用本地代码知识图谱进行毫秒级符号搜索、调用链路（Call Graph）追踪与架构探索，1 步获取代码切片与波及范围",
            systemPrompt = """
                【CodeGraph 代码知识图谱导航指导】：
                1. 消除代码探索“发现税”：
                   - 面对代码架构梳理、函数定义查找、调用链路分析或排错时，严禁使用盲目的逐文件 grep 或全量遍历！
                   - 挂载 CodeGraph MCP 服务后，优先调用 `mcp__codegraph__codegraph_explore(query=...)`，单次工具调用即可获得目标符号定义、调用链拓扑（Callers/Callees）与精准代码切片。
                2. 关系追踪与影响面分析：
                   - 向上溯源：调用 `mcp__codegraph__codegraph_callers(target=...)` 查询指定函数的所有调用方；
                   - 向下追踪：调用 `mcp__codegraph__codegraph_callees(target=...)` 列出下游依赖；
                   - 重构波及范围：修改核心接口或函数前，调用 `mcp__codegraph__codegraph_impact(target=...)` 评估直接和间接受影响模块（Blast Radius）。
                3. 图谱增量同步：
                   - 大规模修改或新增代码文件后，可调用 `mcp__codegraph__codegraph_sync()` 增量刷新图谱索引。
            """.trimIndent(),
            triggerCommand = "/codegraph",
            iconName = "Star",
            isEnabled = true,
            isBuiltin = true,
            category = "编程开发",
        ),
    )
}
