<p align="center">
  <img src="app/src/main/res/drawable/wanxiang_logo.webp" width="96" alt="万象 Logo" />
</p>

<h1 align="center">万象 · WanXiang</h1>

<p align="center"><strong>掌中万象，造物随心。</strong></p>

<p align="center">
  Android 无 Root Linux Runtime · 智能体引擎 (Agent Harness) · 原生 PTY 终端 · 移动开发工作区 · 无线 ADB 诊断
</p>

<p align="center">
  <code>v0.13.35</code> · <code>Android 10+ (SDK 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin · Jetpack Compose</code>
</p>

<p align="center">
  🐧 <strong>官方 QQ 交流群：905971993</strong>
</p>

<p align="center">
  安装包下载 · 全量离线插件包 · 更新公告 · 反馈互助 —— 群内获取最新版 APK
</p>

---

## 🌌 何为万象

《道德经》云：“道生一，一生二，二生三，三生万物。”

**万象**取意于此：以一句自然语言为“道”，在 Android 严格受限的应用沙盒与权限边界内，衍生出真实进程、物理文件、经过验证的代码与可交付的构建产物——万物皆可由此而生。

它不是给 LLM 聊天简单套壳，也不是玩具式的终端模拟器。它让大模型、MCP 工具、Linux 系统、原生 PTY 终端与项目工作区共享同构的执行上下文与因果链——使一句自然语言意图，能够落成物理文件、真实进程、经过验证的代码与可交付的 Android / Flutter 构建产物。

> 掌中方寸，森罗万象。

```text
人的意图 (Intent) ─→ 计划拆解 (TaskPlan) ─→ 工具 / MCP / Linux / 浏览器 ─→ 结果验证 (Verification)
                             ↑                                                  ↓
                             └────────────────── 未完成则继续推进 ──────────────┘
```

---

## ⚡ 核心能力全景

| 领域 | 核心能力与技术实现 |
| :--- | :--- |
| **Linux 沙箱** | 基于 **PRoot** 用户态运行 10 种 ARM64 发行版（Ubuntu 24.04 / Debian 12 / Kali / Arch / Fedora / Alpine 等）；通过 OCI Registry 拉取并校验 RootFS；支持多系统无缝切换、两阶段提交回滚、持久化目录绑定与 Android 共享存储挂载；国内镜像源自动探测与自愈。 |
| **Agent 智能体引擎** | 深度兼容 **OpenAI 兼容接口** 与 **Anthropic Messages API**；支持流式 SSE、思考链（`reasoning_content` / DeepSeek / Claude）、任务拆解进度卡（TaskPlanCard）、结构化分级工具审批与多厂商中转站配置。 |
| **对话回退与快照** | 基于 **SessionFork 会话树派生** 实现一键「撤回到此轮」（Rewind）；配套每轮对话前的 **Checkpoints 文件快照安全网** 并磁盘持久化，重大代码重构与指令随时可安全回滚。 |
| **语义记忆与子智能体** | Agent 记忆语义模型（冲突消解、版本 revision、置顶 pinned、新鲜度 recency）；支持多子智能体协同调度（文件写租约波式调度、结构化 facts pack 回传与超限分页落盘）。 |
| **Git 可视化工作台** | 提交拓扑图（泳道贝塞尔连线 / 分支标签 / 合并节点）、暂存与回退、分支 / 标签 / 远端管理、提交详情与行级 Diff、凭据加密托管与署名配置——手机上完整的 Git 体验。 |
| **原生 PTY 终端** | JNI `openpty`/`forkpty` 底层桥接（`libwanxiang_pty.so`），提供真实 Linux 进程生命周期、控制终端、ANSI/VT100 增量解析、触觉反馈按键条与多会话后台持久化；原生不可用时自动回退至 `script` PTY。 |
| **内置浏览器与 CDP 调试** | 内置 WebView 多 Tab 池与 In-process 浏览器 MCP 服务；支持页面脚本**注入式 Hook 引擎**、**CDP 断点**与 **Worker 级 Fetch 拦截**，提供可视化的网络请求时间线面板与调试状态横幅。 |
| **无线 ADB 与日志工作台** | 独立常驻一级工作台入口；支持**通知栏免切屏输入配对码**秒级配对无线 ADB、mDNS 局域网调试服务自动发现、PRoot / Android 双端日志实时抓取、设备状态一键体检与系统 Intent 诊断。 |
| **移动工作区与构建** | 支持创建空项目、本地 ZIP 导入（防 Zip Slip 校验）与 **GitHub 仓库导入（带实时 clone 进度）**；提供代码浏览、可视化行级 Diff 比对、沙箱内 Gradle / Flutter 后台静默构建与 APK 签名安装。 |
| **工具生态与 MCP** | 离线插件包一键导入（Android / Flutter / 反编译三大开发环境）、内置/签名 Registry、Recipe 事务安装与依赖管理；集成 **Open-WebSearch** 联网搜索、CodeGraph 代码知识图谱，全面支持 Stdio / SSE / Streamable HTTP 协议 MCP 服务。 |
| **可视化工作流** | 将构建、诊断、逆向与 Git 操作编排成可观察、可暂停的 DAG 工作流；支持定时触发与 HUD 悬浮进度，一句指令即可驱动多步流水线。 |
| **端侧协作与全局助手** | **智枢全局桌面悬浮小窗**（支持跨应用前台协作）、局域网 WebChat、内置 FTP 文件传输服务、沙箱内 `llama.cpp` 本地 GGUF 离线运行，以及 FGS 唤醒锁与 Wi-Fi 锁后台保活。 |

---

## 🚀 快速上手

1. **获取安装包**：加入官方 QQ 群 **905971993**，置顶公告下载最新 Release APK（arm64-v8a，Android 10+，推荐 Android 12+）。
2. **就绪沙箱**：首次进入跟随「启程向导」选择 Linux 发行版（如 Ubuntu 24.04），联网完成 RootFS 初始化（国内镜像自动加速）。
3. **配置模型**：在设置中配置你的 API Key（支持 DeepSeek、Claude、OpenAI、SiliconFlow 等），或在沙箱内部署 `llama.cpp` 本地模型。
4. **开启工作区**：在工坊中创建新工程，或从 GitHub / 本地 ZIP 导入已有项目。
5. **意图驱动开发**：向智枢描述你的工程目标；智枢将自主规划步骤、调用 Linux/MCP 工具、读写代码、运行构建并验证结果。

> 💡 **提示**：RootFS 镜像不内置于 APK 包体中。首次初始化需要网络连接与存储空间；移动数据环境下建议在群内下载全量离线插件包；长任务后台构建建议开启前台服务与系统电池优化白名单。

---

## 📦 版本与更新

- **更新通道**：App 内「设置 → 关于 → 检查新版本」支持应用内直接更新（HTTPS 加密通道，公共网络下也可完整校验）。
- **更新公告**：App 内「万象公告」与 QQ 群公告同步推送，含每个版本的变更明细。
- **离线插件包**：Android / Flutter / 反编译三大开发环境的全量离线包在群文件发布，免在线安装、免特殊网络环境。

---

## ⚠️ 边界与已知限制

- **ABI 架构**：当前仅适配 `arm64-v8a` 架构；其他架构设备将在初始化阶段拦截并终止。
- **用户态沙箱**：PRoot 是基于 `ptrace` 的用户态系统调用拦截与路径重写机制，不是硬件虚拟化或完整 KVM 虚拟机，不提供 Root 特权或底层内核模块加载能力。
- **环境兼容性**：复杂 TUI（如部分全屏 curses 应用）、特定软键盘组合键及重型 C/C++ 交叉编译仍需依据具体 ARM64 设备性能与内存情况调优。
- **网络安全**：模型 API 及远程下载端点强制遵循安全传输协议；请妥善保管私有 API Key 与凭据。
- **许可说明**：本仓库是万象的**公开快照存档**，供交流与学习参考；当前版本的持续开发在私有仓库进行，App 以安装包形式分发。

---

## 📜 注脚

> 须弥纳于芥子，万象纳于掌中。

限制从未真正消失；但自由可以来自身处限制之中，仍有能力去构筑、去验证属于自己的世界。

欢迎加入官方 QQ 群 **905971993**，分享你的真机使用记录！
