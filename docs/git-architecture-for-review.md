# 万象 Git 架构 Spec · 求审文档 v1

> **给审阅 AI 的 prompt 建议**（复制到新对话最前）：
>
> 你是一位 Android 资深架构师 + Git 内部机制专家。下面这份文档描述了一个叫"万象"的 AI 手机 App（`top.wanxiang.app`，Kotlin/Compose，通过 PRoot 在用户态跑 Ubuntu 沙箱）里 **Git 相关功能的完整架构**。请你**批判性地审阅**：
>
> 1. **架构合理性**：分层、耦合、状态管理、跨进程通信协议。
> 2. **正确性风险**：并发、竞态、失败恢复、状态泄漏、边界。
> 3. **Git 语义准确性**：credential 协议、auth 流程、仓库生命周期。
> 4. **Android/PRoot 特有的坑**：FileObserver 可靠性、DataStore 一致性、后台被 kill、进程隔离。
> 5. **安全**：凭据明文落盘、代理注入、剪贴板敏感数据、Debug 广播。
> 6. **性能与体验**：轮询频率、状态抖动、内存。
> 7. **可测试性 / 可维护性**。
>
> 每条**具体指出问题位置 + 后果 + 建议改法**。不要泛泛而谈。若某设计合理也请确认。
>
> ---

## 0. 上下文（背景）

- **产品**：万象 = AI 手机 App，用户是中文开发者，主要在 GitHub/Gitee/GitLab 上私有仓库工作。
- **技术栈**：Kotlin / Jetpack Compose / Hilt / Room / DataStore / OkHttp / kotlinx.coroutines。
- **核心特点**：**不引 JGit/libgit2**，git 命令一律通过 **PRoot 沙箱里的真 git 二进制**执行。凭据 + 代理 + `.gitconfig` 全在沙箱里配置。
- **网络环境**：手机在中国大陆，GitHub 需代理。App 有 in-app 沙箱代理入口 + 回落 Android 全局 proxy。
- **规模**：本次涉及 `ChatViewModel.kt`（2200+ 行）、`GitPanel.kt`（1200+ 行）、`GitCredentialIpcBridge.kt`、`GitCredentialIpcBootstrap.kt`、`EnvironmentResolver.kt`、`ProotCommandBuilder.kt`、`SandboxTextExtractor.kt`、`AppUpdateManager.kt` 等。
- **已实测通过**：clone/pull/push/stage/commit/branch/tag/stash/revert/credential-verify/AI commit 全在真机跑通。

## 1. 分层结构

```
UI (Compose)
    ChatTopBar / GitPanel (TabRow + HorizontalPager)
    对话框群：克隆 / 新增凭证 / commit / pull dirty / checkout dirty
    GitProgressBanner (挂在 chat 顶栏 + Git 面板 header 各一处)
    GlobalCredentialDialogHost (挂在 MainActivity setContent 顶层)
    DownloadProgressBanner (挂在 MainActivity setContent 顶层)
    Snackbar (ChatScreen Scaffold 的 snackbarHost)
        ↓
ViewModel (ChatViewModel)
    gitPanelState: StateFlow<GitPanelState>
    gitProgress: StateFlow<GitProgress?>
    gitOpMessage: StateFlow<GitOpMessage>  (Idle/Busy/Ok/Error)
    aiCommit: StateFlow<GitAiCommitState>
    credHealth: StateFlow<Map<credId, GitCredHealth>>
    repoList: StateFlow<GitRepoListState>
    extractedTexts: StateFlow<Map<attId, String?>>
    recentCloneUrls: StateFlow<List<String>>
    matchedCredentialId: StateFlow<String?>
        ↓
Runtime (LinuxRuntimeImpl + ProotCommandBuilder + EnvironmentResolver)
    execute(ShellCommand) → 起 PRoot 进程跑 bash → 返回 ShellResult
    ShellCommand 支持：workingDirectory / environment / onOutput 流式回调 / forcePty
        ↓
Sandbox (Ubuntu via PRoot)
    真 git 二进制 + shell helper + curl + apt + pdftotext 等
    .gitconfig 里 credential.helper 两条链
        ↓
跨进程通信（App ↔ 沙箱）
    通过 /wanxiang-ipc/ 目录（PRoot -b 绑定，同一 inode）
    文件协议：cred-req-<id> / cred-resp-<id>

存储
    GitPreferences (DataStore + AES 加密) — 用户凭据真源
    SettingsDataStore (DataStore 明文) — sandboxHttpProxy / recentCloneUrls / 更新冷却字段
    /wanxiang-ipc/git-credentials (明文文件) — 供沙箱 store helper 读
```

