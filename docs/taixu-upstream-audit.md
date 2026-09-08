# taixu v0.11 / v0.12 / v0.13 · 逐 commit 对 万象 的影响判决

> **背景**：taixu 是 万象 双胞胎（同源代码不同包名）。taixu 上游从 v0.10 fork 基线之后**又推了 3 个版本 27 个 commit**，我逐条 diff 看了他们改啥 + 对照 万象 现状判决。
>
> **判决**含义：
> - ✅ **已修** = 万象 里代码已经是修 后 版
> - 🔴 **中招** = 万象 里 有 同样 bug，需 要修
> - 🟡 **可能中招** = 有对应文件但不确定是否 已 修，需要具体 diff
> - ⚪ **不需要** = 万象 本来 就 走 的 另一条路

---

## 🔴 中招 · **优先 要 修**（5 个）

### R1. `20eb4e0 fix(harness): ensure global uniqueness for tool call entries`（v0.13）
**taixu 加**：新文件 `ToolCallIdNormalizer.kt` + 改 HarnessRuntimeRepository / AnthropicApi / ProviderClient / ResponsesApi / SubagentLaneRunner / HarnessLoop。核心：**同一 session 里 tool_call_id 必须全局唯一**（多 provider 各自返 的 id 会 撞 → Room UNIQUE 崩 or 上下文错乱）。

**万象 现状**：
- ❌ **无 ToolCallIdNormalizer.kt**
- `tool_call_id` 在 8 个 不同 文件 里 出现 22 次
- HarnessRuntimeDao 里 有  UNIQUE 约束，**中招 概率高**

**症状**：长对话 + 混合 provider（比如切了模型）时可能 遇到：`UNIQUE constraint failed: harness_entry.tool_call_id` 崩溃。或 tool result 挂错 call。

**修 代价**：小。抽 taixu 的 `ToolCallIdNormalizer` 直接搬，改 5-8 处 调用点。

---

### R2. `0599ae8 fix(harness): preserve context across compaction`（v0.13）
**taixu 修**：ContextWindowPolicy / HarnessLoop / ToolExecutor / CompactionManager / ApiContextAssembler / SessionTreeStore 六处协同，防止**上下文压缩 时 tool_call 与其 result 被 拆开** → 后续 API 请求缺配对报错。

**万象 现状**：
- 🟡 **有 所有 相关 文件** 但 未 打 这 补丁
- ApiContextAssembler 只 40 行 前 部，需 要 具体 diff 看 是否 有 rebuild 逻辑

**症状**：**长 会话 里 AI 会 说 "tool_use ids without tool_result blocks"**（Anthropic 特别挑这个）或者 OpenAI 报 类似。压缩 后 立刻 挂。

**修 代价**：中。要 摸 清楚 taixu 六处 联动。

---

### R3. `e79560d stabilize terminal IME send action`（v0.11）
**taixu 修**：TerminalScreen 里 IME 动作路由 + 发送按钮 稳定化。他们 commit 里带 一堆 `.tmp_unusedscan` 杂物，实际核心改动 22 文件里。

**万象 现状**：
- 🟡 万象 Terminal 相关代码 存在 但 没打 IME patch
- **今晚我 亲自 遇到**：adb 输入 URL 到 万象 里被 IME 篡改（`https` → `ittps`）。 万象 Terminal 屏 大概率 有 类似 问题

**症状**：用户 在 terminal 里 输入 URL 或 长命令 → 中文输入法 自动 篡改 字符。发送 按钮 有时 失灵。

**修 代价**：中。需 要 具体 拉 taixu 里 那 22 文件 的 净改动 看。

---

### R4. `43d292c feat(harness): 新增 harness 核心容错与前缀缓存机制`（v0.11）
**taixu 加**：ApprovalPolicyEngine 加 逻辑 / ContextWindowPolicy / HarnessLoop / ProviderResponseNormalizer (+66/-5) / RunMetrics / ApiContextAssembler (+74/-9)。**前缀缓存**（Anthropic KV cache 那 类）+ 响应 归一化 + 度量。

**万象 现状**：
- ✅ 各 文件 都 存在（ProviderResponseNormalizer.kt 我们 有）
- 🟡 但 是否 已 有 前缀 缓存 未知。我们 在 v0.11 之前 fork 的话 **一定 缺** 这块

**症状**：Anthropic 或 有 前缀 缓存 的 服务，重复 请求 命中 不 了 cache → 慢 + 贵。

**修 代价**：中偏大（100-200 行 逻辑）。

---

### R5. `81bea34 feat(harness): 双智能体 Planner/Executor 物理隔离`（v0.11）
**taixu 加**：**全新** 4 个 文件 `dual/DualAgentCoordinator.kt` + `dual/DualAgentModels.kt` + `dual/PlannerPromptBuilder.kt` + `dual/PlannerProtocolParser.kt`。让 planner 与 executor 用**独立模型**（比如 planner 用 Claude Opus，executor 用 便宜 DeepSeek）。

