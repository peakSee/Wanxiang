# 🌐 万象 (WanXiang) — 内置浏览器与 Harness 集成方案 (Browser Design)

> **范围**：在 Android 无 Root WanXiang 内置一个 `in-app WebView`，并让 harness 通过 MCP 协议像 Codex 操纵内置 In-App Browser 一样驱动它；可同时被桌面 Claude / Cursor / Copilot 通过 `adb reverse` 接入。
> **状态**：v0.9.0-MVP 落地切片（首期实现 in-app 一族 + 文件系统一族）。
> **作者**：WanXiang Architecture Team
> **创建日期**：2026-09-02

---

## 1. 🎯 目标与对位

### 1.1 目标

让 WanXiang 的 Agent Harness 能像 Codex desktop 内置浏览器那样：

1. **内置可见**：用户能在 App 内打开一个真正的浏览器页面（多 Tab、URL Bar、Co-browsing 状态条）。
2. **AI 可控**：harness 内的 LLM 通过 MCP 工具（`mcp__browser__open` / `navigate` / `snapshot` / `click` / `type` / `screenshot` …）调度同一个浏览器实例。
3. **共驾（Co-browsing）**：UI 操作、AI 操作在同一个 `WebView` 上交替发生，谁的最近一次操作、谁在接管，可视化切换。
4. **可选外接**：同端口（`127.0.0.1:8787` 或 `0.0.0.0:8787`）允许桌面 AI / CLI 通过 MCP JSON-RPC 接入。
5. **安全闭环**：复用 `core:security/SecretRedactor`，敏感 Cookie / Authorization / Password 默认脱敏；按风险等级（LOW / MEDIUM / HIGH / CRITICAL）申请审批。

### 1.2 Codex 对位

| Codex desktop | WanXiang（本方案） |
| --- | --- |
| `agent.browsers.list()` / `get(name)` / `getForUrl()` / `getDefault()` | `BrowserRegistry.listFamilies()` / `get(family)` / `getForUrl()` / `getDefault()` |
| 内置 In-App Browser（XAML / WKWebView） | in-app `WebView`（`androidx.webkit`） |
| 通过 Chrome Extension + CDP 操纵桌面 Chrome | 通过 **in-process MCP server** 操纵自己 app 内的 WebView；外部 Chrome 走 Intent 唤起（v1.1+） |
| 工具结果含 base64 截图 | `ToolResult.imageAttachments: List<ToolImageRef>`，UI 用 Coil 渲染本地文件 |
| 持久化浏览器 binding 跨 turn | `BrowserRegistry` 单例 + `BrowserSessionToken`（按 agent session） |
| 选择策略：用户显式 > URL 隐式 > 默认 | `BrowserSelectionPolicy.decide(request, prefs, urlHint)` |

### 1.3 与 WebReverse-MCP 的关系

本方案**借鉴**其模块切分粒度 / 工具动词集 / 风险矩阵 / Evidence Store 设计；**不复用其代码**（他们的 `pluginManagement` 与 `gradle 7.x` 配置不适配 WanXiang），后续若有强需求可独立 PR 借鉴特定模块。

---

## 2. 🏗️ 模块拓扑

```
feature/browser               ── Compose UI（RuntimeCard/RuntimeTopBar 设计系统）
       │
       ▼
runtime/browser               ── BrowserRegistry + WebView pool + InApp Engine + MCP tool handlers
       │                          │
       │                          ├─► McpInProcessServer (loopback HTTP)
       │                          │
       └─► 复用 runtime/WorkspaceManager & WorkspaceFileService（file.* 工具全走它）

harness/mcp/server            ── Ktor 起服务：POST /mcp/sse、Auth、Tool Dispatch、Resources
       │
       ▼
harness (现有 McpManager)     ── bootstrapBuiltinBrowser() 启动时插入配置 + 保活
       │
       ▼
core/browser (Pure Kotlin)    ── BrowserFamily / Risk / Capability / SnapshotRef / PageSnapshot
core/datastore                ── BrowserPreferences 分面（homeUrl / allowRemote / allowEvalJs / coBrowsing）
core/model                    ── ToolResult.imageAttachments（已加）、McpServerConfig.isBuiltin（已有）
```