## 2. 关键组件职责

### 2.1 `GitCredentialIpcBridge`（跨进程桥）
- 生命周期 `@Singleton`，App 级；`start()` 幂等。
- 双通道捕获 `cred-req-*` 创建事件：
  - 主：`FileObserver(path, CREATE or CLOSE_WRITE or MOVED_TO)`。
  - 兜底：`fallbackPollLoop` 每 1 秒扫一次目录（防 MIUI/PRoot 上 inotify 失效）。
- `seen = LinkedHashSetWithCap(64)` 按 requestId 去重（FileObserver + 轮询并发时不双触发）。
- `_request: MutableStateFlow<CredentialRequest?>` → UI 观察弹全局对话框。
- `respond(id, username, password)` → 原子写 `cred-resp-<id>` + **同时 upsert 到 GitPreferences**（下次同 host 免弹）。
- `cancel(id)` → 写 `cancel=1` → helper 退非零让 git 报"auth failed"。
- `cleanupStale(dir)` 启动时清所有遗留 req/resp 文件。
- 内部 `CoroutineScope(SupervisorJob + Dispatchers.IO)`。

### 2.2 `git-credential-wanxiang`（沙箱 shell helper）
POSIX sh 脚本，作为 `.gitconfig` 里 credential.helper 的第二条（`store --file=/wanxiang-ipc/git-credentials` 之后）。

关键决策：
- 因为 git 的多 helper **顺序串行、凭据累加**，本 helper 总会被调；被调 ≠ 未登录。
- 所以 helper **先自查 `git-credentials` 是否已有 host 条目**：命中则**静默 exit 0**（让 store 已经给 git 的凭据生效）。
- 未命中才走 IPC：`printf 'host=%s\n' > REQ.tmp; mv REQ.tmp REQ`（原子，触发 inotify）→ 轮询 RESP 每 200ms → 最长 25 分钟（配合 git 无 timeout）。
- 拿到 RESP → `cat "$RESP"` 到 stdout 让 git 读 → 清理 req/resp 文件。
- RESP 内容格式：`username=...\npassword=...\n` 或 `cancel=1\n`。

### 2.3 `GitCredentialIpcBootstrap`（装配）
App 启动 `WanXiangApplication.onCreate` 里 `launch { bootstrap.start() }`。做四件事（全幂等）：
1. 从 `assets/wanxiang/git-credential-wanxiang` 抽到 `gitIpcDir/` + `chmod +x`。
2. 用 `linuxRuntime.execute` 跑 `git config --global --unset-all credential.helper; git config --global credential.helper 'store --file=...'; git config --global --add credential.helper '/wanxiang-ipc/git-credential-wanxiang'`（20 次 5s 退避重试直到成功，因为沙箱启动时机不确定）。
3. `observeAndMirrorCredentials`：订阅 `GitPreferences.credentials` flow，任何变更 `distinctUntilChanged` 后**重写 `git-credentials` 明文文件**（先 `.tmp` 再 renameTo）。
4. 启动 `GitCredentialIpcBridge.start()`。

**额外**：如果沙箱 `git` 未装（`buildpack-deps:noble-scm` 镜像不带），首次会 `apt-get update && apt-get install -y -qq git`。apt 用国内 TUNA 源（万象 provision 里已配好）。

