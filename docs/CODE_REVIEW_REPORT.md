# 万象 (WanXiang) 项目全局代码审查报告

> 审查时间：2026-08-31
> 审查范围：全模块（app / core / runtime / tools / harness / feature）
> 代码规模：约 330+ Kotlin 源文件 + JNI C 代码

---

## 摘要

| 严重程度 | 数量 | 说明 |
| :--- | :---: | :--- |
| 🔴 严重 (Critical) | 2 | 数据丢失风险、安全边界突破 |
| 🟠 高 (High) | 5 | 死锁、并发崩溃、资源泄漏 |
| 🟡 中 (Medium) | 7 | 功能缺陷、性能、健壮性 |
| 🟢 低 (Low) | 6 | 代码质量、可维护性 |

---

### C2. HostBridge 路径穿越：沙箱可访问宿主任意文件路径

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/bridge/HostBridge.kt:305-315`

**问题**：
```kotlin
private fun resolveSandboxPath(sandboxPath: String): String {
    return when {
        sandboxPath.startsWith("/sdcard/") -> "/storage/emulated/0/${...}"
        sandboxPath == "/sdcard" -> "/storage/emulated/0"
        sandboxPath.startsWith("/storage/emulated/0/") -> sandboxPath
        else -> sandboxPath  // ← 原样返回任意宿主绝对路径
    }
}
```

`handleInstallApk` 接收沙箱传入的路径，`resolveSandboxPath` 对非标准前缀的路径**原样返回**。这意味着沙箱内进程可以传入：
- `/data/data/top.wanxiang.app/databases/wanxiang.db`（应用私有数据库）
- `/data/app/.../base.apk`（其他应用的 APK）
- `/system/...`（系统分区）

虽然有 Bearer Token 认证，但 token 写入 `/opt/wanxiang/.bridge-key`，沙箱内 root 用户可直接读取。

**影响**：沙箱逃逸，可读取/安装宿主任意路径的 APK 文件。

**修复建议**：
```kotlin
private fun resolveSandboxPath(sandboxPath: String): String? {
    return when {
        sandboxPath.startsWith("/sdcard/") -> "/storage/emulated/0/${sandboxPath.removePrefix("/sdcard/")}"
        sandboxPath.startsWith("/storage/emulated/0/") -> sandboxPath
        else -> null  // 拒绝其他路径
    }
}
```
在 `handleInstallApk` 中对 null 返回 400 错误。同时考虑将 token 改为每次启动生成且不写入沙箱可读取位置。

---

## 🟠 高优先级问题 (High)

### H1. `runBlocking` 在 suspend 函数中可能导致死锁

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/LinuxRuntimeImpl.kt:783`

**问题**：
```kotlin
private fun configureRootfs(distroId: String = "ubuntu") {
    // ...
    runCatching {
        kotlinx.coroutines.runBlocking {  // ← 在普通函数中阻塞等待协程
            assetSynchronizer.syncAssetsToDistro(distroId)
        }
    }
}
```

`configureRootfs` 被 `initialize()`、`installDistro()`、`resetSandbox()` 等 suspend 函数调用，这些函数运行在 `Dispatchers.IO` 上下文中。`runBlocking` 会阻塞当前线程，如果 `syncAssetsToDistro` 需要切换到被阻塞的同一线程池（如 `Dispatchers.IO` 线程耗尽时），会导致**死锁**。

**修复建议**：将 `configureRootfs` 改为 suspend 函数，直接调用 `assetSynchronizer.syncAssetsToDistro(distroId)`，去掉 `runBlocking`。

---

### H2. `ProcessRegistry` 并发访问 `LinkedHashMap` 导致崩溃

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/shell/ProcessRegistry.kt:159`

**问题**：
```kotlin
private val processes = LinkedHashMap<String, ManagedProcess>()  // 非线程安全

override fun list(): List<ManagedProcess> = processes.values.toList()  // ← 无锁访问
```

`start()`、`stop()`、`stopAll()`、`cleanupDeadProcesses()` 都在 `mutex.withLock` 中修改 `processes`，但 `list()` 直接无锁读取。在多线程环境下（UI 线程调用 `listBackground()`，IO 线程调用 `stopAll()`），可能抛出 `ConcurrentModificationException`。

**修复建议**：
```kotlin
override fun list(): List<ManagedProcess> = synchronized(processes) {
    processes.values.toList()
}
```
或改用 `ConcurrentHashMap`（但需注意有序性需求）。

---

### H3. `ProcessRegistry` 空 catch 块吞掉所有异常

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/shell/ProcessRegistry.kt:122`

**问题**：
```kotlin
scope.launch {
    try {
        session.output.collect { terminalOutput -> ... }
    } catch (_: Exception) {  // ← 静默吞掉所有异常
    } finally { ... }
}
```