### 2.1 单向依赖

保持与全项目一致的：`feature/* → runtime/* → core/*`；新增模块**严格不可被反向依赖**。

---

## 3. 🔌 协议与数据形态

### 3.1 工具命名（统一 MCP 风格）

工具在模型侧的呈现：

```
mcp__browser__open                ── 新建或复用 tab 打开 URL
mcp__browser__navigate            ── 当前 tab 跳转
mcp__browser__snapshot            ── 提取交互元素 ref
mcp__browser__click               ── 按 ref 点击
mcp__browser__type                ── 按 ref 键入文本
mcp__browser__press               ── 按 ref + 按键（Enter/Tab/Escape…）
mcp__browser__screenshot          ── 当前 tab 全屏截图落盘并返回 image_ref
mcp__browser__current_url         ── 取 URL / Title
mcp__browser__list_tabs           ── 列出所有 tab
mcp__browser__close_tab           ── 关闭指定 tab
mcp__browser__evaluate            ── 在当前页执行一段 JS（critical）
mcp__browser__page_source         ── 返回页面 pretty print HTML
mcp__browser__network_list        ── 列出已捕获网络请求
mcp__browser__cookies_get         ── 取 Cookie（高风险）
mcp__browser__local_get / list / delete / clear
mcp__browser__session_*           ── 同 local
mcp__browser__file_*              ── 工作目录读/写/搜/hash/zip/share（复用 WorkspaceFileService）
```

> 命名解析走现有 `harness/mcp/McpToolApiName.encode` / `legacy` 兼容。模型看到的实际是 `mcp__browser__open__<hash>`，但**不影响** harness 拼回 `McpToolInfo`。

### 3.2 Snapshot Ref 协议（防泄露内部 selector）

```json
{
  "url": "https://example.com/login",
  "title": "Sign in",
  "refMap": {
    "e12":  { "selector": "form#login > input[name=email]",  "tag": "input", "type": "email",   "role": "input" },
    "e15":  { "selector": "form#login > input[name=pwd]",    "tag": "input", "type": "password","role": "input" },
    "e18":  { "selector": "button[type=submit]",             "tag": "button","text": "Continue","role": "button" }
  }
}
```

- 模型只能拿到 `e12 / e15 / e18`，**拿不到**真实 selector。
- `runtime/browser/snapshot/RefResolver` 内部维护 `tabId → refMap` 映射；用户点击元素后（用户事件）也写入 refMap（避免 AI 与 UI 互踢）。

### 3.3 Screenshot 返回

`ToolResult.imageAttachments[0] = ToolImageRef(uri = "screenshots://wanxiang-browser/t1/2026-09-02T11-09-09.png", mime = "image/png", w = 1080, h = 2400)`。

UI 用 `Coil` 渲染缩略图，点击进入 `BrowserActionCard` 全屏预览。文件落到 `/data/data/<app>/cache/wanxiang-browser/screenshots/...`；**不外发**，符合"敏感数据不出本地"原则。

### 3.4 MCP Resources（外部 AI / IDE 可订阅）

```
browser://current-page      ── 当前 tab URL + 标题
browser://dom               ── 最近一次 snapshot 全量
browser://console           ── 最近 console 日志
browser://network           ── 已捕获网络请求
browser://tabs              ── 所有 tab 摘要
browser://storage           ── Cookie / Local / Session 摘要
```

---

## 4. ⚖️ 风险与审批矩阵