### 2.4 `EnvironmentResolver`（沙箱 env）
每次 `linuxRuntime.execute` 都通过它构造 env map。代理优先级：
1. `overrideProxy` volatile（`SandboxProxySync` 从 DataStore 持续灌入的 in-app 值）。
2. `Settings.Global.HTTP_PROXY`（Android 系统级）。
3. 无 → 不注入。

命中则注入 `http_proxy / https_proxy / HTTP_PROXY / HTTPS_PROXY` + `no_proxy=localhost,127.0.0.1,::1` 四个变量。

### 2.5 `ProotCommandBuilder`
每次起 PRoot 时决定 `-b` 挂载。关键：
- `<files>/linux-runtime/git-ipc` → `/wanxiang-ipc`（**IPC 桥的传输介质**）。
- `<files>/linux-runtime/workspace` → `/workspace`（用户项目根）。
- `<files>/linux-runtime/distros/ubuntu/home` → `/root`（含 `.gitconfig`）。
- `<files>/linux-runtime/attachments` → `/attachments`。
- `<files>/linux-runtime/tmp` → `/tmp`。
- `--kill-on-exit --link2symlink --sysvipc --change-id=0:0`。

### 2.6 `GitOpMessage` / `GitOpAction`
统一"完成时给用户的反馈 + 可点动作"状态机：
- `Idle / Busy(label) / Ok(message, action?) / Error(message, action?)`。
- 动作类型：
  - `SwitchWorkspaceTo(path)` — clone 完成后一键切工作区。
  - `RetryWithClean(url, targetDir)` — 目标已存在时清空重试。
  - `RetrySame(cmd)` — 网络失败再来。
  - `UndoRename(oldName, newName)` — rename 后撤销。
  - `StashPop` — stash 完成后一键还原。
  - `CopyError` — 把错误原文塞剪贴板（用户能粘给助手）。
- ChatScreen `LaunchedEffect(gitOp)` 订阅 → 显 Snackbar → 消费 `consumeGitOpMessage()` 回 Idle。

### 2.7 `runStreamingGitOp` / `gitClone` / `applyGitProgress`
- 网络 op 用 `forcePty=true`（PRoot 里 `script -qfec`）让 git 走 tty → 吐 `% Receiving objects: NN% (a/b), X MiB/s` 进度。
- `ShellCommand.onOutput: (String) -> Unit` 每 chunk 调 → `applyGitProgress` 用 `\r` 分段取最后一段 → 正则抽 `% (\d+)%` / `\((\d+)/(\d+)\)` / `([\d.]+ [KM]?B)` → 写 `_gitProgress` StateFlow → UI 每秒多次刷新。
- 网络失败：`isRetryable(out)` 判定（`failed to connect / could not resolve / timed out / rpc failed / early eof`）→ 3 次退避（1s / 3s / 9s）。
- 其他错误：`translateGitError` 把常见 git stderr 翻成人话（"同名目录已存在 → 一键清空"、"认证失败 → 更新 PAT"、"权限不足 → 缺 scope"、"404 → URL 错或私有"）。

## 3. 关键流程

