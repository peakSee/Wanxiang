# Known Issues

This file tracks known issues and environment notes for the WanXiang Android project.

## 1. Offline build depends on a warm Gradle dependency cache

- The Phase 0 dependency set uses Hilt `2.50`, Room `2.6.1`, and KSP `1.9.22-1.0.17`.
- Room `2.6.1` pulls transitive processor dependencies:
  - `com.google.devtools.ksp:symbol-processing-api:1.9.0-1.0.13`
  - `org.jetbrains.kotlin:kotlin-stdlib:1.8.22`
  - `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22`
- On a fresh machine without those artifacts cached, the first offline `assembleDebug` will fail with
  `No cached version of ... available for offline mode`.
- On the current development machine the artifacts have now been downloaded and cached, so
  `assembleDebug --offline` succeeds.
- If the build is run on another machine with network access, remove `--offline` once to warm the cache,
  then offline builds will work afterward.

## 2. Room migration history

  tool version/metadata fields;
  normal upgrades must not fall back to destructive migration.
- If the schema changes again, add a forward migration and a JVM/instrumented migration test before changing
  the database version. Never reintroduce `fallbackToDestructiveMigration()` for a released build.

## 3. Dependency stack is intentionally old but stable for Phase 0

- Kotlin `1.9.22`, AGP `8.2.2`, Gradle `8.5`, KSP `1.9.22-1.0.17`, Hilt `2.50`, Room `2.6.1`.
- These versions are sufficient for the Phase 0 skeleton and match the original project plan constraints.
- A future modernization pass may align with the newer cached toolchain (Kotlin 2.2.x / AGP 8.7.x) if the
  project later requires it, but that is out of scope for Phase 0.

## 4. Phase 1 runtime payloads are downloaded on first initialization

- Linux rootfs 不内置到 APK；首次引导通过 `proot-distro 5.8.0` OCI Registry 流程拉取用户选择的 ARM64 镜像。
- 安装器选择 `linux/arm64` manifest，逐层校验 Registry 提供的 SHA-256 digest，并按 OCI whiteout 语义合并到 staging 目录后原子激活。
- layer 缓存位于应用私有目录，支持 gzip 与 zstd OCI layer；不存在旧版 release tarball 兼容入口。
- 如果设备不是 ARM64，初始化会在架构检测阶段停止；PRoot 导入后还会执行 `--version` 启动校验。
- PRoot native 组件仍需固定 SHA-256；RootFS 完整性由 OCI manifest 中每一层的 digest 保证。

## 5. Beta 前仍需补齐的能力