**万象 现状**：
- ✅ **我们 已 有 DualAgentCoordinator.kt**（今 晚 grep 到 过）
- 🟡 但**不 确定 是不是 物理隔离 版本**。需 对比 diff

**症状**：如果 我们 版本 只是 planner 复用 executor 的 模型，就 没 意义。要 看 dual/ 目录 有 几 个 文件 + PlannerPromptBuilder 有 没 有。

---

## 🟡 需要具体 diff 才能定（3 个）

### Y1. `5020df1 fix(runtime): resolve EACCES in tar stream extraction`（v0.13）
**taixu 加**：抽 `FileSystemOps` 接口 + `AndroidFileSystemOps` 实现。TarStreamExtractor 改成 `internal constructor` 便于测 + 加 195 行 单测 覆盖。

**万象 现状**：
- 🟡 我们 TarStreamExtractor 用 `runCatching { Os.chmod } .onFailure { logger.w }` → 已经 容错 不 crash。
- 但 taixu 加 抽象 是 让 单测 能跑（不是 修 bug）。**功能 上 我们 应该 没 这个 bug**，只是缺 单测。

**判决**：**优先级 低**（除非 想 补 单测）。

---

### Y2. `70c0ff2 fix(runtime): support distros without bash and fallback to posix shell`（v0.13）
**taixu 修**：LinuxRuntimeImpl / RootfsValidator / LinuxSession。之前 假定 有 bash，改成 fallback posix sh。

**万象 现状**：
- ✅ **我们 早 就 用 `GUEST_SHELL = "/bin/sh"`**！不需要 修
- 但 若 未来 引入 依赖 bash 的 特性（比如 process substitution），要 加

**判决**：**不需要 修**。

---

### Y3. `2c3a4b5 fix(harness): RTK 命令改写加机器可读输出护栏`（v0.11）
**taixu 加**：RtkCommandOptimizer 加 73 行 检测 —— **模型 输出 里 若 是 机器 可读 结构（JSON/YAML）** 就 保留 不 改写。避免 破坏 结构化 输出。

**万象 现状**：
- 🟡 我们 有 RtkCommandOptimizer.kt 但 不 确定 是 不是 修 前 版
- 需 要 具体 拉 diff 对 照

**判决**：**先 拉 diff 看看**（5 分钟）。

---

## ✅ 已修 / 不需要（1 个）

### O1. `068fa25 fix(theme): 澄明主题深色模式下前景色误用深色问题`（v0.11）
**taixu 修**：Chengming 深色 主题 4 组 色号 改亮。**万象 现状**：
- ✅ 我们 里 `ChengmingDarkPrimary = Color(0xFF4C75F2)` + `OnPrimary = Color(0xFFFFFFFF)` — **完全 就 是 taixu fix 后 的 值**！

**判决**：**已 有，不 需要**。

---

## 🎁 大特性（不 是 bug）

### F1. **完整 Workflow 引擎**（v0.13 · 32f52c0 + cf761a5 + 827bc1c + 250f9ba + 623cd87 + d2bf2f8 + 1c1d969 + ab49c46 + aede4e2 + 018989d + bd45c6f）
- **规模**：feature/workflow 模块（**2000 行 WorkflowEditorScreen.kt** + WorkflowCanvas2D + WorkflowViewModel + WorkflowScreen + WorkflowRunDetails + hud 3 文件）+ core/model/workflow 8 文件 + harness/workflow 8 文件 + HostActionNodeExecutor 27KB + BuiltinWorkflows 29KB
- **能力**：DAG 可视化编辑 + GUI Pilot（自动点 APK UI 元素）+ HUD 覆盖层 + `/wf` 聊天命令 + 主动 推荐 + 内置 工作流
- **万象 现状**：❌ **完全 没**

**代价 判断**：**约 5000 行 净代码 + 一堆 集成点**。做 的话 需要**独立 一整 天**。 值得 单独 排期，别 混在 bug fix 里。

---