| 工具前缀 | riskLevel | REQUEST 模式审批 | ASSISTED 模式审批 | FULL_ACCESS 自动 |
| --- | --- | --- | --- | --- |
| `*snapshot` / `*screenshot` / `*current_url` / `*list_tabs` / `*page_source` / `console.*` / `network.list` / `page.*_info` / `*_get`（只读） | LOW | 否 | 否 | 否 |
| `*open` / `*navigate` / `*back` / `*forward` / `*refresh` / `*close_tab` | MEDIUM | **是** | 是 | 否 |
| `*click` / `*type` / `*press` / `*scroll` / `*network_set_cache_disabled` / `*local_set` / `*local_delete` / `*cookies_set` | HIGH | **是** | 是 | 否 |
| `*evaluate`（页内 JS 注入）/ `*cookies_get` / `*local_get_specific`（敏感字段） | CRITICAL | **是** + 二级确认 | 是 | **是**（仍建议二次确认） |
| `*file.delete` / `*file.unzip_to_system` | HIGH/CRITICAL | **是** | 是 | 否 |

实现落点：走现有 `McpManager.executeTool` + 现有 `AgentApprovalRequestEntity`；额外在 `harness/mcp/server/McpAuth` 注入工具级 grant 表（与"用户授权过的 tool 才返回"叠加）。

---

## 5. ⚙️ 核心调用时序

### 5.1 Agent 主动调用

```
ChatScreen
  └─► ChatViewModel.send(prompt)
        └─► HarnessLoop.send()
              ├─► ProviderClient 拉模型（tool list 含 mcp__browser__*）
              └─► 模型返回 tool_call { name = "mcp__browser__<server>__<hash>", args = {action, ...} }
                    └─► McpManager.executeTool(fullToolName, args)
                          └─► McpHttpTransport.execute(builtinServer, tool, args)
                                └─► POST http://127.0.0.1:8787/mcp   (in-process)
                                      └─► McpInProcessServer → McpToolDispatcher
                                            └─► BrowserMcpTools.dispatch(tool, args)
                                                  └─► BrowserRegistry.getDefault().<engine>.<action>(args)
                                                        └─► AndroidInAppBrowser / NetworkInterceptor / …
                          └─► 返回 (ok, output + image_ref)
                    └─► 回到 HarnessLoop：下一个 tool round 或产出 AssistantText
```

### 5.2 用户手动操作（Co-browsing）

```
BrowserScreen
  └─► BrowserViewModel.onUrlBarCommit(text)
        └─► AndroidInAppBrowser.loadUrl(text)
              └─► WebViewClient.onPageFinished
                    ├─► 运行 ref 提取脚本
                    ├─► SnapshotBuilder → 推给 BrowserEventBus
                    └─► harness 端通过 resource 订阅（如果有）拿到下一次 tool 调用的 refMap

用户点击
  └─► AndroidInAppBrowser.onUserInteraction
        └─► 暂停 harness 接管（coBrowsingDisabled=true 时，ref 仍然累积但不再写入）
```

### 5.3 外接桌面 AI

```
桌面 Claude/Cursor
  └─► mcpServers.webreverse.url = "http://<Android-IP>:8787/mcp"
       (或 adb reverse tcp:8787 tcp:8787 后用 http://127.0.0.1:8787/mcp)
        └─► McpHttpTransport（同款 JSON-RPC 2.0 / Streamable HTTP）
              └─► Bearer Token 由 AgentPreferences 提供
                    └─► McpInProcessServer → BrowserTools.dispatch
```

---

## 6. 🔐 安全策略

| 层 | 策略 |
| --- | --- |
| SecretRedactor | 所有 cookie / Authorization / password / set-cookie / token 自动替换为 `[REDACTED_*]`（复用 `core:security` 已实现） |
| 风险等级 | LOW/MEDIUM/HIGH/CRITICAL 四档；HIGH+ 触发 ApprovalRequestDialog |
| 外接开关 | `browserPreferences.allowRemoteConnect` 默认 `false`；开启后端口仅绑 `0.0.0.0`，同时要求 User 已设置 `browserRemoteToken`（≥32 字节 base64）|
| 敏感域白名单 | 主域列表（`localhost`、`127.0.0.1`、用户配置的允许域）允许 `cleartext`；其它强制 HTTPS（Hilt Qualifier + NetworkSecurityConfig） |
| 文件沙箱 | `*file.*` 工具复用现有 `WorkspaceFileService` 路径校验，禁止 `/proc /sys /dev /system /root` |
| Token | `mcp__browser__*` 内置 server 默认走 loopback 不需要 token；外接强制要求 token；token 通过 SecureRandom 生成、SecretRedactor 加密保存 |