- 交互终端当前使用 Debian `script` + `ProcessBuilder` 创建 PTY；通过记录 `/dev/pts/*` 从属设备并执行独立 `stty -F` 命令，UTF-8、Ctrl+C、动态 Resize、ANSI SGR/光标/清屏和滚动缓冲已接通，不会把 `stty` 注入 Codex/OpenClaw/Hermes 的 stdin。真正的 JNI `forkpty` backend 仍可作为后续替换实现，当前需要在 ARM64 设备上验证更多复杂 TUI。
- 真 PTY（JNI forkpty）后端的全部代码已就绪（`app/src/main/cpp/pty_native.c` + `NativePty.kt`/`NativePtySession.kt`，Termux 同款 setsid/控制终端语义）；当前机器未装 NDK，`build.gradle.kts` 的 `externalNativeBuild` 配置已临时注释。装上 NDK 后恢复配置即可启用，未启用时自动回退到 `script` 后端，功能不受影响。
- Codex、OpenClaw、Hermes 的 Adapter 已隔离安装、验证、更新和卸载路径；官方脚本会先通过 HTTPS 下载到 Linux `/tmp`、设置为仅所有者可执行，再执行并由 `trap` 清理。脚本内容仍属于易变化的上游代码，正式发布前仍需按各自官方文档固定版本、复核许可，并在 ARM64 设备上逐个执行兼容性验收。
- 工具程序统一安装到 `/opt/wanxiang/tools/{toolId}`，稳定命令入口位于 `/opt/wanxiang/bin`；Codex、OpenClaw、Hermes 的配置数据分别通过 `CODEX_HOME`、`OPENCLAW_HOME` 或 Hermes `--hermes-home` 指向 `/opt/wanxiang/data/{toolId}`。卸载默认保留数据，用户明确勾选后才删除对应工具数据。
- `RemoteScriptRunner` 禁止脚本下载跨主机重定向，并支持为官方脚本配置固定 SHA-256、在执行前通过 `sha256sum -c` 校验；当前三个上游安装地址仍没有项目可长期信任的固定脚本版本哈希，因此正式 Beta 发布前必须把 URL 改为版本化地址并填入哈希，或改用固定 release archive。
- 前台服务已声明并请求 Android 13+ 通知权限；若用户拒绝通知，后台 Agent 仍会受系统限制，建议从系统设置重新允许通知后使用持续运行服务。
- 远程工具 Registry 已支持 HTTPS + Ed25519 签名校验、服务端口元数据和动态工具卡片；当前没有预置项目自己的签名 Registry 地址，默认继续使用 APK 内清单。
- RuntimeManager 对 Python/Git 等继续使用 Debian apt；Node 会先尝试 apt，版本不满足工具约束时切换到带固定 SHA-256 的官方 ARM64 binary，并安装到持久化 `/opt/wanxiang`。正式发布前仍需在目标 ARM64 环境验证动态库兼容性。
- 共享 Runtime 已提供引用计数和显式 `apt purge` 清理入口；默认不会随工具卸载自动删除，仍需产品发布前补充更细的磁盘占用估算。
- 开发者页的存储统计现在按 RootFS、Runtime 基础目录、工具程序、工具数据、工作区和缓存分桶，避免把同一目录重复计入；统计仍是文件大小估算，不等同于文件系统实际占用的 block 数。
- Tool Registry 已增加 `schemaVersion`、ARM64/HTTPS/服务路径校验、启停开关和版本元数据；主页更新现在走独立 UPDATE 事务，失败或取消不会释放旧版本的共享 Runtime 引用。
- 安装事务会在 `/opt/wanxiang/.transactions` 保存程序目录快照；应用启动时会根据 Room 中仍为 RUNNING 的任务恢复更新前目录或清理半安装目录，完成后清理快照。工具配置数据不进入快照。
- Manifest 依赖支持受控版本约束（例如 `node>=22.22.3`、`python>=3.11`），统一解析为 RuntimeRequirement；具体 Adapter 仍会在官方安装步骤前做一次显式依赖获取和验证。
- Provider Base URL 已按 URI 解析：HTTPS 允许远端 API，HTTP 仅允许精确的 `localhost`、`127.0.0.1` 和 `::1` 回环地址，不接受 `localhost.evil` 这类前缀伪装。
- 下载器会拒绝非 HTTPS、错误的断点 `Content-Range` 和超过 1 GiB 的响应，并使用临时文件加原子提交；各 Runtime 资产仍应在升级时同步更新固定 SHA-256。
- 终端会识别带 OAuth/登录/设备授权提示的 HTTPS URL，显示用户主动打开浏览器的按钮；普通链接不会自动打开，设备码登录仍直接在终端完成。
- RootFS 安装标记会记录在线来源版本；开发者页的 RootFS 更新会先 staging、保留 `/root` 与 `/opt/wanxiang`、执行健康检查并在失败时恢复旧版本。由于第三方工具可能把文件写入发行版的其他系统目录，正式 Beta 仍应在 ARM64 设备上验证更多工具组合的迁移兼容性。
- RootFS 更新使用持久化 `rootfs.update.pending` 与 `rootfs.previous` 两阶段提交；如果应用在激活新 RootFS 后被终止，下次初始化会先恢复旧版本，再重新开始更新流程。
- 工具配置和 `/opt/wanxiang` 已从 rootfs 中迁移到 App 私有 bind mount；旧版本 rootfs 会在首次配置时迁移一次，后续工具更新不会随 rootfs 替换丢失。
- PRoot 下 dpkg 解包硬链接与 I/O 防御：
  - 自动配置 `/etc/dpkg/dpkg.cfg.d/01_wanxiang_nodoc` 排除 doc/man/locale 文档，节省 >50% I/O，并消除文档类硬链接解包隐患；
  - 自动配置 `/etc/apt/apt.conf.d/99wanxiang-apt-config` 增强重试与非交互配置；
  - 自动在 `/usr/local/sbin/wanxiang-fix-perl` 预置 Perl 幽灵硬链接（`perlthanks` hardlink）自愈重包工具，并在 `RuntimeManager` 捕获异常时自动触发自愈。
  - 自动配置 Git 全局 `safe.directory = *`，彻底解决 PRoot 虚拟 UID 0 与宿主 workspace 权限冲突导致的 dubious ownership 错误。

