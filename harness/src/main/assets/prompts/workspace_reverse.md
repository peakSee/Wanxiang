### Android 逆向工程与代码审计操作规约
- 当前工程类型：Android APK 逆向；根目录：`{{WORKSPACE_PATH}}`；目录标记：`{{MARKER_TEXT}}`。
- 核心准则：原始 APK 和 `unpacked/` 是分析输入，先 read `apk-info.properties` 与 `REVERSE.md`，切勿覆盖原始 APK。必须遵循**「规划先行、结构为引、源码优先、毫秒检索、反射图谱」**标准化逆向流程。

#### 1. 强制规划先行 (Planning First)
- 逆向审计属于复杂多阶段任务，按 workflow 规则块在第一轮工具调用建立执行看板，严禁在无规划状态下盲目探索。建议阶段：
  1. 结构感知（读取 Manifest、识别主包名与核心入口组件）
  2. 源码获取（JADX 一键反编译 Java 源码或 DEX 提取）
  3. 核心特征与关键词检索（使用 `rg` 毫秒级精准搜索）
  4. 深入追踪核心类与调用链（Java/Smali 交叉比对与反射分析）
  5. 汇总审计结论与业务逻辑证明
- 每完成一个阶段，调用 `plan(action="advance")` 推进看板。

#### 2. APK 结构与架构感知 (Structure & Components)
- 优先读取 `apk-info.properties` 与 `AndroidManifest.xml`。
- 提取关键信息：`package` 包名、入口 Activity、关键 Service/Receiver、Application 自定义类（通常包含热修复/加壳/初始化逻辑）、权限声明。
- 遇到 R 类资源引用（如 `@string/login_sms_title` 或 `0x7f...`），使用 `rg` 检索 `res/values/strings.xml` 或 `R.smali` 映射。

#### 3. 源码反编译与工具链选择 (Decompilation Toolchain)
- **优先一键反编译为 Java 源码工程**：
  `jadx -d out/java <APK/DEX文件路径>`
  Java 源码具备完整控制流与类型结构，阅读效率比 Smali 高 10 倍以上！反编译后直接检索 `out/java/sources/` 下的代码。
- **资源与 Smali 修改**：使用 `apktool d <APK路径> -o unpacked/` 解包，修改后用 `apktool b` 回编译。
- **DEX 转 JAR**：若需使用其他 Java 字节码分析工具，可执行 `d2j-dex2jar <APK/DEX路径> -o out.jar`。

#### 4. 毫秒级极速检索与代码知识图谱 (Ripgrep & CodeGraph)
- 代码检索与调用链追踪统一按 code-navigation 规则块执行：优先 CodeGraph MCP（explore/callers/callees/impact），文本检索用 `rg`，严禁 `find | xargs grep` 遍历。
- 逆向场景常用检索范例：
  - 搜索类名/方法名/常量：`rg "quickPhoneLogin" out/java/`
  - 仅搜索 Java 文件：`rg -t java "isNewUser" out/java/`
  - 仅搜索 Smali 文件：`rg -g "*.smali" "invoke-static.*login" unpacked/`
  - 忽略大小写正则搜索：`rg -i "sms.*code" out/java/`

#### 5. 插件化与反射调用图谱分析 (Plugin & Reflection Architecture)
- 对于球球大作战等包含插件化/组件化架构或大量反射调用的 APK：
  - **反射特征识别**：检索 `Class.forName`、`getMethod`、`invoke`、`ReflectUtils`、`Reflect` 包装类；
  - **类名字面量追踪**：反射通常伴随全类名字符串，使用 `rg` 检索目标接口或类的全路径字符串（如 `"com.ztgame.mobileappsdk"`）；
  - **动态插件加载**：检查 `DexClassLoader`、`PathClassLoader`、Asset/Zip 释放路径或 `.so` 动态加载，追踪子 DEX 或插件 APK。

#### 6. 大文件与 Smali 精准分片阅读 (Targeted Method Reading)
- 大文件按 code-navigation 规则分片阅读，严禁盲目读取全文件。Smali 专属技巧：先 `rg -n '^\.method' Path/To/Class.smali` 提取方法签名大纲，定位起始行后用 `read(offset, limit)` 精准分页。

#### 7. 工作便签与线索持久化 (Scratchpad Tracking)
- 逆向过程中发现的关键类名、函数调用链、中间假说及反编译目录，第一时间通过 `scratchpad(action="save", key="findings", value=...)` 沉淀，防止长会话上下文丢失。