---

## 7. 📁 文件索引（落盘清单）

### 新增模块与目录

```
core/browser/src/main/java/top/wanxiang/app/core/browser/
    BrowserFamily.kt
    BrowserRisk.kt
    BrowserCapability.kt
    BrowserDescriptor.kt
    SnapshotRef.kt
    PageSnapshot.kt
    BrowserSelectionPolicy.kt          (Pure Kotlin, 单测覆盖)
    BrowserPreferences.kt              (Pure Kotlin 配置数据类)

core/browser/src/test/java/top/wanxiang/app/core/browser/
    BrowserSelectionPolicyTest.kt
    SnapshotRefTest.kt

runtime/browser/src/main/java/top/wanxiang/app/runtime/browser/
    BrowserRegistry.kt                (interface)
    BrowserRegistryImpl.kt            (@Singleton)
    BrowserSessionToken.kt            (按 agent session 绑定)
    BrowserEventBus.kt                (snapshot/url/title/console/network 事件)
    engine/BrowserEngine.kt           (interface)
    engine/AndroidInAppBrowserEngine.kt
    engine/AndroidWebViewFactory.kt
    engine/WebViewTabPool.kt
    snapshot/SnapshotBuilder.kt       (evaluateJavascript + AccessibilityNode 简化)
    snapshot/RefResolver.kt           (ref → selector 表)
    screenshot/ScreenshotRecorder.kt  (PixelCopy 异步截图 → internalCache)
    network/NetworkInterceptor.kt     (shouldInterceptRequest 缓存层)
    storage/StorageController.kt      (CookieManager + WebStorage)
    js/JsEvaluator.kt                 (evaluateJavascript 包装 + 超时)
    secret/SecretRedactingInterceptor.kt (页面 header / body 脱敏落库)
    tools/BrowserMcpTools.kt          (注册全部 mcp__browser__* tool handler)
    tools/BrowserMcpResources.kt      (browser://* resource handler)
    capabilities/BrowserCapabilities.kt (engine.family → capability set)
    di/BrowserModule.kt               (Hilt bindings)

runtime/browser/src/main/AndroidManifest.xml

feature/browser/src/main/java/top/wanxiang/app/ui/browser/
    BrowserScreen.kt
    BrowserViewModel.kt
    BrowserTopBar.kt                  (URL Bar / Forward / Back / Refresh / Co-browsing toggle)
    BrowserTabBar.kt                  (横滚 tab 切换)
    BrowserCoBrowsingPill.kt          ("AI 已接管" / "你正在接管")
    BrowserActionCard.kt              (供 ChatToolCards 复用的缩略图卡)
    snapshot/SnapshotSheet.kt         (全屏 snapshot 预览)
    BrowserNavRoute.kt

harness/src/main/java/top/wanxiang/app/harness/mcp/server/
    McpInProcessServer.kt             (Ktor 起服，POST /mcp/sse + Bearer auth)
    McpAuthFilter.kt
    McpToolDispatcher.kt              (mcp__<server>__<tool> → 路由到 BrowserMcpTools)
    McpResourceDispatcher.kt
    McpServerRuntime.kt               (singleton, 启停)
    di/McpServerModule.kt

harness/src/main/java/top/wanxiang/app/harness/browser/
    BrowserMcpBootstrap.kt            (McpManager.bootstrapBuiltinBrowser())
```

### 修改文件

