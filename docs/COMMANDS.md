# 🛠️ 万象 (WanXiang) — 常用开发与自动化命令速查 (Runbook & Commands)

---

## 1. 构建环境要求 (Prerequisites)

- **JDK 环境变量设置（Windows PowerShell）**：
  必须通过设置 `JAVA_HOME` 指向 Android Studio 自带的 JBR（支持 Java 25 / 17+）执行 Gradle 任务：
  ```powershell
  $env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"
  # 或自定义安装路径（例如 F:\AndroidDev\jdk\jdk-25.0.3+9）
  ```

---

## 2. 核心构建与测试命令 (Build & Test)

```powershell
# 1. 运行所有单元测试（全库回归）
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat test --console=plain

# 2. 运行单模块单元测试
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat :feature:settings:testDebugUnitTest --console=plain

# 3. 仅编译 Kotlin 代码（快速语法与类型检查）
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat compileDebugKotlin --console=plain

# 4. 单模块快速编译验证
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat :harness:compileDebugKotlin --console=plain -q

# 5. 编译并打包 Debug APK
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat assembleDebug --console=plain

# 6. 验证架构依赖边界（防止非法跨模块依赖）
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat architectureCheck --console=plain

# 7. 仅验证 Harness 内置工具契约与执行策略
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"; .\gradlew.bat :harness:testDebugUnitTest --tests "top.wanxiang.app.harness.*" --console=plain
```

---

## 3. 设备部署与实时日志调试 (Deploy & Debug)

```powershell
# 1. 安装 Debug APK 到已连接的真机或模拟器
adb install -r app/build/outputs/apk/debug/wanxiang-v0.12.0-debug.apk

# 2. 启动万象主入口 Activity
adb shell am start -n top.wanxiang.app/.MainActivity

# 3. 实时过滤万象运行时与智能体核心日志
adb logcat -s WanXiang:V HarnessLoop:V ProotProcess:V
```
