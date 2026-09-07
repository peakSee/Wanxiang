# Android 全套离线插件制作指南

本文档说明如何把 Android SDK、Build-Tools、ARM64 AAPT2、NDK、JDK、Gradle、ADB 和 Android-only Flutter SDK 全部放入一个 `.txplugin`，然后在无网络环境下导入和安装。本插件不包含 Web、iOS、Windows、macOS 或 Linux Desktop 构建资源；iOS 也不能在 Linux ARM64 PRoot 中构建。

## 0. 先理解当前实现

仓库里的 `android-suite` 目前是内置 `BuiltinPluginBundles`，由 `ToolManager.batchInstallComponents()` 驱动；它的默认脚本会下载 Android/Gradle/NDK/Flutter 资源。

本地 `.txplugin` 则走 `ToolRegistry -> ToolManager -> GenericRecipeInstaller`。因此要做完全离线版本，推荐先把 Android 全套做成一个单体本地插件，例如 `android-suite-offline`，由一个离线安装脚本一次性部署所有资源。不要直接把现有联网脚本原样塞进包里。

相关实现：

- [ToolManifest.kt](../core/model/src/main/java/top/wanxiang/app/core/model/ToolManifest.kt)
- [ToolRegistry.kt](../tools/src/main/java/top/wanxiang/app/core/tools/ToolRegistry.kt)
- [GenericRecipeInstaller.kt](../tools/src/main/java/top/wanxiang/app/runtime/tools/GenericRecipeInstaller.kt)
- [RuntimeAssetSynchronizer.kt](../runtime/src/main/java/top/wanxiang/app/runtime/scripts/RuntimeAssetSynchronizer.kt)

## 1. 推荐目录

```text
android-suite-offline/
├── manifest.json
└── payload/
    ├── archives/
    │   ├── jdk-17-linux-arm64.tar.xz
    │   ├── gradle-8.14.2-bin.zip
    │   ├── android-platform-34.zip
    │   ├── android-build-tools-35.0.0.zip
    │   ├── android-sdk-tools-static-aarch64.zip
    │   ├── android-ndk-r29-arm64.tar.xz
    │   ├── cmake-linux-arm64.tar.xz
    │   ├── ninja-linux-arm64.zip
    │   ├── adb-linux-arm64
    │   └── flutter-linux-arm64-android-only-slim.tar.gz
    ├── checksums/
    │   └── SHA256SUMS
    ├── licenses/
    │   └── android-sdk-license
    ├── scripts/
    │   ├── install-android-suite.sh
    │   └── uninstall-android-suite.sh
    └── config/
        ├── gradle.properties
        └── wanxiang-android-ndk.gradle
```

### 资源是否必须提供

| 资源 | Android 核心 | Flutter | 说明 |
|---|---:|---:|---|
| JDK 17 Linux ARM64 | 必须 | 必须 | 不要使用 x86_64 JDK。 |
| Android Platform 34 | 必须 | 必须 | 至少包含 `android.jar`。 |
| Build-Tools 35 | 必须 | 必须 | 至少包含 `d8.jar`、`aapt2`、`zipalign`、`apksigner`。 |
| ARM64 AAPT2/SDK tools | 必须 | 必须 | Google 常见 Build-Tools 原生程序通常是 x86_64，必须使用 ARM64 版本覆盖。 |
| NDK ARM64 | 必须 | 构建原生代码时必须 | 必须包含 `toolchains/llvm/.../clang`、`llvm-strip`。 |
| Gradle 8.14.2 | 必须 | 必须 | 建议使用固定版本。 |
| ADB ARM64 | 建议 | Android 调试需要 | 如果 RootFS 已有可不重复打包。 |
| CMake/Ninja | NDK 构建需要 | Flutter 原生插件常用 | 建议一并提供。 |
| Flutter ARM64 SDK | 可选 | 必须 | 使用已精简的 Linux ARM64 Android-only Flutter 包；不包含 Web/iOS/桌面资源。 |

