# 万象 · WanXiang v0.10.0 发布记录

> **发布时间**：2026-09-03
> **版本号**：v0.10.0（`appVersionName = 0.10.0`，`appVersionCode = 16`）
> **支持范围**：Android 10+ · arm64-v8a（无 Root / PRoot 沙箱）

---

## 🎉 版本亮点

v0.10.0 是本轮迭代中最具「可观测、可恢复」意义的一个版本：

- **浏览器获得 Hook 引擎与 CDP 级调试能力**——可注入式拦截、断点、Worker 级 Fetch 拦截，并配套网络时间线面板，浏览器行为第一次可以被精确观察与调试；
- **对话正式支持「回退」**——基于 SessionFork 会话树派生的 Rewind/Checkpoint 机制，配每轮文件快照与磁盘持久化，重大操作前随时可撤回；
- **Agent 记忆升级为语义模型**——冲突去重、revision、pinned、新鲜度语义，配合 Room 数据库迁移（43→44）；
- **Skill 导入体验全面增强**——目录递归发现、任意位置导入、ZIP 批量导入、自动去重。

---

## ✨ 新功能

### 浏览器：注入式 Hook 引擎与 CDP 调试

- **注入式 Hook 引擎**：内置浏览器支持脚本注入式 Hook，可对页面行为进行拦截与改造；
- **CDP 断点 / Worker 级拦截**：通过 CDP 协议提供断点能力，并支持 Worker 级 Fetch 拦截，可观察页面网络请求全过程；
- **网络时间线面板**：在浏览器面板中实时查看网络请求时间线；
- **CDP 暂停横幅**：调试暂停时显示明确横幅提示，避免误以为页面卡死；
- **浏览器 MCP 动态端口**：Harness 侧浏览器 MCP 服务改为动态端口分配，避免端口冲突，并收敛 provider schema。

### Agent Harness：对话回退与 Checkpoint 快照

- **对话回退实装（Rewind）**：基于 **SessionFork 会话树派生**实现对话回退，可在不丢失上下文的前提下回到历史轮次；
- **「撤回到此轮」UI 入口**：聊天界面新增回退入口，一键撤回至指定轮次；
- **Checkpoints 每轮文件快照安全网**：每轮对话前对工作区文件做快照，为回退提供文件级安全网；
- **Checkpoint 快照磁盘持久化**：快照落盘，重启应用后仍可回退。

### Agent Harness：记忆语义模型（数据库 43→44）

- **记忆冲突去重 / revision / pinned / 新鲜度语义模型**：记忆条目支持冲突消解、版本（revision）、置顶（pinned）与新鲜度（recency）语义，Agent 的记忆更加可控、可维护；
- 对应 Room 数据库从 v43 迁移至 v44（详见下文「数据库迁移」）。

### Agent Harness：子智能体调度与预算控制

- **write_paths 写租约波式调度**：子智能体的文件写路径以「写租约」方式波式调度，避免多智能体并发写冲突；
- **父级 facts pack**：子智能体向父级回传结构化 facts 打包，汇总信息更规整；
- **汇总注入预算控制与超限分页落盘**：子智能体汇总注入增加预算控制，超限内容自动分页落盘，避免长文本丢失或撑爆上下文。

### Skill 导入与管理

- **Skill 目录递归发现**：设置页自动发现嵌套目录中的 Skill；
- **任意位置自选目录导入**：支持从任意目录手动选择导入 Skill；
- **Skill ZIP 批量导入**：支持 ZIP 包形式批量导入；
- **目录自动发现批量导入**：自动扫描 `attachments/skills` 与工作区 `skills` 目录，按 resourcePath 去重批量导入。

### 工作区（Workspace）

- **GitHub 导入实时进度**：GitHub 导入弹窗实时展示 `git clone` 进度与百分比，长任务不再「干等」。

---

## 🐛 问题修复

- **终端**：修复进入终端时 `scrollToItem(-1)` 导致的闪退；
- **Harness**：修复异常历史导致 DeepSeek 返回 400 `tool_calls` 序列错误；
- **Harness**：修复 DeepSeek 思考模式下工具调用轮未回传 `reasoning_content` 的问题；
- **Harness**：修复子智能体超时后工具调用计数归零的问题；
- **聊天**：图片下载增加反爬规避，并修复本地图片渲染问题；
- **浏览器**：tab id 前缀归一，修复 URL 含反引号导致的解析清洗异常；
- **设置**：候选模型过多时限制高度内部滚动，避免撑爆弹窗底部黑屏；
- **运行环境**：在线 Android 套件补齐 JDK 规范路径软链接，修复工坊签名环境误报缺失；
- **构建预检**：CMake/Ninja 按 `has_native` 门控，并按实际缺失项报错，避免误报；
- **CI**：Release 流水线签名配置改写入 `keystore.properties`，放开 CI 签名限制。

---

## ♻️ 体验优化与重构

- **图标体系**：全量替换系统图标为 **Lucide 单色 SVG**，移除 `material-icons-extended` 依赖，应用体积与视觉统一性双收益；
- **代码清理**：移除死代码与未用依赖，Harness 侧去 BOM 与 `Duration.milliseconds` 迁移清理。

---

## 📝 文档

- 导航文档同步 Hook 引擎与 CDP 调试能力（见 `docs/AI_NAVIGATION.md`、`docs/BROWSER_DESIGN.md`）。

---

## ⚠️ 数据库迁移说明

本版本将 Room 数据库从 **v43 迁移至 v44**，迁移内容为记忆语义模型相关字段（含 `expiresAt` 默认值修正为 `DEFAULT NULL` / 小写 `null`）。

- 升级安装后数据库将自动迁移，**无需手动操作**；
- 迁移不可降级回退，如因故需要回退到旧版本，请先备份工作区与对话数据。

---

## 🔖 提交范围

自上一发布（v0.9.0）以来共 **30 个提交**（含发布提交），覆盖 `harness` / `browser` / `chat` / `settings` / `workspace` / `terminal` / `runtime` / `database` / `ci` / `components` 等模块。

---

*掌中万象，造物随心。愿每一次意图，都能落成可检查的结果。*