## 7. Android 核心环境：装配期一次性部署（2026-08 重构）

- 【Android & 移动全栈套件 · android-core】现在在**插件装配阶段**一次性完成全部环境部署，`build_android.sh` 不再承担任何下载/自愈职责（旧版每次构建都重复 licenses 写入、init.gradle 生成、Gradle 下载自愈，浪费大量时间）。
- 装配内容（`setup_android_core.sh`，幂等可重入，断点续装）：
  - 真实 Android 34 平台包：`platform-34-ext7_r03.zip`（android.jar + core-for-system-modules.jar，~60MB）从腾讯云镜像 `mirrors.cloud.tencent.com/AndroidSDK/` 下载（官方源兜底），SHA-1 固定校验，安装到 `/opt/android-sdk/platforms/android-34/`；
  - Gradle 8.14.2 官方独立包：腾讯云 → 华为云双镜像，SHA-256 固定校验（`7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999`）；
  - 全套 SDK licenses 预接受 + Build Tools 35.0.0 的官方 Java 工具/元数据；宿主原生程序使用固定 SHA-256 的 lzhiyong ARM64 35.0.2 aapt/aapt2/aidl/zipalign；
  - `lzhiyong/termux-ndk` r29（NDK `29.0.14206865`）Linux AArch64 静态工具链，固定 SHA-256 后安装到 `/opt/wanxiang/toolchains/android/ndk/artifacts/<digest>/ndk`；AGP 通过本地 Gradle init policy 的 `android.ndkPath` 使用该不可变路径；
  - 全局阿里云 Maven 镜像 `/root/.gradle/init.gradle`（beforeSettings 注入，覆盖所有项目）；
  - 持久化环境变量 `/etc/profile.d/wanxiang-android.sh`（JAVA_HOME/ANDROID_HOME/GRADLE_HOME/PATH），`/root/.bashrc` 自动 source；
  - JAVA_HOME 推导、java/gradle 全局软链、cacerts 注入。
  - GitHub 大文件下载（lzhiyong ARM64 工具 / termux-ndk / Flutter / JADX）统一走共享镜像库 `wanxiang-mirror-lib.sh`：内置 github.akams.cn 全量节点（78 个），安装前并行测速（16 路、每节点 256KB 探测）自动选最快，全部失败回退官方 GitHub 直连；Gradle / SDK 平台包仍优先走腾讯/华为国内镜像。
