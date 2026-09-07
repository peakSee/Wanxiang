# 🧭 万象 (WanXiang / LinuxAIRuntime) — AI 导航入口

> 本文件为导航入口，默认载入。全部细节（技术栈 / 模块拓扑 / 调用链路 / 文件索引 / 铁律 / 命令）在 [`docs/AI_NAVIGATION.md`](docs/AI_NAVIGATION.md)，按需读取。

## 是什么

Android 无 Root 下用 PRoot 跑 Linux 多发行版沙箱 + AI Agent Harness + 原生 PTY 终端。Kotlin 2.4 / Compose / Hilt / Room / Navigation3，仅 `arm64-v8a`。

## 模块

`app`(壳/装配/JNI) · `core`(model·common·database·datastore·network·security) · `runtime`(PRoot/RootFS/PTY/工作区) · `tools`(Registry/安装事务/Provider安全) · `harness`(Agent循环/MCP/子智能体) · `feature`(components·theme·home·chat·terminal·workspace·settings·developer·onboarding·custom\_iteration·navigation)

## ⚠️ 架构与 UI 铁律（写代码 / 加 UI 前必守，违反即回退重写）

1. **模块与依赖边界**：依赖只允许 `feature/* → core/runtime/harness/tools`，feature 之间严禁横向依赖（页面装配只在 `feature/navigation`）；`:core:model` 必须 Pure Kotlin（严禁 `android.*`/`androidx.*`/Compose）；业务层只依赖 `core/database/PersistenceRepositories.kt` 的 Repository 接口（严禁直连 Room DAO）；偏好读写统一走 `core/datastore/PreferenceFacades.kt`。
2. **UI 设计系统**（防加乱布局）：容器一律 `RuntimeCard`；按钮/弹窗/开关/进度/顶栏/底栏一律 `Runtime*` 组件（严禁散落原生 Material3 Card/Button/AlertDialog）；含输入框或列表的弹窗 text 区必须 `fillMaxWidth().verticalScroll(rememberScrollState())`（严禁固定高宽/硬编码 offset）；消息气泡 `widthIn(max=560.dp)`；路径/标签等单行文本 `maxLines=1 + TextOverflow.Ellipsis`；触摸目标 ≥48dp；`RuntimeCard` 内嵌内容用 `Surface`（严禁嵌套 Card）。
3. **命令执行边界**：短任务用 `base`；长任务/常驻必须用 `process(action="start")`（严禁 `nohup`/`setsid`/`&`），process 外部 ID 加 `agent-process:` 命名空间。
4. **移动端构建**：NDK 只由 init policy 的 `android.ndkPath` 注入，不得在项目里再写 `ndk.dir`/`ndkPath`；沙箱 Gradle 固定 `daemon=false`、`workers.max=2`、`Xmx=1024m`。
5. **工作区安全**：ZIP 导入必须 `WorkspaceManager.importProjectArchive()`（防 Zip Slip）；导入类型写入 `.wanxiang-project.properties`，不得靠扩展名反猜。
6. **包名与品牌**：包名 `top.wanxiang.app`（dev 版 `top.wanxiang.app.dev`），应用名「万象 / WanXiang」，日志/目录统一 WanXiang 标识。
7. **改动方式**：优先 `edit` 局部精准修改（oldText 精确唯一）；遵循现有命名与分层，不整文件重写、不引入不必要依赖；代码注释写「为什么/意图」。

## 动手前先读

| 要做什么                  | 推荐阅读文档                                                                |
| :-------------------- | :-------------------------------------------------------------------- |
| **快速把握全局**            | [`docs/AI_NAVIGATION.md`](docs/AI_NAVIGATION.md)（语义导航总览）              |
| **写代码 / 改模块 / 理拓扑**   | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)（架构与模块拓扑）               |
| **追踪数据流 / 调用链路**      | [`docs/EXECUTION_TRACES.md`](docs/EXECUTION_TRACES.md)（核心执行时序）        |
| **定位某个类 / 寻找文件**      | [`docs/FILE_INDEX.md`](docs/FILE_INDEX.md)（关键文件索引速查）                  |
| **UI/UX 设计系统与架构铁律**   | [`docs/ARCHITECTURE_RULES.md`](docs/ARCHITECTURE_RULES.md)（设计规范与避坑指南） |
| **构建 / 测试 / 打包 / 调试** | [`docs/COMMANDS.md`](docs/COMMANDS.md)（常用命令速查）                        |