```
settings.gradle.kts                                       ── 加 :core:browser / :runtime:browser / :feature:browser
gradle/libs.versions.toml                                 ── 加 ktor-server-cio / ktor-server-sse / androidx-webkit
core/model/.../HarnessMessage.kt                          ── ToolResult 加 imageAttachments + ToolImageRef
core/model/.../McpModels.kt                               ── McpServerConfig 新增 builtinRisk / bootstrapOrder（如未对齐现有 isBuiltin）
core/datastore/.../PreferenceFacades.kt                   ── 新增 BrowserPreferences 分面
harness/.../McpManager.kt                                 ── init { ... } 阶段调用 McpServerRuntime.bootstrap()
app/build.gradle.kts                                      ── implementation(project(":runtime:browser"))
runtime/build.gradle.kts                                  ── implementation(project(":core:browser"))
feature/navigation/.../WanXiangNavHost.kt                    ── 注册 BrowserRoute
feature/home/.../HomeScreen.kt                            ── 入口 RuntimeCard
feature/chat/.../ChatToolCards.kt                         ── 复用 BrowserActionCard
docs/AI_NAVIGATION.md, docs/ARCHITECTURE.md, docs/FILE_INDEX.md ── 同步新增模块
```

---

## 8. 🧪 测试策略

| 层级 | 覆盖目标 | 工具 |
| --- | --- | --- |
| **Pure 模型层（必过）** | `BrowserSelectionPolicy` / `SnapshotRef` / `ToolImageRef` 序列化 | JUnit4，纯 JVM |
| **MCP 分发（必过）** | `McpToolDispatcher` 路由、`McpAuthFilter` 拒绝无 token | JUnit4 + Mockk |
| **WebView（Robolectric）** | `WebViewTabPool` 复用 / `RefResolver` 解析 | Robolectric 4.16 |
| **UI（Compose 截图）** | `BrowserScreen` 首屏布局 | Compose UI test |
| **端到端（手工）** | 主流程：Agent → MCP server → WebView → screenshot → 回灌 | adb + 手工 case |

关键单测文件已列入 §7，需与代码一并提交。

---

## 9. 🚀 MVP 切片（2 周）

| Step | 目标 | PR |
| --- | --- | --- |
| 1 | `:core:browser` 模型层 + 单测 | #1 |
| 2 | `:core:datastore` BrowserPreferences 分面 | #2 |
| 3 | `:runtime/browser` 骨架（BrowserRegistry、InApp Engine、SnapShot/Screenshot 简化版） | #3 |
| 4 | `harness/mcp/server` Ktor 起服 + Bootstrap | #4 |
| 5 | `:feature/browser` Compose 主屏（URL Bar / 单 Tab / Co-browsing pill）| #5 |
| 6 | NavHost + HomeScreen 入口 + ChatToolCards 接入 | #6 |
| 7 | 文档同步 + architectureCheck | #7 |

每个 PR 后跑 `architectureCheck` 与既有 `:app:preBuild`。

---

## 9.5 🎣 注入式 Hook 引擎与 CDP 调试（阶段 1 + 2，已落地）

> 2026-09 起在 MVP 之上扩展的网页逆向能力，对标 WebReverse-MCP 的 hook/断点特性。

### 9.5.1 注入式 Hook 引擎（阶段 1，allowHooks 门禁）

- **架构**：`hook/` 包 + assets `hook_runtime.js`。规则存 `HookRuleStore`（线程安全），经 `HookInstaller` 以 document-start（`WebViewCompat.addDocumentStartJavaScript`，WebView 105+；古董版本降级 onPageStarted 补种）注入页面；页面侧改写 fetch/XHR/函数/属性，经 `WanxiangHookBridge`（addJavascriptInterface）回吐事件到 `HookEventPipeline` → `BrowserEventBus`。
- **动作集**：网络类规则支持 `log / block / redirect / mock / modify_headers` + `captureBody`；body 存 `NetworkBodyStore`（LRU，总字节预算）。
- **MCP 工具**：`browser.hook_create / hook_list / hook_remove / hook_reset / hook_hits / inject_script / network_detail`。
- **降级链**：document-start 不可用 → onPageStarted 注入 → onPageFinished 幂等校验补种。

### 9.5.2 CDP 断点与 Worker 级拦截（阶段 2，allowCdp 门禁）