### 3.1 完整 clone 私有仓库
```
① 用户点克隆 → ChatViewModel.gitClone(url)
② repoName = URL 末段去 .git；ws = currentGitWs()
③ 若 !workspaceExists(ws) → mkdir -p ws（clone 本质是"建项目"，父不该要求预先存在）
④ 若 workspaceExists(ws/repoName) → Error + RetryWithClean 动作
⑤ 循环 attempt 0..2：
    取消标志 cancelRequested 检查
    找凭据 findCredential(creds, host)：
      有 → effective = GitAuth.wrap(raw, cred)  // GIT_CONFIG env + 一次性临时凭据文件
      无 → effective = raw
    linuxRuntime.execute(
      commandLine = "$effective 2>&1",
      workingDirectory = ws,
      timeoutMs = 600_000,
      onOutput = { chunk -> applyGitProgress(chunk, ...) },
      forcePty = true,
    )
    → 沙箱 bash → git
⑥ git 需 auth → credential.helper 链：
    store helper 读 git-credentials：有则返回；无则返回空
    → 空时我 shell helper 触发：
      写 cred-req-<id> 到 /wanxiang-ipc/
      轮询 cred-resp-<id>（每 200ms，25 分钟上限）
⑦ 同时 App 侧：
    GitCredentialIpcBridge.FileObserver 或 1s 兜底轮询捕获 → 解析 host
    → _request.value = CredentialRequest(id, host)
    → GlobalCredentialDialogHost 弹「为 github.com 登录」
    → 用户填 + 点保存并重试
    → bridge.respond(id, user, pass)：
      原子写 cred-resp-<id>（协议 4 字段 protocol/host/username/password）
      upsert 到 GitPreferences（下次免弹）
      _request = null（关弹窗）
⑧ helper 读到 RESP → cat 给 git → git 用凭据继续 fetch
⑨ git 每输出流式回 onOutput → applyGitProgress → 状态推 UI 进度条
⑩ exit=0 → _gitProgress=null + _gitOpMessage = Ok + SwitchWorkspaceTo 动作
⑪ Snackbar 「✓ 已克隆到 Wanxiang/」+ [切过去]
⑫ 用户点切过去 → switchWorkspace(path) → refreshGitStatus
```

### 3.2 pull / push
- pull：dirty → 弹二次确认（`pendingCheckout` 类似机制叫 `pullDirtyConfirm`）；否则 `runStreamingGitOp("git pull --progress")`。
- push：先 `git rev-parse --abbrev-ref @{u}` 查 upstream → 无 → `git push -u origin <current>`；有 → `git push --progress`。也走 `runStreamingGitOp`。

### 3.3 附件解析（PDF/DOCX/PPTX/EPUB → AI 读文）
```
① ChatScreen picker onAttachmentsPicked(uris)
② 每 uri 走 AttachmentHelper.processUri → 拷到 files/linux-runtime/attachments/<ts>_<safeName>
③ 图片走 base64 → 多模态 vision
④ 文档非图片：coroutineScope { forEach { launch { extractTextForAttachment } } } 并发
⑤ SandboxTextExtractor.extract(guestPath, name)：
   PDF   → command -v pdftotext || apt-get install -y poppler-utils; pdftotext -enc UTF-8 ... -
   DOCX  → unzip -p file word/document.xml | sed 's/<[^>]*>/ /g' ...
   PPTX  → unzip -p file ppt/slides/slide*.xml | sed ...
   EPUB  → unzip -p file *.xhtml *.xml | sed ...
   MD/TXT/HTML → cat
⑥ 结果 Map<attId, text?> → _extractedTexts StateFlow
⑦ 附件卡上徽章：⟳解析中 / ✓ N 字符已抽取 / — 无可抽文本 / ✗ 失败原因
⑧ sendFromComposer 时把 text 用 markdown 代码块拼进消息 → AI 秒读
```

### 3.4 应用更新
```
① App 冷启 → MainActivity.setContent 里 LaunchedEffect(onboarding.completed)
② autoCheckUpdates 开关 → 距上次 <6h 冷却中 return → setLastUpdateCheckTime(now)
③ checkUpdateMerged → GET /app-api/wanxiang/config/get-all → 拿 wanxiang.latest_version
④ versionCode > current 且 != dismissedCode → 延后 3s → updateInfo = info
⑤ 弹对话框
⑥ 用户点下载 → **重跑一次 checkUpdateMerged 拿最新**（防中间版本 cascade，
   例如 dialog 显 0.4 时我发了 0.5，这里如果 fresh.versionCode>info 就静默升到 0.5）
⑦ downloadApk(url, expectedTotalBytes, onProgress)：
   - 检查 .part 半成品文件 → 有则带 Range: bytes=<existing>-
   - 206 → 追加；200 → 覆盖从头
   - 416 或 200 且已有 existing → 清 .part 重来
   - 每 chunk → _downloadProgress StateFlow
⑧ 全局 DownloadProgressBanner 挂 MainActivity 根，跨页可见
⑨ 下完 .part renameTo apk → installApk FileProvider ACTION_VIEW
```