捕获 `Exception` 后什么都不做，包括：
- `OutOfMemoryError`（继承 Error，不被 Exception 捕获，但其他严重错误如 `IOException` 被吞）
- 协程取消异常（`CancellationException` 应重新抛出）
- 日志收集器崩溃，导致后续日志无法收集但无任何告警

**修复建议**：
```kotlin
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (e: Exception) {
    logger.w("Process log collector failed for $id", e)
}
```

---

### H4. `NativePtySession.close()` 存在竞态条件

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/pty/NativePtySession.kt:106-119`

**问题**：
```kotlin
override suspend fun close() = withContext(Dispatchers.IO) {
    if (closed.compareAndSet(false, true)) {
        readerJob.cancel()        // 异步取消，不等待
        sessionScope.cancel()     // 异步取消
        NativePty.killPid(childPid, 1)
        NativePty.killPid(childPid, 9)
        NativePty.waitPid(childPid)
        NativePty.closeFd(masterFd)  // ← readerJob 可能仍在 readFd
        outputChannel.close()
    }
}
```

`readerJob.cancel()` 后没有 `join()`，reader 协程可能仍在 `NativePty.readFd(masterFd)` 中阻塞。此时 `closeFd(masterFd)` 关闭文件描述符，如果另一个线程/协程打开新文件复用了该 fd 编号，reader 会读到错误数据或写入已关闭的 channel。

**修复建议**：
```kotlin
readerJob.cancel()
readerJob.join()  // 等待 reader 退出
NativePty.closeFd(masterFd)
```

---

### H5. `WorkspaceFileAccess` 写入时 TOCTOU 符号链接攻击

**位置**：`harness/src/main/java/top/wanxiang/app/harness/WorkspaceFileAccess.kt:80-99`

**问题**：
```kotlin
suspend fun write(path: String, content: String): AppResult<Unit> {
    val file = resolveWritable(path)  // ① 检查路径在工作区内
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
    temporary.writeText(content, Charsets.UTF_8)
    if (!temporary.renameTo(file)) {  // ② 此时 file 可能已被替换为符号链接
        temporary.copyTo(file, overwrite = true)  // ← 跟随符号链接写出工作区
        temporary.delete()
    }
}
```

`resolveWritable` 检查 canonical path 在工作区内，但在 `renameTo`/`copyTo` 之前，如果攻击者（或沙箱内进程）将目标文件替换为指向工作区外的符号链接，`copyTo(file, overwrite = true)` 会**跟随符号链接**写入任意文件。

**修复建议**：写入前重新检查 `file.canonicalFile` 是否仍在工作区内；或使用 `O_NOFOLLOW` 语义（Java NIO `LinkOption.NOFOLLOW_LINKS`）。

---

## 🟡 中优先级问题 (Medium)

### M1. `SecretManager.decrypt()` 返回类型语义不一致

**位置**：`core/security/src/main/java/top/wanxiang/app/core/security/SecretManager.kt:25-33`

**问题**：
```kotlin
fun decrypt(value: String): String? = runCatching {
    if (value.isEmpty()) return ""  // ← 空串返回 "" 而非 null
    // ...
}.getOrNull()  // ← 失败返回 null
```

函数声明返回 `String?`，但空字符串输入返回 `""`（非 null），解密失败返回 `null`。调用方无法区分"输入为空"和"解密成功且结果为空串"。`SettingsDataStore.workshopKeystores` 中 `WorkshopKeystoreCodec.decode(secretManager.decrypt(ciphertext))` 收到 null 时可能 NPE。

**修复建议**：统一语义——空串返回 `null`，或改为返回 `Result<String>`。

---

### M2. `HostBridge` HTTP 请求解析缺少长度限制

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/bridge/HostBridge.kt:140-174`

**问题**：
- `reader.readLine()` 读取请求行和 header 行无长度限制
- 恶意客户端可发送 100MB 的单行请求头，导致 OOM
- `Content-Length` 上限 4MB，但 header 本身无限制

**修复建议**：使用 `readLine()` 时累计已读字节数，超过 8KB 即断开连接。

---

### M3. MCP 工具发现超时过短，沙箱冷启动时频繁失败

**位置**：`harness/src/main/java/top/wanxiang/app/harness/mcp/McpManager.kt:136`

**问题**：
```kotlin
const val DISCOVERY_TIMEOUT_MS = 4_000L  // 4 秒
```

MCP STDIO 传输需要在沙箱内启动进程（可能涉及 PRoot 启动、Python 解释器加载、依赖导入），4 秒在低端设备上经常不够。超时后该轮对话不注入 MCP 工具，用户体验为"工具时有时无"。