- **传输**：`CdpTransport`（LocalSocket 连 `webview_devtools_remote_<pid>`）→ 自实现 WS 握手与帧编解码（`WsFrameCodec`）→ `CdpSession`（命令关联/事件分发/断连清理）。
- **生命周期**：`CdpManager` 管理 attach（上限 + DevTools socket 引用计数，`setWebContentsDebuggingEnabled` 按需开关）；`CdpTargetMatcher` 通过 `window.__wanxiangTabId` 标记把 MCP tab 匹配到 CDP target；`CdpTabConnection` 持单 tab 连接 + Worker 子会话（`Target.setAutoAttach` flatten）。
- **断点**：`CdpDebugController` —— set/remove/list 断点、暂停状态、单步（over/into/out）、暂停帧求值（`debug_eval`，可读写局部变量）、作用域读取。断点在 detach 后重 attach 自动重放。
- **Worker 级拦截**：`CdpFetchInterceptor` 用 `Fetch.requestPaused` 做引擎级网络改写，与注入式规则共享 `HookRuleStore` 决策（`CdpFetchDecision`），覆盖 Worker/Service Worker 与注入盲区子资源。
- **MCP 工具**：`browser.debug_attach / detach / set_breakpoint / remove_breakpoint / list_breakpoints / resume / step / state / eval / scope / status` 共 11 个。

### 9.5.3 门禁与安全

- `allowHooks` / `allowCdp` 为**独立 DataStore 偏好**（默认 false），设置页「MCP 插件与协议生态 → 浏览器 MCP 安全门禁」四开关（远程连接/JS 执行/Hook/CDP）；池级开关，改后需重启应用。
- attach 期间页面暂停会冻结页内工具（evaluate/snapshot/click 拒绝），detach 前自动 resume 兜底。
- **已知容错**：`tokenOf` 对模型丢 `t:` 前缀的 tab id 做后缀归一匹配；`sanitizeUrl` 剥离模型偶发的反引号包裹。

---

## 10. 📜 决策记录（ADR 摘要）

- **ADR-001**：工具暴露走 MCP，**不**走原生工具（`HarnessTool.BROWSER`）。理由：与 harness 既有 `McpManager` 流水线零冲突；未来工具集可平滑扩到 200+。
- **ADR-002**：MCP server 跑在 WanXiang 自己的 Android 进程内（loopback），使用 Ktor 起服。理由：无需 fork WebReverse-MCP；与现有 `McpHttpTransport` 同协议同形态；外接零开发。
- **ADR-003**：内建 server 默认 `isBuiltin=true` 且 `isEnabled=true`，但配置写入 `McpServerRepository`。理由：与用户对 MCP server 的"启用/禁用"心智模型一致；用户关闭时不影响其它 MCP server。
- **ADR-004**：ref 不暴露真实 selector；用户和 AI 操作共用一张 `refMap`，ref 在 tab 重建时归零。理由：防止 prompt 里出现内部 path / 减少幻觉、同时支持 co-browsing。
- **ADR-005**：第一版 MVP **不**实现 CDP hub、断点、hook、JSVMP/WASM、Evidence Graph。理由：WanXiang 主要用例是"AI 打开网页、读 DOM、读 API、点击登录"，不必要求 web 逆向工程能力。**（2026-09 演进：阶段 1 注入式 Hook 引擎与阶段 2 CDP 断点/Worker 级拦截已落地，见 §9.5；JSVMP/WASM 与 Evidence Graph 仍未实现）**

---

## 11. 🔗 关联文档

- 架构总览：[`ARCHITECTURE.md`](ARCHITECTURE.md)
- AI 语义导航：[`AI_NAVIGATION.md`](AI_NAVIGATION.md)
- 数据流与时序：[`EXECUTION_TRACES.md`](EXECUTION_TRACES.md)
- 架构铁律：[`ARCHITECTURE_RULES.md`](ARCHITECTURE_RULES.md)
- 文件索引：[`FILE_INDEX.md`](FILE_INDEX.md)
- MCP 既有实现：`harness/src/main/java/top/wanxiang/app/harness/mcp/`
- 工具执行器：`harness/src/main/java/top/wanxiang/app/harness/ToolExecutor.kt`
- 审批引擎：`harness/src/main/java/top/wanxiang/app/harness/ApprovalPolicyEngine.kt`