- `build_android.sh` 重构为纯执行器：加载 profile 环境 → 前置校验（缺 java.jar/gradle 快速失败并指引插件中心）→ 写 local.properties → 多级 Gradle 调度。`android init` 脚手架直接预置阿里云镜像（替代旧版构建期 sed 自愈注入）。
- ToolManager 批量装配对重型下载脚本（setup_android_core.sh / setup_flutter.sh / setup_jadx.sh / setup_pnpm.sh）超时从 180s 放宽到 20 分钟（与 GenericRecipeInstaller 对齐）；apt 聚合安装整批失败时降级 `--ignore-missing`，避免个别发行版缺包（如 apksigner）导致全部装不上。
- Android 核心套件把 AAPT2 和 NDK 安装到带 SHA-256 的不可变制品目录，正式构建只注入规范制品路径；`/opt/wanxiang/android-sdk-tools/aapt2` 与 `/opt/android-sdk/ndk/<version>` 仅是旧项目兼容视图。`/root/.gradle/gradle.properties` 固定 `android.aapt2FromMavenOverride` 并设置 `android.builder.sdkDownload=false`，`/root/.gradle/init.d/wanxiang-android-ndk.gradle` 固定 `android.ndkPath`，阻止 AGP 在构建中补装官方 x86_64 主机工具。Android/Flutter 构建脚本会清理遗留的 `local.properties` `ndk.dir`，但不再写回，以保证 AGP 只看到 `ndkPath` 这一种 NDK locator。内置构建与工具链装配还通过 `/opt/wanxiang/locks/android-toolchain.lock` 共享/排他互斥。
- 注意：插件中心的组件就绪探针已升级为校验真实 SDK（java + aapt + android.jar + gradle launcher jar），老沙箱升级 App 后探针会显示未就绪，重新装配一次即可（幂等，已下载组件会跳过）。

---

# 6. 近期实现状态（截至 2026-08）

## 模块化
- 已按计划 §4 拆为多模块：`core/{common,model,database,datastore,network,security}`、`feature/{theme,components,home,chat,terminal,workspace,settings,developer,navigation}`、`runtime`、`tools`（core/tools + runtime/tools 安装器）、`harness`、`app`；包名统一为 `top.wanxiang.app.*`。

## 真 PTY
- NDK（30.0）已安装，JNI forkpty 后端已启用（`NativePtySession` + `pty_native.c`），自动回退 `script` 后端。终端多会话（`TerminalSessionManager`）已接入，会话元数据存 `terminal_sessions` 表，进程重启后重建同名会话壳（只存元数据，不存输出）。
- 会话选择从占位标签行改为标题栏按钮 + 会话列表弹窗（切换/关闭/新建/重命名）。

## Agent Harness
- 原生 Agent（类 pi）：read/write/edit/base 四工具 + 工具循环，LLM 走 `harness_models`（Room）激活模型，回退 App Provider 配置。
- 流式输出：`ChatApi.chatStream` 用 OkHttp okio 逐行解析 SSE，内容增量实时更新；tool_calls arguments 按 index 分片累积；解析用 `as? JsonObject/JsonArray/JsonPrimitive` 安全转换，避免 JsonNull 抛错。
- 聊天内模型管理（添加/切换/删除模型）与会话重命名已加。
- 会话可关联工作区，Agent 的 base 工具默认在该工作区目录执行。

## 已知待办
- 终端触摸滚动 / IME 中文输入未完善：透明输入层在键盘输入时会挡 LazyColumn 触摸滚动；中文输入法组合尚未完全直通。
- Codex / OpenClaw / Hermes 三个真实工具的整机安装、启动、健康检查验收**尚未完整跑通**（仅修了外围：apt 自愈、30s→20min 超时、git HTTP/1.1、安装器失败重试）。
- `feature` 各屏缺单元测试（Compose 测试需 Robolectric，尚未接入）。
- 设备当前离线，最新 APK（多会话/流式/模型管理/模块化）尚未真机安装验证。

## 依赖/构建
- Kotlin 2.4.10 / AGP 9.3.1 / Gradle 9.7 / Hilt 2.60 / Room 2.8.4 / Compose BOM 2026.08。Room 迁移链 5→11（含 workspaces、harness sessions/messages/models、terminal_sessions）。测试分布在 `core/*`、`harness`、`feature:terminal` 与 `app`。