**修复建议**：
- 首次发现延长到 15-30 秒
- 缓存发现结果（已有 cache 机制），后续轮次用缓存
- 后台异步刷新缓存，不阻塞对话

---

### M4. `ToolExecutor` 使用字段注入，初始化顺序不安全

**位置**：`harness/src/main/java/top/wanxiang/app/harness/ToolExecutor.kt:65-66`

**问题**：
```kotlin
@Inject
lateinit var settingsDataStore: AgentPreferences
```

构造函数有 18 个可选参数（大多为 `? = null`），但 `settingsDataStore` 用字段注入。如果在构造函数执行期间（或某个可选参数的初始化 lambda 中）访问 `settingsDataStore`，会抛出 `UninitializedPropertyAccessException`。

**修复建议**：将 `settingsDataStore` 移到构造函数参数中，统一使用构造函数注入。

---

### M5. `HttpClientProvider` 缺少连接池和拦截器配置

**位置**：`core/network/src/main/java/top/wanxiang/app/core/network/HttpClientProvider.kt:13-17`

**问题**：
```kotlin
fun create(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
```

- 未配置连接池（默认 5 个空闲连接，5 分钟存活，对高并发场景可能不足）
- 未添加 HTTP 日志拦截器（调试困难）
- 未配置重试策略（`retryOnConnectionFailure` 默认为 true，但无自定义退避）
- 单一 client 同时用于 LLM API（长连接流式）和文件下载（大流量），互相影响

**修复建议**：为 LLM API 和文件下载分别配置独立的 OkHttpClient 实例；添加 `ConnectionPool` 调优。

---

### M6. `ProotCommandBuilder.build()` 有副作用（创建目录）

**位置**：`runtime/src/main/java/top/wanxiang/app/runtime/proot/ProotCommandBuilder.kt:62`

**问题**：
```kotlin
attachmentsDir.mkdirs()  // ← 在"构建命令"的纯函数中创建目录
```

`build()` 和 `buildInteractive()` 被设计为纯命令构建函数，但内部调用 `mkdirs()` 产生副作用。在单元测试中调用 `build()` 会意外创建目录；在高频调用场景下重复 `mkdirs()` 有性能开销。

**修复建议**：移除 `mkdirs()`，由调用方（`LinuxRuntimeImpl.initialize()`）在初始化时一次性创建目录。

---

### M7. `ChatViewModel` 重复 import 和未使用的 import

**位置**：`feature/chat/src/main/java/top/wanxiang/app/ui/chat/ChatViewModel.kt:46,53,55,61`

**问题**：
- 第 46 行和第 53 行重复 `import kotlinx.coroutines.flow.asStateFlow`
- 第 55 行和第 61 行重复 `import kotlinx.coroutines.flow.distinctUntilChanged`
- `android.util.Log` 导入但可能未使用
- `android.widget.Toast` 导入但可能未使用

**影响**：代码整洁度，不影响运行，但说明缺乏 lint 检查。

**修复建议**：运行 `./gradlew lint` 清理所有未使用和重复 import。

---

## 🟢 低优先级问题 (Low)

### L1. 硬编码 DNS 服务器，国内访问可能缓慢

**位置**：`runtime/.../LinuxRuntimeImpl.kt:1056`
```kotlin
resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
```
Cloudflare (1.1.1.1) 和 Google (8.8.8.8) 在国内网络环境下可能不稳定或被污染。建议根据用户网络环境智能选择，或提供国内 DNS（223.5.5.5 / 119.29.29.29）选项。

---

### L2. `HostBridge` 错误响应泄露内部路径

**位置**：`runtime/.../HostBridge.kt:235`
```kotlin
return HttpResponse(404, errorJson("APK file not found: $apkPath (resolved: $hostPath)"))
```
错误信息中包含 `resolved: $hostPath`，向调用方暴露了宿主文件系统路径结构。建议仅返回沙箱路径，内部路径记日志即可。

---

### L3. `stripSetuidBits` 遍历整个 rootfs，性能差且可能阻塞

**位置**：`runtime/.../LinuxRuntimeImpl.kt:979-997`

每次 `configureRootfs` 都遍历整个 rootfs（可能数万文件），在初始化时阻塞数秒到数十秒。建议：
- 首次初始化时执行一次
- 后续通过 `dpkg` hook（已有 `99wanxiang-strip-setuid`）增量处理
- 或在后台异步执行，不阻塞初始化流程

---

### L4. 缺少全局异常处理器和 ANR 防护

**位置**：`app/src/main/java/top/wanxiang/app/WanXiangApplication.kt`

