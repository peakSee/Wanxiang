# Baseline Profile 启动优化指南

> 万象冷启动优化第二阶段：安装时预编译（AOT）热点方法，消除首次启动 JIT 现场编译开销。

## 原理

APK 内嵌 `assets/dexopt/baseline.prof`（由 `app/src/release/generated/baselineProfiles/` 编译打包）。
首次安装后 `androidx.profileinstaller` 在后台把它提交给 ART 预编译，覆盖：

- `Application.onCreate` → Hilt 图构建 → 首屏 Compose 组合的完整启动路径
- 首帧 LazyColumn 滚动路径

## 模块结构

| 路径 | 职责 |
| :--- | :--- |
| `baselineprofile/` | 生成者模块（`com.android.test` + macrobenchmark），含采集用例 |
| `baselineprofile/.../StartupBaselineProfile.kt` | 采集场景：冷启动 + 首页轻滑动 |
| `app/src/release/generated/baselineProfiles/` | 产物，**随版本库提交** |
| `app/build.gradle.kts` | 消费者接线：插件 + `baselineProfile(project(":baselineprofile"))` |

## 重新采集（App UI 或启动链路大改后）

前置：一台已连接、已解锁的设备/模拟器（真机或 AVD 均可）。

```powershell
$env:JAVA_HOME="F:\AndroidDev\jdk\jdk-25.0.3+9"
.\gradlew.bat :app:generateBaselineProfile --console=plain
```

成功标志：`app/src/release/generated/baselineProfiles/*.txt` 更新，测试 `1/1 completed (0 failed)`。

### 多设备并存时锁定目标

```powershell
$env:ANDROID_SERIAL = "emulator-5554"   # 或具体设备序列号
```

### 本机无设备时：手动起一个无头 AVD

```powershell
# 一次性创建（本仓库已有 android-37.1 google_apis_playstore x86_64 镜像时可直接用）
powershell -NoProfile -Command "Start-Process -FilePath \"$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe\" -ArgumentList '@<avd名>','-no-window','-no-audio','-no-boot-anim'"
adb -s emulator-5554 shell settings put secure user_setup_complete 1
adb -s emulator-5554 shell settings put global device_provisioned 1
```

x86_64 模拟器可以运行 arm64-only 应用（模拟器 37+ 自动二进制翻译），万象仅 arm64-v8a 不受影响。

## 已知坑位

1. **vivo 等国产 ROM 的 USB 安全加固**会以 `Calling from not trusted UID!` 拦截仪器化注入，
   表现为 `Failed to install split APK(s)` 或 instrumentation 进程秒死——这类设备无法用于采集，换模拟器。
2. 采集用的是 nonMinified 变体；若 release 主线程抛错（如 R8 后才暴露的正则问题），
   采集阶段就会以 `Process crashed` 失败。先修崩溃再采集。
3. 强杀 Gradle daemon 可能留下损坏的 KSP 缓存（报 `NoSuchFileException ... kspCaches\...\*_Factory.java`），
   删除对应模块的 `build/kspCaches` 重跑即可。

## 收益验证

```powershell
adb install -r app/build/outputs/apk/release/wanxiang-v0.12.0-release.apk
adb shell am start -W top.wanxiang.app/.MainActivity   # 对比 TotalTime
adb logcat -s WanXiangStartup                            # splash dismissed 耗时
```