### 3.5 AI 生成 commit message
```
① 提交对话框顶部 [✨ AI 生成] 按钮（未 staged 时禁用）
② aiGenerateCommitMessage() → _aiCommit = Loading
③ git diff --staged --stat + git diff --staged | head -c 8192
④ providerClient.resolveModel() 拿当前激活模型（走 KeyRoulette）
⑤ system prompt 让模型按 Conventional Commits 中文写
⑥ providerClient.chat(model, messages) → text 回填 commitMessage
⑦ aiCommit = Done(text) → UI LaunchedEffect 观察填输入框
```

## 4. 存储与"真源"选择

**同一份凭据在两个地方存**，可能引发一致性问题：
1. `GitPreferences.credentials: Flow<List<GitCredential>>`（DataStore + AES 加密）。
2. `/wanxiang-ipc/git-credentials`（明文文件，供沙箱 git 的 store helper 读）。

写路径：UI 增删 → `GitPreferences.setCredentials(list)` → DataStore 变 → `observeAndMirrorCredentials` 通过 `distinctUntilChanged` 触发 → 重写文件。

读路径：
- UI（凭证页、AI commit、健康检查）读 `GitPreferences`。
- 沙箱 `git credential fill` 走 `.gitconfig` 的 store helper → 读**明文文件**。

## 5. 会话 / 工作区 / 项目 关系

- **会话（ChatSession）**：Room 表 `harness_session`，字段 `workspace: String`（相对或绝对 Linux 路径，空 = 未绑）。
- **工作区（WorkspaceProject）**：`files/linux-runtime/workspace/<name>/` 或 `<files>/sdcard/projects/<name>/` 挂到 `/workspace/<name>`。
- **Git 面板绑工作区不绑会话**：`currentGitWs() = workspace.value.ifBlank { "/workspace" }` → 所有 git 命令的 `workingDirectory`。
- **两个会话绑同一工作区 → 看到同一份 git 状态**（合理，因为是同一目录）。
- 会话切工作区靠 `harnessLoop.workspace: StateFlow<String>`，全局单例。

## 6. 一些设计选择与已知权衡

| 决策 | 理由 | 代价 |
|---|---|---|
| 不引 JGit，全走沙箱真 git | 语义 100% 正确 + submodule/LFS/worktree 免费 | 依赖 PRoot 稳定性 + 首次装 git 慢 |
| 凭据双源（加密 DataStore + 明文文件） | UI 层要加密 / store helper 只能读明文文件 | 一致性风险 + 明文文件泄露面（但 Android 私有目录，其他 app 读不到） |
| FileObserver + 1s 轮询 双通道 | MIUI/PRoot 上 inotify 未必可靠 | CPU 略耗 + 需 seen set 去重 |
| 更新时"点击下载再重拉一次配置" | 解决 0.4→0.5 中间版本 cascade | 用户点下载多等 200-500ms |
| forcePty=true | 让 git 走 tty 吐 `%` 进度 | script -qfec 命令包装开销 + `\r` 分段处理 |
| in-app 代理优先 Android 全局 | 中国手机 Android 全局 proxy 只对 WebView/OkHttp 生效，libcurl 不读 | 用户可能有认知负担（设置里两个地方都能填） |
| GitOpAction 用 sealed interface | 编译期穷举 | 每加动作类型要改 UI when |
| 附件解析走沙箱（不引 PdfBox/MuPDF） | 避免 APK 大小 +5MB | 首次要 apt-get install poppler-utils |

## 7. 请审阅的具体点（每条请 AI 明确回答）