项目有 `CrashReporter`（`core/common/.../CrashReporter.kt`），但未确认是否在 Application 中注册 `Thread.setDefaultUncaughtExceptionHandler`。前台服务中运行的 Agent 循环如果抛出未捕获异常，可能导致进程崩溃而无日志。

**修复建议**：确认 `WanXiangApplication.onCreate()` 中初始化了 `CrashReporter`，并为前台服务添加独立的异常处理。

---

### L5. `architectureCheck` 任务检查范围不完整

**位置**：`build.gradle.kts:13-67`

`architectureCheck` 检查了：
- model 层平台依赖
- feature 横向依赖
- DAO 直接导入

但未检查：
- `core:common` 是否依赖了 `core:database`（循环依赖风险）
- `runtime` / `harness` 是否依赖了 `feature`（反向依赖）
- `:core:model` 是否间接通过 `api` 暴露了 Android 依赖

建议扩展检查规则，或引入 `module-graph-assert` 插件做更严格的模块依赖校验。

---

### L6. 测试覆盖不足

根据 `KNOWN_ISSUES.md` 和代码扫描：
- `feature/*` 各屏幕基本无单元测试
- `HostBridge`（安全关键路径）无测试
- `SecretManager` 仅有 `SecretRedactorTest`，无加解密 round-trip 测试
- `NativePtySession` 无 JVM 层测试（JNI 部分需 instrumented test）

建议优先为安全关键路径（HostBridge、WorkspaceFileAccess、SecretManager）补充测试。

---

## 优化建议（非 Bug）

### O1. 引入统一的 `AppCoroutineScope` 注入

项目中多个单例各自创建 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`：
- `ProcessRegistryImpl.scope`
- `TerminalSessionManager.scope`
- `McpManager.scope`
- `HostBridge.bridgeScope`
- `HarnessLoop.loopScope`

建议通过 Hilt 提供一个全局 `@ApplicationScope` 的 `CoroutineScope`，统一管理生命周期，便于在 App 退出时统一取消。

---

### O2. `StateFlow` 热流泄漏风险

`TerminalSessionManager.activeHandle` 使用 `stateIn(scope, SharingStarted.Eagerly, null)`，`Eagerly` 模式下即使无收集者也保持活跃，可能导致 `combine` 上游持续运行。建议改用 `SharingStarted.WhileSubscribed(5_000)`。

---

### O3. 日志系统可优化

`AppLogger` 目前是自定义实现，建议：
- 接入 `Timber` 或 `Napier` 等成熟库
- 支持日志分级输出（release 版自动关闭 Debug 日志）
- 敏感信息脱敏应在 Logger 层统一处理，而非各调用点手动 `secretRedactor.redact()`

---

## 修复优先级建议

| 优先级 | 问题 | 预计工作量 |
| :--- | :--- | :--- |
| **立即修复** | C1 数据库迁移链断裂 | 2-4 小时（需确认 schema 变更） |
| **立即修复** | C2 HostBridge 路径穿越 | 30 分钟 |
| **本周修复** | H1 runBlocking 死锁 | 1 小时 |
| **本周修复** | H2 ProcessRegistry 并发 | 30 分钟 |
| **本周修复** | H3 空 catch 块 | 15 分钟 |
| **本周修复** | H4 PTY close 竞态 | 1 小时 |
| **本周修复** | H5 符号链接 TOCTOU | 2 小时 |
| **迭代优化** | M1-M7 中优先级 | 各 30 分钟-2 小时 |
| **长期改进** | L1-L6 + O1-O3 | 持续 |

---

## 审查方法说明

本次审查采用以下方法：
1. **文档先行**：完整阅读 `AI_NAVIGATION.md`、`ARCHITECTURE.md`、`ARCHITECTURE_RULES.md`、`FILE_INDEX.md`、`KNOWN_ISSUES.md`
2. **模式扫描**：使用 Grep 扫描 `!!`、空 catch、`GlobalScope`、`runBlocking`、`Thread.sleep`、`TODO/FIXME` 等常见问题模式
3. **关键文件精读**：深入阅读 `LinuxRuntimeImpl`、`ProcessRegistry`、`HostBridge`、`HarnessLoop`、`ProviderClient`、`ToolExecutor`、`WorkspaceFileAccess`、`NativePtySession`、`AppModule`、`DatabaseMigrations` 等核心文件
4. **架构合规校验**：对照 `ARCHITECTURE_RULES.md` 中的铁律逐项验证

**未覆盖范围**：
- 未运行编译/静态分析工具（`./gradlew lint`、`detekt`）
- 未执行单元测试
- 未在真机上验证运行时行为
- 未审查 `feature/*` 下所有 UI 代码的细节（仅抽样）
- 未审查 `tools/` 模块下所有安装器实现

建议后续运行 `./gradlew lint detekt` 补充静态分析结果。
