# 移动端构建第三阶段：统一自检与命令兜底

## 目标

第三阶段把 Android/Flutter 构建入口统一到同一套环境策略。无论用户从工作区构建按钮、Agent，还是 PRoot 控制台开始构建，都应先确认当前工具链可用，并优先使用万象安装的 ARM64 资源。

## 标准入口

```sh
wanxiang-build doctor /workspace/project
wanxiang-build android /workspace/project assembleDebug
wanxiang-build flutter /workspace/project apk --debug
wanxiang-build analyze /workspace/project
wanxiang-build android /workspace/project assembleDebug --offline
```

`doctor` 至少检查：

- `/bin/sh` 与当前 RootFS 架构；
- JDK 17 与 `JAVA_HOME`；
- Gradle 或项目 Gradle Wrapper；
- Android SDK、Platform、Build-Tools 与 ARM64 AAPT2；
- Flutter/Dart（Flutter 项目）；
- 项目声明的 wrapper、AGP、compileSdk 和 NDK 版本是否超出本地能力；
- QEMU 开关和隔离 x86_64 兼容环境是否完整。

第四阶段新增 `analyze`、离线构建和 APK ABI 产物闸门，详见 [`MOBILE_BUILD_STAGE4.md`](MOBILE_BUILD_STAGE4.md)。

## 决策规则

1. 默认只使用 ARM64 工具链。
2. 项目版本不匹配时先输出差异，不静默下载另一套 SDK/JDK/NDK。
3. 需要调整项目时，由 Agent 的“移动端构建环境守卫”Skill 做最小版本对齐。
4. 只有遇到明确的 x86_64 ELF 主机工具错误时，才允许使用 `--qemu` 进入隔离 QEMU 会话。
5. QEMU 不改变 APK ABI；最终产物仍应只包含 ARM64。

## 控制台说明

普通 Shell 不可能在用户输入任意绝对路径命令时做到百分之百拦截。因此第三阶段通过 `/opt/wanxiang/bin` 包装器、统一 `wanxiang-build` 命令和 Agent Skill 覆盖常规 `gradle`、`gradlew`、`flutter` 构建路径。用户显式绕开包装器执行 SDK 内部绝对路径时，视为高级手动操作。