### F2. `ContextUsageDialog` 上下文用量 详细 视图（v0.13 · 018989d）
- taixu 加 **366 行 ContextUsageDialog.kt** + 改 ContextUsageRing (-127 行 精简）。
- **万象 现状**：❌ 无 Dialog 只有 Ring。Ring 是 视觉小 圈 不 到 明细。

**代价**：小（1-2 小时 搬 移 植 + 集成）。**性价比 高**。

---

### F3. `e27338a 自定义 Anthropic 中转站 + 连通性检测`（v0.12）
- taixu 加 7 文件，agent_providers.json 加 9 行 + ModelEditorScreen +12 + SettingsViewModel +14 + AgentModelConnectionTester 检测。**给用户 一个 明确 的 "测 一下 能不能通" 按钮 + 支持 自定义 base URL**。

**万象 现状**：🟡 我们 有 AgentModelConnectionTester.kt 但 不 知 是不是 一样。

**代价**：小。

---

### F4. `90f90ff 通知栏 ADB 配对码免切屏`（v0.12）
- taixu 加 357 行 AdbNotificationManager + 105 行 Receiver。核心：**通知栏 里 直接 输入 6 位 配对码**，不用 切到 App。

**万象 现状**：✅ **我们 有 AdbNotificationManager.kt + AdbNotificationReceiver.kt**。 需 要 diff 看 是 不是 一样 完整。

---

### F5. `7526e16 Release 包体积压到 8.5MB · RTK 外置 离线 插件 包`（v0.11）
- taixu 改 build.gradle.kts +24/-18 + **删了** `app/src/main/assets/bin/rtk` + 加 `assets/plugins/rtk-suite-offline/`（DOWNLOADS.md + manifest.json）
- **原理**：RTK 从 内嵌 assets 移到**首 次 启动 后 用户 主动 下载** or 走 插件中心

**万象 现状**：
- ❌ 我们 **两处 都 有**：`app/src/main/assets/rtk/rtk`（内嵌）**和** `assets/plugins/rtk-suite-offline`（外挂）
- **说明 抄了 一半 忘了 删 内嵌**！这 就 是 为啥 我们 10.23MB vs 他们 8.5MB
- 差 1.7MB ≈ **就是 那份 冗余 的 rtk 二进制**

**判决**：**该 删 内嵌 保留 外挂**。修 5 分钟（改 build.gradle 判断 + 删 一行 assets 依赖）。**收益 直接 包 从 10.23 → 8.5 MB**。

---

## 🧹 其他 值得 关注（次要）

| 短 sha | 内容 | 万象 | 判决 |
|---|---|---|---|
| `b739996` | 双智能体 接入 + 模型路由 + DAG 约束（v0.11） | 🟡 有 DualAgent 但 可能 不 完整 | 需 diff |
| `9e5ec50` | Webchat 线程池 / 长连接 回收（v0.11） | ✅ 有 AndroidHttpServer + WebChatBridgeServer，需 diff 是否 有 泄漏 | 拉 diff 看 |
| `b2b1989` + `e8839aa` | Browser ScreenshotRecorder 线程 shutdown 竞态（v0.11） | ✅ 有 ScreenshotRecorder | 需 diff |
| `564bcd4` | 无线 ADB + FTP 生命周期（v0.12） | 有 类似 | 拉 diff |
| `da5b90a` | ToolSchemaValidator Unflattening 反扁平化（v0.11）| 有 ToolSchemaValidator | **值得 看** —— LLM 输出 深层 参数 现在 是不是 会 挂 |

---

## 🎯 我的**建议 优先级**（供 你 醒 后 定 夺）

### 🔥 第 一 批 · 立 即 修（3-4 小时）
1. **F5 · 删 内嵌 RTK 保 外挂** → **包 从 10.23 → 8.5MB**。**5 分钟 改 build.gradle + 删 一行**。ROI 最 高。
2. **R1 · ToolCallIdNormalizer** → 抄 taixu `ToolCallIdNormalizer.kt`（约 60 行）+ 集成 5 处。**30 分钟**。防 崩溃。
3. **F2 · ContextUsageDialog** → 从 taixu 搬 366 行 Dialog + 精简 我们 Ring 到 127 行。**1.5 小时**。
4. **Y3 · RTK 输出 护栏** → 拉 diff 看 是不是 需要。若 需要 **45 分钟**。

### ⭐ 第 二 批 · 明晚（2-3 小时）
5. **R2 · Compaction 上下文丢失** → 拉 taixu 六处 diff 精 读 + 移植。**1.5 小时**。防 长 对话 挂。
6. **R3 · Terminal IME 稳定化** → 拉 diff 看。**1 小时**。
7. **F3 · Anthropic 中转站 + 连通性 检测** → 抄 他们 7 处 改。**30 分钟**。

### 🚧 第 三 批 · 有 空 再 说（1 天）
8. **R4 · Harness 前缀 缓存 机制** → 4 处 集成。**2 小时**。**性能 大 提升**（Anthropic cache 命中率）。
9. **R5 · 双 智能体 物理 隔离** → 看 我们 版本 是否 已 有。**2 小时**。
10. **F1 · 完整 Workflow 引擎** → **独立 排期**（**5000 行**代码 + 完整 测试）。**一整 天**。**不 建议 混 到 bug fix 里**。

---

## ⚡ 立即 能 拍 板 的

- **删 内嵌 RTK**（1 分钟）→ **包 -1.7MB**
- **抄 ToolCallIdNormalizer**（30 分钟）→ 防 崩
- 其他 都 需要 我 拉 具体 diff 判 断 万象 版本 是否 已 有
