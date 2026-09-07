# 🏗️ 万象 (WanXiang) — 系统架构与模块拓扑 (Architecture & Modules)

---

## 1. 🚀 项目定位与技术栈摘要 (Tech Stack Snapshot)

- **核心定位**：在 **Android 无 Root 用户态** 下，基于 **PRoot** 运行完整的 **Linux 沙箱**，并深度集成 **AI Agent Harness 智能体引擎**、**原生 PTY 终端**、**工作区文件管理** 与 **Material 3 Expressive UI**。
- **Android / UI 层**：
  - **主工程构建链**：Gradle 9.7.0 / AGP 9.3.1 / Kotlin 2.4.10 / compileSdk & targetSdk 37 / NDK 30.0.15729638
  - **语言**：Kotlin（100% Kotlin + Jetpack Compose）
  - **UI 风格**：Material 3 Expressive (M3 动态表面、Haptic 触觉反馈、双栏自适应 Dual-Pane)
  - **依赖注入**：Hilt / Dagger
  - **状态与异步**：Kotlin Coroutines, StateFlow, SharedFlow
  - **本地存储**：Jetpack DataStore (Preferences), Room Database (SQLite)
- **底层运行时 (Runtime & Native)**：
  - **C/C++ JNI**：`app/src/main/cpp/pty.c` (openpty, fork, exec, ioctl 终端 PTY 桥接，编译为 `libwanxiang_pty.so`, `arm64-v8a`)
  - **Linux 容器**：内置 PRoot 引擎，启动 Debian rootfs
  - **宿主存储映射**：PRoot `-b <host>:<guest>` 原生绑定机制（Download, Documents, /sdcard）
- **AI 智能体引擎 (Agent Harness)**：
  - **协议兼容**：OpenAI Chat Completion API 标准（支持流式 SSE、`reasoning_content` 思考过程、函数工具调用 `tool_calls`）
  - **内置工具**：`read` / `write` / `edit` / `base` / `process`，并可扩展 MCP 与子智能体工具
  - **命令生命周期**：`base` 用于有界前台命令，默认超时由用户配置；`process` 通过 Runtime 进程注册表托管跨工具调用持续运行的命令
  - **本地端侧模型**：沙箱内原生支持 `llama.cpp` 与 `Ollama`，无需 API Key
- **沙箱 Android 开发套件（不要与主工程构建链混淆）**：Gradle 8.14.2 / AGP 8.11.1 / Kotlin 2.2.20 / compileSdk & targetSdk 34 / NDK 29.0.14206865，仅面向 ARM64 沙箱内的 Android/Flutter APK 构建。

---

## 2. 🗺️ 模块拓扑与职责 (Module Topology)

```text
LinuxAIRuntime/
├── app/                  # 应用壳工程：MainActivity、Hilt 初始化、JNI C 代码、前台保活 Service
├── core/
│   ├── model/           # 纯 Kotlin 数据模型 (不含 Android SDK 依赖)
│   ├── common/          # 协程调度器、日志、通用工具类
│   ├── database/        # Room 数据库：Harness 对话记录、会话、工具执行历史
│   ├── datastore/       # Jetpack DataStore：用户偏好、存储挂载、激活模型、Agent 命令超时
│   ├── network/         # OkHttp 网络客户端、SSE 流式解析器、超时策略
│   └── security/        # API Key 本地安全加解密
├── runtime/              # Linux 沙箱与 PRoot 核心：命令/进程注册、PTY、工作区导入导出与构建
├── project-template/     # 标准化项目模板：manifest、变量表单协议、导入导出、物化与内置模板资产
├── harness/              # Agent 智能体核心：Agent 循环、流式推理、内置工具/审批、MCP
├── tools/                # 工具生态中心：Registry、本地插件、安装事务、批量组件安装、Provider 安全
└── feature/              # Compose UI 业务特性层
    ├── components/      # 万象 M3 Expressive 设计规范、通用组件 (RuntimeCard, TopBar, Icons)
    ├── theme/           # Material 3 调色板、字体、主题配置
    ├── home/            # 首页运行仪表盘：Linux 系统状态、内存/磁盘/进程实时监控
    ├── chat/            # 智枢 Agent 对话界面、TaskPlanCard 任务拆解卡片、宽屏双栏布局
    ├── terminal/        # 终端 Compose UI 与触觉按键条；会话/VT100 状态机位于 runtime
    ├── workspace/       # 工作区管理器：创建/ZIP/GitHub 导入、导出、代码浏览、后台构建
    ├── settings/        # 设置中心：模型档案、Agent 超时、工具/本地插件、存储挂载、外观与诊断
    ├── developer/       # 开发者原生沙箱与诊断面板
    ├── onboarding/      # 首次启动引导与 RootFS 解压就绪流程
    ├── custom_iteration/ # 自定义迭代（AI 改应用）入口
    └── navigation/      # 顶层导航路由与 NavHost 调度
```

---

## 3. 🔗 跨模块交互原则 (Cross-Module Rules)

1. **单向依赖流**：
   - 依赖方向严格保持 `feature/*` → `core/*` / `runtime` / `harness` / `tools`。
   - 业务 feature 之间**严禁**互相横向依赖，仅可依赖共享 UI `feature/components` 与 `feature/theme`，顶层由 `feature/navigation` 负责页面装配与路由调度。
2. **持久化隔离**：
   - feature / runtime / harness 只能依赖 `core/database/PersistenceRepositories.kt` 暴露的 Repository 接口，严禁直接注入或操作 Room DAO。
   - 偏好配置统一通过 `core/datastore/PreferenceFacades.kt` 提供的领域分面接口读取或写入。
3. **纯模型层隔离**：
   - `:core:model` 必须保持 Pure Kotlin，**严禁**引入 `android.*`、`androidx.*` 或 Compose 依赖。
   - `architectureCheck` 任务已接入 `app:preBuild` 验证阶段，严密阻断模型层平台化与违规依赖。
4. **命令执行边界**：
   - 短时、有明确退出状态的前台命令调用 `LinuxRuntime.execute()` / Harness `base`。
   - 跨调用持续运行的服务或长任务调用 `LinuxRuntime.startBackground()` / Harness `process`，统一由 `ProcessRegistry` 托管 PID、生命周期与环形日志缓冲。
5. **工作区操作边界**：
   - 项目生命周期与物理目录交互统一通过 `WorkspaceManager` 与 `WorkspaceFileService`。
   - ZIP 导入必须执行路径穿越防范（Zip Slip Check）、文件大小上限与条目数校验；Git clone 必须在沙箱内隔离执行。
6. **构建调度与保活边界**：
   - 工作区构建由单例 `WorkspaceBuildTaskCoordinator` 全局持有，UI 页面旋转或重建不中断构建任务；底层构建执行与环境预检由 `WorkspaceBuildRunner` 承载。