### 架构
- **A1** `ChatViewModel` 已 2200+ 行、30+ git 相关方法都塞这里。是否该拆出 `GitViewModel`？拆分的边界怎么定（哪些状态共享、哪些独立）？
- **A2** Git 状态是**工作区级**不是**会话级**。会话切换用 `harnessLoop.workspace` 一个全局单例 StateFlow。如果同一时间两个 ViewModel 都活着（多 pane），会不会读到不属于自己的 workspace？
- **A3** `GitCredentialIpcBridge._request` 是 MutableStateFlow 单值——同一时刻只支持一个 pending 请求。如果两个 git 并发需要 auth（例如两个 clone）怎么办？第二个请求会覆盖第一个的 UI 状态。
- **A4** Bootstrap 里 `credential.helper` 用 `--unset-all + --add + --add` 保证幂等；但 bootstrap 只跑一次；如果用户手动 `git config --global credential.helper` 加了什么，重启会被我覆盖。合理吗？

### 正确性 / 并发
- **B1** `runGitWriteWithSuccess` 里的 `_gitOpMessage.value = Ok(...)` 会被并发命令互相覆盖（后写的赢）。若用户快速点两次操作（比如连续两个 stage），Snackbar 反馈会丢。
- **B2** `cancelRequested` 只是标志位，实际 `linuxRuntime.execute` **不能中途取消**。用户点取消要等到当前 chunk 才 break 循环，但 shell 命令在沙箱里继续跑到 timeoutMs（10 分钟）。真实中断该怎么做？
- **B3** `applyGitProgress` 里 `_gitProgress.value = GitProgress(...)` 每个 chunk 都更新 → 每秒可能几十次 recomposition。有没有节流必要？
- **B4** `GitCredentialIpcBridge.cleanupStale` 在 `start()` 里跑（scope.launch），跟 FileObserver 的 `startWatching()` 并发。有极小概率在启动瞬间漏事件。要不要在 `start()` 内先 cleanup 再 startWatching？
- **B5** 沙箱 shell helper 用 `RID="$(date +%s)-$$"` 做 requestId。同一秒两个 git 进程 PID 不同 OK；但如果沙箱重启过 PID 从 1 开始 → 可能撞车？（实际上 pid 唯一保证跨进程，但沙箱是共享 PID namespace 吗？）
- **B6** `verifyGitCredential` 用沙箱 `curl api.github.com`。如果用户网络挂了 → 状态 `Unknown`；如果 token 有效但**手机没网**（比如 airplane mode） → 也会 `Unknown`。用户以为"网络问题不是 token 问题"→ 但下一次 push 会失败。要不要区分？

### 存储 / 一致性
- **C1** GitPreferences 加密（AES） + `git-credentials` 明文。用户卸载 App → 明文文件随 app 目录删除？DataStore 加密 key 在 Android Keystore，卸载即丢。合理。**但如果手机 root + adb pull data** → 明文凭据直接被读。要不要考虑至少混淆（比如 URL encode 已经做但不算加密）？
- **C2** `recentCloneUrls` 明文 DataStore 存。里面可能有私有仓库 URL（比如 `https://github.com/company/secret-repo.git`）。要不要跟凭据一样加密？
- **C3** `observeAndMirrorCredentials` 用 `distinctUntilChanged` — 如果凭据 list 顺序变了但内容一致 → 不触发。但如果 UI 上 reorder 了，git-credentials 文件的**排序**（当前按 host.lowercase）可能变。git store 用最后匹配的行。有 subtle bug 吗？
- **C4** 用户在两个入口改凭据（凭证页 UI 删 / 沙箱 `git credential reject` 让 store 自己删） → 两边不一致。目前只有第一个入口，OK。但如果以后开放终端给用户手敲 git，会踩这个坑。

