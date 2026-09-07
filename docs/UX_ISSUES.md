# UX 问题台账（2026-08-29）

> 2026-08-29 完成一轮全量 UX 审查（约 150 条，覆盖 feature 全部 UI 模块 + app 装配层）并**全部修复**，逐条明细见当日提交记录。
> 本文件现仅记录：已落地的系统性修复、**遗留项**、以及后续新增 UX 问题的登记区。
> 新问题按「`文件:行号 — 描述 — 严重度`」追加到文末，修复后删除。

## 已落地的系统性修复（组件/VM 层收敛）

| # | 修复 | 位置 |
|---|------|------|
| S1 | 图标按钮语义：各屏图标按钮补 `contentDescription`（走 stringResource）；RuntimeIconButton Material 分支忽略描述的缺陷已修；ToolCard/MCP/权限状态点等纯颜色指示补 semantics | 各 feature 模块 |
| S2 | 破坏性二次确认（12 处）：删会话/消息/模型档案、清空草稿、关终端会话（3 秒倒计时）、放弃编辑、删构建脚本、删 GGUF 模型（倒计时）、卸载工具（卡片+详情页共用 `UninstallToolConfirmDialog`，internal）、删快捷短语、删挂载点、删 MCP 服务、Skill 删除 | 各 feature 模块 |
| S3 | 表单/弹窗状态 `remember` → `rememberSaveable`/listSaver/mapSaver；DistroManagement 安装进度整体迁入 VM `DistroInstallUiState`（旋转可恢复）；聊天输入草稿入 SavedStateHandle；聊天弹窗开关/编辑目标、工作区三步向导（含 mapSaver）、模型编辑器全字段迁移 | 各 feature 模块 |
| S4 | 静默失败改可观察：ToolCenterViewModel `operationError`+`isSyncing`、ToolDetailViewModel toggleAutoStart 失败上抛、APK 下载失败对话框内错误文案、聊天附件失败计数+Toast、系统页跳转失败 Toast | 各模块 |
| S5 | `throwable.message` 直出全部映射为用户文案，原始异常进 Log | 各模块 |
| S6 | 字符串 `contains("失败")` 判错全部改为 VM 类型化标记（`xxxIsError: StateFlow<Boolean>`）：workspace ×3（`messageIsError`）、settings ×8 | workspace/settings |
| S7 | <48dp 触摸目标全部加 `Modifier.minimumInteractiveComponentSize()` 或放大；RuntimeIconButton 玻璃分支同步 | 全应用 |
| S8 | 点名硬编码文案迁移 strings 资源并同步 values-en（chat +30 条、workspace 模板/工坊整屏、home/terminal、settings、components、custom_iteration 新建 res） | 各模块 |

专项（非穷举）：终端启动失败可重试（`retryInitialize`）、内嵌终端隐藏返回键（`showBackButton`）、终端错误单一入口、LazyColumn 稳定 key（会话/技能/MCP/终端行）、Markdown 表格横向滚动、diff 虚拟化（LazyColumn + DiffLine）、FlowRow 徽章、轮询 100/200ms→500ms、审批卡 busy、`/clear` 与同名模型档案提示、输入校验（端口 1024-65535/挂载路径/gguf/URL/文件名，VM 前置校验）、创建工程进行中反馈、子目录加载遮罩、AI 自愈两入口日志统一、提示词编辑 400ms 防抖、`terminal_*`/`home_*` 等文案资源化、PTY 标题架构动态取 `Build.SUPPORTED_ABIS`。

## 遗留项（经评估暂不处理）

- `RuntimeIcon` 的 `contentDescription` 默认仍为 null（改必传需动全量调用点签名）；语义修复落在调用点与 RuntimeIconButton 层。
- 聊天死代码：`ContextUsageButton`、`ChatTurnAnchorBar` 保留未删。
- `CodeEditorScreen` 的 `TextFieldState` 不可保存（光标位置旋转丢失；内容由 VM 恢复）；编辑器深色配色不随主题（重设计项）。
- settings 模块未加全局表单 BackHandler（影响面大，仅做了状态保存）。
- custom_iteration Screen 正文、WorkspaceScreen 向导 :1107-1154 等未点名硬编码文案未迁移（长期清理项）。
- values-en 历史存量缺失条目未追溯补齐。
- Agent 会话 LMK/SIGKILL 内存治理另见 docs/KNOWN_ISSUES.md。

## 新增问题登记区

（空）