如果要求“断网且全新 RootFS 也能安装”，还必须将 `adb`、CMake、Ninja、`sha256sum`、`tar`、`xz` 等运行工具一起提供，或者确认它们已经存在于 RootFS。不要在离线安装脚本里执行 `apt-get install`。ZIP 解压优先使用系统 `unzip`，没有 `unzip` 时自动回退到已部署 JDK 的 `jar xf`，因此不要求额外联网安装解压包。

## 2. 资源准备原则

### 2.1 所有资源必须是 Linux ARM64 可运行版本

宿主应用是 Android ARM64，但插件资源运行在 PRoot Linux 内。资源应满足：

```sh
file jdk/bin/java
readelf -h adb | grep -E 'Machine|AArch64'
```

结果应显示 `ARM aarch64` 或 `AArch64`。Android APK 内的 x86_64 二进制、Windows `.exe`、macOS Mach-O 文件都不能放进去。

### 2.2 归档不要在包内重复嵌套插件目录

正确：

```text
payload/archives/flutter-linux-arm64.tar.xz
```

不推荐：

```text
payload/archives/flutter-linux-arm64/flutter/bin/flutter
```

优先保存官方归档，安装脚本负责校验、解压和原子替换。

### 2.3 记录 SHA-256

PowerShell：

```powershell
Get-ChildItem .\payload\archives -File | Get-FileHash -Algorithm SHA256 |
  ForEach-Object { "{0}  {1}" -f $_.Hash.ToLower(), $_.Path.Substring((Get-Location).Path.Length + 1) } |
  Set-Content .\payload\checksums\SHA256SUMS
```

Linux：

```sh
sha256sum payload/archives/* > payload/checksums/SHA256SUMS
```

## 3. `manifest.json` 示例

```json
{
  "schemaVersion": 1,
  "id": "android-suite-offline",
  "name": "Android 全栈开发套件（离线版）",
  "description": "内置 JDK、Android SDK、Build-Tools、ARM64 AAPT2、NDK、Gradle、ADB、CMake、Ninja 和 Flutter 的离线开发环境",
  "version": "1.0.0",
  "latestVersion": "1.0.0",
  "enabled": true,
  "publisher": "Your Name",
  "category": "DEVELOPER",
  "launchType": "command",
  "architectures": ["ARM64"],
  "permissions": ["WORKSPACE_READ", "WORKSPACE_WRITE"],
  "updateStrategy": "REINSTALL",
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE",
  "installSteps": [
    "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/install-android-suite.sh\""
  ],
  "uninstallSteps": [
    "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/uninstall-android-suite.sh\""
  ],
  "launchCommand": "gradle --version",
  "verifyCommand": "test -x \"$WANXIANG_TOOL_DIR/bin/java\" && java -version && gradle --version && test -f /opt/android-sdk/platforms/android-34/android.jar && test -f /opt/gradle-8.14.2/lib/gradle-launcher-8.14.2.jar",
  "commandLinks": ["java", "javac", "gradle", "adb", "flutter", "dart"],
  "environment": {
    "ANDROID_HOME": "/opt/android-sdk",
    "ANDROID_SDK_ROOT": "/opt/android-sdk",
    "GRADLE_HOME": "/opt/gradle-8.14.2",
    "JAVA_HOME": "/opt/wanxiang/toolchains/android/jdk",
    "ANDROID_NDK_HOME": "/opt/wanxiang/toolchains/android/ndk"
  }
}
```

导入器会强制把本地插件改成 `source=LOCAL`、`offlineOnly=true`、`installMethod=LOCAL_PACKAGE`。`dependencies` 对离线插件应保持为空，不能依赖在线 Runtime 下载。

## 4. 离线安装脚本结构

不要直接使用现有 `app/src/main/assets/scripts/setup_android_core.sh` 和 `setup_flutter.sh`，因为它们包含网络下载逻辑。复制后新建：

```text
payload/scripts/install-android-suite.sh
```

推荐脚本结构：