### Android / PRoot
- **D1** FileObserver 用 `String path` 构造（`@Suppress("DEPRECATION")`）。API 29+ 推荐 File 版；这里用 String 是因为项目 minSdk=26。但 PRoot 的 `-b` 绑定后 inotify 事件跨 mount 边界的行为**没标准保证**。你信 1s 轮询的兜底吗？间隔要不要更短？
- **D2** 沙箱 `.gitconfig` 通过 `linuxRuntime.execute("git config --global ...")` 写。如果沙箱还没 ready（`LinuxRuntimeImpl` restoreInstalledState 未完成）→ 命令失败 → 靠 20 次重试。**但**在应用被系统 kill 后重启 → 冷启时间可能超过重试窗口 100 秒 → credential.helper 就没注册。用户 clone 直接失败 → 但错误消息可能不指向这个根因。怎么改善？
- **D3** PRoot 挂载 `<ipcDir>:/wanxiang-ipc` + `<homeDir>:/root`，两者嵌套（后者是前者父路径）。PRoot 的 `-b` 处理这种嵌套冲突吗？会不会有 `/root/.gitconfig` 和 `/wanxiang-ipc/*` 的路径混淆？（现在实测没问题，但依赖未文档化。）
- **D4** `--change-id=0:0` 让沙箱内是 root。这意味着 apt-get install 无需 sudo。副作用：所有 git 命令都在 root 权限下跑，`.git` 目录文件属 root（宿主机看到的 uid 是 u0_a300，通过 PRoot 映射 OK）。但 `safe.directory = *` 的必要性就来自这里。生产环境上有没有其他隐患？
- **D5** App 从后台被系统 kill 时，PRoot 子进程会被杀吗？（`--kill-on-exit` 是 PRoot 正常退出时的清理，被 SIGKILL 时未必。）如果 PRoot 泄漏 → 内存/文件描述符积累。有没有 watchdog？

### Git 语义
- **E1** credential 协议里 RESP 我给了 4 字段（protocol/host/username/password）。git 文档说只要 username/password 也 OK（其他从 request 继承）。你倾向哪种？给全 4 字段有兼容风险吗？
- **E2** 我的 shell helper 只在 `op == "get"` 时动 store/erase 都 exit 0。但 git 认证**失败**时会调 `credential reject` → 触发 `erase`。目前 erase 什么都不做 → 用户 token 已过期但 git-credentials 文件里还在 → 每次 push 都失败要用户手动去 UI 删。要不要 erase 时也做点事（比如标记 credential 为 invalid）？
- **E3** `GitAuth.wrap` 生成一次性凭据文件：`mktemp` → printf → `GIT_CONFIG_KEY_0=credential.helper GIT_CONFIG_VALUE_0="store --file=$__cred"` → 命令结束后 `rm`。这是**绕过**沙箱的全局 credential.helper 链，用 inline 凭据。但**同时**保留了 `unset-all + 用我 tmp 文件` 会**覆盖** shell helper。也就是说**有已存凭据时不走弹窗**（正确），无已存凭据走弹窗（正确）。但**如果 token 过期 → git 认证 401 → git 调 reject → 谁都不管**（我们的 shell helper 只在 get 时动）。用户就陷入"看到 auth failed 但弹窗不出现"。要不要在 helper `erase` 时也弹"这个 token 是不是过期了？"
- **E4** `gitPush` 查 upstream 用 `git rev-parse --abbrev-ref --symbolic-full-name @{u}`。命令失败时输出是错误消息文本（比如 `fatal: no upstream configured`）。我用 `!upstreamSet.contains("no upstream")` 判"没 upstream"——脆弱。改用 exit code 判断更稳。
- **E5** `gitClone` 目标 `<repoName>` 用 URL 末段去 `.git`。`https://github.com/x/y/` → `y`（OK）。但 `git@github.com:x/y.git`（SSH 形式）→ 我 `substringAfterLast('/')` 拿不到（因为没有 `/`）。当前只支持 HTTPS，OK。但如果用户误粘 SSH URL，会拿整串当 repoName → 名字里带冒号奇怪。**是否该明确拒绝非 HTTPS**？

