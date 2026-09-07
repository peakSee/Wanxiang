# 第三方组件与许可证清单

本文档记录当前工程直接使用、在线下载或随 APK 携带的第三方组件。它是分发前的审查清单，不构成法律意见；发布前应重新核对每个版本的上游许可证、NOTICE 和再分发条件。

| 组件 | 用途 | 来源/版本 | 许可证与分发备注 |
|---|---|---|---|
| Debian / Ubuntu ARM64 rootfs | Android Linux 用户态系统 | 使用 `proot-distro 5.8.0` OCI 机制从 Docker/OCI Registry 拉取 | 各发行版及其中的软件包分别遵循各自许可证；不内置 APK，发布前需要提供对应版权与许可证获取地址 |
| Termux PRoot | Android ARM64 PRoot 启动器 | Termux 主仓库二进制包，版本和 SHA-256 见 `ProotInstaller` | 必须随对应二进制包核对许可证、NOTICE 和源码获取地址；APK 仅携带当前 ARM64 构建 |
| `libandroid-shmem.so` / `libtalloc.so` | PRoot 动态库依赖 | 与 PRoot 构建匹配的 Termux 包 | 不能只因为文件是 `.so` 就假定可自由再分发；发布前核对上游包的许可证文本 |
| `zstd-jni` | Android ARM64 `.tar.zst` 解压 | Maven Central，`1.5.5-4` AAR | 按上游 zstd-jni 的许可证和 NOTICE 分发 |
| RTK | Agent 安全前台命令的输出精简 | `rtk-ai/rtk` `v0.47.0`，随 APK 携带 `aarch64-unknown-linux-gnu` 可执行文件；归档 SHA-256 `960ceb5f1f5f0b0939b32b5b1d41dec6d9a7113137b0703c68dca0d169a260fc` | Apache License 2.0；完整许可证随 APK 置于 `assets/licenses/rtk-LICENSE`。仅在 Linux/glibc ARM64 沙箱中使用，无法运行或不支持的命令会回退原始命令 |
| Node.js ARM64 Runtime | OpenClaw 需要的 Node 22 fallback | Node.js 官方 `v22.22.3` Linux ARM64 tar.xz，SHA-256 固定在 `RuntimeBinaryInstaller` | Node.js 官方许可证/NOTICE；在线下载，不内置 APK；发布前保留对应版权和源码获取信息 |
| lzhiyong Android SDK Tools | ARM64 `aapt`、`aapt2`、`aidl`、`zipalign` | `35.0.2` 静态 AArch64 归档，URL 与 SHA-256 固定在 `setup_android_core.sh` | 在线下载、不内置 APK；发布前核对上游 Apache/AOSP LICENSE、NOTICE 与再分发要求 |
| lzhiyong/termux-ndk | 沙箱内 Android/Flutter 项目的 Linux AArch64 NDK 主机工具链 | r29 / NDK `29.0.14206865`，`android-ndk-r29-aarch64.tar.xz`，SHA-256 固定在 `setup_termux_ndk.sh` | 在线下载、不内置 APK；上游基于 AOSP LLVM/NDK，发布前随固定版本核对 LICENSE、NOTICE、源码与修改说明 |
| OkHttp | HTTPS 下载与 Registry 请求 | Maven Central，`4.12.0` | Apache License 2.0；发布包应包含 Apache 版权与 NOTICE 要求的文本 |
| AndroidX / Jetpack Compose Material 3 | Android UI 与系统集成 | Google Maven，版本见 `gradle/libs.versions.toml` | 按各 AndroidX/Jetpack 组件的许可证与 NOTICE 要求分发 |
| Ktor Client | HTTP 客户端（Agent 流式）与下载 | Maven Central，版本见 `gradle/libs.versions.toml` | Apache License 2.0 |
| Kotlin / Kotlinx Serialization / Coroutines | 应用基础运行库 | Maven Central，版本见 `gradle/libs.versions.toml` | 按各组件上游许可证与 NOTICE 要求分发 |
| Hilt / Room | 依赖注入与本地数据库 | Google Maven，版本见 `gradle/libs.versions.toml` | 按各组件上游许可证与 NOTICE 要求分发 |
| Agency Agents（软件研发精选目录） | 内置子智能体人格与专业工作流；按 9 个研发部门提供 136 个角色 | `msitarzewski/agency-agents`，固定提交 `3c9588880b7cafaec325a104899fd8bbe27e7d72` | MIT；原始 Markdown 与完整许可证随 APK 置于 `agency_agents/` assets，应用仅精选软件研发相关角色 |

## 工具安装器的上游来源

OpenAI Codex、OpenClaw 和 Hermes Agent 默认由用户在初始化 Linux 后主动点击安装，安装器通过 HTTPS 调用各项目官方安装入口；它们不是当前 APK 内置的二进制。对应上游链接和安装参数记录在：

- `docs/tools/codex.md`
- `docs/tools/openclaw.md`
- `docs/tools/hermes.md`

因此，工具的许可证、商标、服务条款和网络安装脚本变更应在每次发布前重新审查。应用不允许远程 Registry 直接下发任意 shell 命令；Registry 只提供数据，实际安装行为由 APK 内置且受 allow-list 约束的 Adapter 执行。

## 发布前检查

- 锁定并记录 APK 中每个 AAR/JAR/native 文件的准确版本、SHA-256、许可证和 NOTICE。
- 为可选发行版 rootfs、PRoot 和动态库保留源码/许可证获取地址及对应版本。
- 确认在线下载的 rootfs 和工具不会被误打包进 APK，也不会绕过上游许可证要求。
- 在商店或公开分发前补充 APK 内可访问的 “第三方许可证” 页面或完整文本包。