```sh
#!/bin/sh
set -eu

PAYLOAD="${WANXIANG_PLUGIN_PAYLOAD:?missing WANXIANG_PLUGIN_PAYLOAD}"
ARCHIVES="$PAYLOAD/archives"
CHECKSUMS="$PAYLOAD/checksums/SHA256SUMS"
TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"
ANDROID_HOME="/opt/android-sdk"
TOOLCHAIN_ROOT="/opt/wanxiang/toolchains/android"
JDK_HOME="$TOOLCHAIN_ROOT/jdk"
NDK_HOME="$TOOLCHAIN_ROOT/ndk"
GRADLE_VERSION="8.14.2"

mkdir -p "$TOOL_DIR/bin" "$ANDROID_HOME" "$TOOLCHAIN_ROOT" /opt/wanxiang/bin

# 1. 校验所有内置归档，不联网、不调用 apt。
if [ -f "$CHECKSUMS" ]; then
    (cd "$PAYLOAD" && sha256sum -c checksums/SHA256SUMS)
fi

# 2. JDK：先解压到临时目录，验证后原子替换。
rm -rf "$JDK_HOME.staging"
mkdir -p "$JDK_HOME.staging"
tar -xJf "$ARCHIVES/jdk-17-linux-arm64.tar.xz" -C "$JDK_HOME.staging"
JDK_SOURCE=$(find "$JDK_HOME.staging" -type f -path '*/bin/java' -print -quit)
test -n "$JDK_SOURCE"
JDK_SOURCE=$(dirname "$(dirname "$JDK_SOURCE")")
rm -rf "$JDK_HOME"
mv "$JDK_SOURCE" "$JDK_HOME"
rm -rf "$JDK_HOME.staging"

# 3. Gradle。
rm -rf "/opt/gradle-$GRADLE_VERSION.staging"
mkdir -p "/opt/gradle-$GRADLE_VERSION.staging"
unzip -q "$ARCHIVES/gradle-$GRADLE_VERSION-bin.zip" -d "/opt/gradle-$GRADLE_VERSION.staging"
test -f "/opt/gradle-$GRADLE_VERSION.staging/gradle-$GRADLE_VERSION/lib/gradle-launcher-$GRADLE_VERSION.jar"
rm -rf "/opt/gradle-$GRADLE_VERSION"
mv "/opt/gradle-$GRADLE_VERSION.staging/gradle-$GRADLE_VERSION" "/opt/gradle-$GRADLE_VERSION"
rm -rf "/opt/gradle-$GRADLE_VERSION.staging"

# 4. Android Platform。
rm -rf /tmp/android-platform-staging
mkdir -p /tmp/android-platform-staging
unzip -q "$ARCHIVES/android-platform-34.zip" -d /tmp/android-platform-staging
PLATFORM_SOURCE=$(find /tmp/android-platform-staging -name android.jar -print -quit | xargs -r dirname)
test -n "$PLATFORM_SOURCE"
rm -rf "$ANDROID_HOME/platforms/android-34"
mkdir -p "$ANDROID_HOME/platforms"
mv "$PLATFORM_SOURCE" "$ANDROID_HOME/platforms/android-34"
rm -rf /tmp/android-platform-staging

# 5. Build-Tools + ARM64 原生工具。
rm -rf /tmp/android-build-tools-staging
mkdir -p /tmp/android-build-tools-staging
unzip -q "$ARCHIVES/android-build-tools-35.0.0.zip" -d /tmp/android-build-tools-staging
BUILD_SOURCE=$(find /tmp/android-build-tools-staging -name source.properties -print -quit | xargs -r dirname)
test -f "$BUILD_SOURCE/lib/d8.jar"
rm -rf "$ANDROID_HOME/build-tools/35.0.0"
mkdir -p "$ANDROID_HOME/build-tools"
mv "$BUILD_SOURCE" "$ANDROID_HOME/build-tools/35.0.0"
rm -rf /tmp/android-build-tools-staging

rm -rf /tmp/android-arm64-tools-staging
mkdir -p /tmp/android-arm64-tools-staging
unzip -q "$ARCHIVES/android-sdk-tools-static-aarch64.zip" -d /tmp/android-arm64-tools-staging
test -x /tmp/android-arm64-tools-staging/build-tools/aapt2
cp -a /tmp/android-arm64-tools-staging/build-tools/. "$ANDROID_HOME/build-tools/35.0.0/"
rm -rf /tmp/android-arm64-tools-staging

# 6. NDK / CMake / Ninja / ADB。根据实际归档结构调整目录名。
rm -rf "$NDK_HOME.staging"
mkdir -p "$NDK_HOME.staging"
tar -xJf "$ARCHIVES/android-ndk-r29-arm64.tar.xz" -C "$NDK_HOME.staging"
NDK_SOURCE=$(find "$NDK_HOME.staging" -name source.properties -print -quit | xargs -r dirname)
test -x "$NDK_SOURCE/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
rm -rf "$NDK_HOME"
mv "$NDK_SOURCE" "$NDK_HOME"
rm -rf "$NDK_HOME.staging"

mkdir -p "$TOOL_DIR/bin"
ln -sfn "$JDK_HOME/bin/java" "$TOOL_DIR/bin/java"
ln -sfn "$JDK_HOME/bin/javac" "$TOOL_DIR/bin/javac"
ln -sfn "/opt/gradle-$GRADLE_VERSION/bin/gradle" "$TOOL_DIR/bin/gradle"

if [ -f "$ARCHIVES/adb-linux-arm64" ]; then
    cp "$ARCHIVES/adb-linux-arm64" "$TOOL_DIR/bin/adb"
    chmod +x "$TOOL_DIR/bin/adb"
fi

# 7. 可选 Flutter。只有包内确实包含归档时才执行。
if [ -s "$ARCHIVES/flutter-linux-arm64.tar.xz" ]; then
    rm -rf /tmp/flutter-staging
    mkdir -p /tmp/flutter-staging
    tar -xJf "$ARCHIVES/flutter-linux-arm64.tar.xz" -C /tmp/flutter-staging
    FLUTTER_SOURCE=$(find /tmp/flutter-staging -type f -path '*/bin/flutter' -print -quit | xargs -r dirname | xargs -r dirname)
    test -x "$FLUTTER_SOURCE/bin/flutter"
    rm -rf /opt/flutter
    mv "$FLUTTER_SOURCE" /opt/flutter
    rm -rf /tmp/flutter-staging
    ln -sfn /opt/flutter/bin/flutter "$TOOL_DIR/bin/flutter"
    ln -sfn /opt/flutter/bin/dart "$TOOL_DIR/bin/dart"
fi

ln -sfn "$TOOL_DIR/bin/java" /opt/wanxiang/bin/java
ln -sfn "$TOOL_DIR/bin/javac" /opt/wanxiang/bin/javac
ln -sfn "$TOOL_DIR/bin/gradle" /opt/wanxiang/bin/gradle
[ -e "$TOOL_DIR/bin/adb" ] && ln -sfn "$TOOL_DIR/bin/adb" /opt/wanxiang/bin/adb || true
[ -e "$TOOL_DIR/bin/flutter" ] && ln -sfn "$TOOL_DIR/bin/flutter" /opt/wanxiang/bin/flutter || true
[ -e "$TOOL_DIR/bin/dart" ] && ln -sfn "$TOOL_DIR/bin/dart" /opt/wanxiang/bin/dart || true

mkdir -p /root/.gradle
cp "$PAYLOAD/config/gradle.properties" /root/.gradle/gradle.properties
mkdir -p /root/.gradle/init.d
cp "$PAYLOAD/config/wanxiang-android-ndk.gradle" /root/.gradle/init.d/wanxiang-android-ndk.gradle
printf '%s\n' 'android.builder.sdkDownload=false' >> /root/.gradle/gradle.properties

test -x "$TOOL_DIR/bin/java"
test -f "$ANDROID_HOME/platforms/android-34/android.jar"
test -f "/opt/gradle-$GRADLE_VERSION/lib/gradle-launcher-$GRADLE_VERSION.jar"
test -x "$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
```