### 安全
- **F1** shell helper 用 `cat "$RESP"` 把凭据**明文**输出到 stdout → git 读。这条 stdout 会进 git 的 stderr 日志吗？（不会，git 内部管道，不落 log。）但 `linuxRuntime.execute` 捕获的 stdout 里**可能包含 git 的 stdout**——如果 git 处理 credential helper 输出时把它打印出来了（比如 verbose 模式），凭据会进 万象 runtime.log（明文）。要不要在 applyGitProgress 前做正则脱敏 `ghp_[A-Za-z0-9]{36}`？
- **F2** Debug 广播（`top.wanxiang.app.DEBUG_GIT` 等）只在 debug 版 AndroidManifest 注册。**但如果 release 版被误打了 debug AndroidManifest 合并（比如 build.gradle 配置错）** → 广播入口对外开放 → 任何人 adb 或第三方 app `sendBroadcast` 就能触发 gitClone/set_proxy/clear_creds。要不要 receiver 端加签名校验 or `android:permission` 保护？
- **F3** GitOpAction.CopyError 把整段错误文本塞剪贴板。如果错误里包含 token（比如 `fatal: Authentication failed for 'https://peakSee:ghp_xxx@github.com/...'`）→ token 进剪贴板 → 其他 app 读剪贴板 → 泄露。要不要 CopyError 前正则掩码？
- **F4** DataStore 的 `sandboxHttpProxy` 是明文字符串（`http://user:pass@host:port` 可能带凭据）。DataStore 明文文件在 app 私有目录。同上：root + adb 可读。用户如果配了带 auth 的代理 URL → 代理凭据泄露。要不要加密？

### 性能
- **G1** fallbackPollLoop 每 1s 一次 dir.listFiles。ipc 目录一般只有几个文件 → 忽略。**但**如果 helper 崩了没清理，req/resp 文件会积累。seen 有 cap 64 但文件本身没清理。要不要在 pollLoop 里定期清 N 小时前的孤儿文件？
- **G2** `applyGitProgress` 每 chunk 触发 `_gitProgress.value = ...` → Compose 端 recomposition。git 输出快时（如 fetch 大量 objects）一秒能 100+ chunk。StateFlow 是 conflated 的，最终消费会去重，但 Compose 侧 collectAsStateWithLifecycle + recomposition 有开销。节流 100-200ms 有意义吗？
- **G3** `gitClone` 的 `timeoutMs=600_000` 10 分钟。用户手机 4G 慢的话大仓库可能超时。要不要根据 repo 大小动态调？
- **G4** 附件解析并发 launch 时如果用户传 10 个 PDF → 10 个 linuxRuntime.execute **并发**。PRoot 一次起 10 个 bash 进程 → 内存/CPU 顶得住吗？（PRoot 每个进程几 MB，10 并发大概 100MB 增量。）要不要限流到 3 并发？

### 可测试 / 可维护
- **H1** 现在 15+ git 方法都直接依赖 `LinuxRuntime.execute`，单测困难（只能集成测）。要不要抽一个 `GitCommandRunner` 接口，测试时 mock？
- **H2** `GitAuth.wrap` 生成的 shell 字符串很长（含 mktemp + printf + env + cmd + rm + exit code）。有没有单测覆盖？改 shell 语法很容易引入 bug。
- **H3** `translateGitError` 一堆字符串 `in l` 判断。改文案很脆。要不要抽成数据（YAML/JSON）+ 单测覆盖每种 pattern？
- **H4** UI 层 `GitPanel.kt` 1200+ 行包含 6 个 `@Composable` 函数 + 若干 Dialog。要不要拆到不同文件？

---

## 8. 已知不完美但暂时保留的点（我意识到 + 有代价）

1. **`ChatViewModel` 太大** — 拆 GitViewModel 是重构大工程。
2. **FileObserver 兜底靠 1s 轮询** — 更严的做法用 `inotify` 系统调用 + `signalfd`，实现复杂度高。
3. **凭据明文文件** — 走 Android Keystore + EncryptedSharedPreferences 更严，但沙箱里 git 得能读 → 只能明文。
4. **cancel 不真中断** — 需要 LinuxRuntime 支持 process kill（改底层）。
5. **apt-get install poppler-utils 首次 30-60s** — 可以预装到 rootfs，但 rootfs 打包时加体积。
6. **沙箱 `.gitconfig` 被 bootstrap 每次覆盖** — 如果用户手改会丢失。改成"只 unset 我自己的 key + add"更安全。

---

**请给出你的评审：按上面 7 大类逐条明确回应（合理/风险/改法），不要笼统总结。发现上面没列的问题也请补上。**
