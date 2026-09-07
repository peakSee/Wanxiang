---
name: wanxiang-custom-iteration
description: 万象（WanXiang）自定义迭代与 WanXiangDev 构建规范，引导 Agent 安全开发、提交 PR 及构建双包共存 APK。
---

# 万象（WanXiang）自定义迭代专属开发规范

本 Skill 用于引导 AI Agent 在万象无 Root Linux 沙盒中协助用户进行万象应用自身的迭代开发、测试、云端 CI 构建与开源社区 PR 交付。

---

## 1. 核心安全与隔离准则

1. **工作区隔离**：所有针对万象源码的修改必须且仅在隔离工作区 `~/custom_wanxiang` 中进行，严禁直接在运行宿主目录或系统级配置中修改。
2. **凭据安全**：
   - 引导用户使用 `gh auth login`（设备码流程）或 SSH Key 认证；
   - 严禁将 GitHub Token、私钥或任何敏感凭证打印在终端输出、会话消息或提交记录中。
3. **双包共存（Dual-Flavor）规范**：
   - 自定义迭代构建的 APK 命名必须为 `WanXiangDev`；
   - 包名必须重命名为 `top.wanxiang.app.dev`；
   - 默认存储与配置目录与正式版隔离，确保测试版与手机中正在运行的万象正式版完全共存、互不影响。

---

## 2. 迭代开发流程

1. **环境诊断**：
   - 检查 `git` 与 `gh` 安装状态；
   - 确认当前处于 `~/custom_wanxiang` 工作区中；
   - 检查当前 Git 分支状态与工作区 Clean 状态。
2. **架构规范约束**：
   - **UI 层**：严格使用 Jetpack Compose + Material3，遵循 `top.wanxiang.app.ui` 命名规范；
   - **状态管理**：使用 Hilt 注入 ViewModel，以 `StateFlow` + `collectAsStateWithLifecycle` 暴露状态；
   - **Linux 沙盒**：PRoot 系统调用与交互遵循 `runtime` 模块标准契约。
3. **本地冒烟测试**：
   - 在提交前运行 `./gradlew testDebugUnitTest` 进行基础单元测试；
   - 验证无误后创建特性分支 `feature/xxx` 进行 commit。

---

## 3. 构建 WanXiangDev APK（首选 GitHub Actions）

由于手机端架构（ARM64）与标准 Android 编译工具链（x86_64）的差异，优先使用 GitHub Actions 云端 CI 构建：

1. 确保用户的 Fork 仓库已包含 `.github/workflows/wanxiangdev-build.yml`；
2. 在终端触发工作流并实时监控状态：
   ```bash
   gh workflow run wanxiangdev-build.yml --ref <feature-branch>
   gh run watch <run-id> --exit-status
   ```
3. 编译成功后下载生成的 APK 产物：
   ```bash
   gh run download <run-id> -n wanxiangdev-apk -D /storage/emulated/0/Download/
   ```
4. 校验生成的 APK 包名（`top.wanxiang.app.dev`）与签名无误后提示用户安装体验。

---

## 4. 开源 PR 提交规范

经过本地真机安装验证满意后，协助用户生成标准 PR 模板并提交到万象官方仓库：
- **目标仓库**：`peakSee/Wanxiang:main`
- **源分支**：`user-fork:feature/xxx`
- **PR 模板包含**：概述（Summary）、改动点（Changes）、测试结果（Testing）、截图/录屏（Screenshots）、潜在风险（Risks）。