这只是安装流程骨架，具体归档内层目录需要根据你下载的 JDK、NDK、Flutter 包实际结构调整。关键原则是：每一个 `curl`、`wget`、`git clone`、`apt-get` 都必须从离线脚本中删除。

## 5. 离线卸载脚本

`payload/scripts/uninstall-android-suite.sh`：

```sh
#!/bin/sh
set -eu

TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"
rm -f /opt/wanxiang/bin/java /opt/wanxiang/bin/javac /opt/wanxiang/bin/gradle
rm -f /opt/wanxiang/bin/adb /opt/wanxiang/bin/flutter /opt/wanxiang/bin/dart
rm -rf "$TOOL_DIR"
rm -rf /opt/android-sdk
rm -rf /opt/gradle-8.14.2
rm -rf /opt/wanxiang/toolchains/android
rm -rf /opt/flutter
rm -f /root/.gradle/init.d/wanxiang-android-ndk.gradle
```

如果希望保留用户项目和 Gradle 缓存，不要删除 `/root/.gradle/caches`，也不要删除 `$WANXIANG_TOOL_DATA`。

## 6. 打包

Windows PowerShell：

```powershell
$root = "D:\plugins\android-suite-offline"
Compress-Archive `
  -Path "$root\manifest.json", "$root\payload" `
  -DestinationPath "D:\plugins\android-suite-offline.txplugin" `
  -Force
```

Linux/macOS：

```sh
cd android-suite-offline
zip -r ../android-suite-offline.txplugin manifest.json payload
```

检查包内容：

```sh
unzip -l android-suite-offline.txplugin
```

根目录必须是：

```text
manifest.json
payload/archives/...
payload/checksums/SHA256SUMS
payload/scripts/...
```

不能多一层 `android-suite-offline/` 目录。

## 7. 导入和安装

1. 打开“设置 -> 插件与工具中心”。
2. 点击右上角导入按钮，选择 `android-suite-offline.txplugin`。
3. 导入成功后，Android 全套会显示为一个本地插件。
4. 点击安装。
5. 安装脚本首先校验内置资源，然后部署 JDK、SDK、Build-Tools、NDK、Gradle、ADB 和 Flutter。
6. 检查 `verifyCommand` 通过后，再用终端验证 `java`、`gradle`、`adb`、`flutter`。

安装过程不应访问网络。如果日志出现 `curl`、`wget`、`apt-get update`、`git clone`，说明脚本仍有联网分支，不能称为完全离线插件。

## 8. 体积限制和实际建议

当前本地导入器对 ZIP 解压后的总大小限制为 8 GiB。Android + NDK + Flutter 全套仍可能接近这个上限；打包前必须统计资源体积：

```powershell
Get-ChildItem .\payload -Recurse -File | Measure-Object Length -Sum
```

如果超过 8 GiB，当前实现不能用一个 `.txplugin` 导入，需要后续增加分卷插件或共享资源包能力。资源必须保证应用有足够的内部存储空间，不能依赖 `/sdcard` 直接执行。

## 9. 最终检查

```text
[ ] 所有 JDK/NDK/Flutter/ADB/SDK 原生程序都是 Linux ARM64
[ ] payload/archives 中包含所有离线归档
[ ] payload/checksums/SHA256SUMS 与实际文件一致
[ ] 安装脚本不包含 curl、wget、git clone、apt-get、在线 Maven/Google URL
[ ] Android Platform、Build-Tools、AAPT2、NDK、Gradle 均有安装后检查
[ ] 断网情况下可以完成全新安装
[ ] 重复安装不会损坏已有环境
[ ] 卸载不会删除用户项目和 Gradle 缓存（除非用户明确选择删除数据）
[ ] `.txplugin` 解压后总大小小于 8 GiB
```
