# ARM64 资源下载清单

本插件定位为 **Android-only**：Flutter 只保留 Android 编译所需资源，不提供 Web、iOS、Windows、macOS 或 Linux Desktop 构建能力。iOS 本身也不能在 Linux ARM64 PRoot 中构建，因为需要 macOS/Xcode。

请只下载下列 AArch64/ARM64 资源，并按目标文件名保存到 `payload/archives/`。不要下载文件名包含 `x86`、`x86_64`、`amd64` 的变体。

| 目标文件名 | 下载地址 | 备注 |
|---|---|---|
| `jdk-17-aarch64-linux.tar.gz` | https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz | Eclipse Temurin JDK 17 AArch64；SHA-256 `457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41` |
| `gradle-8.14.2-bin.zip` | https://mirrors.cloud.tencent.com/gradle/gradle-8.14.2-bin.zip | Gradle 是 Java 归档，不是主机 ELF；SHA-256 `7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999` |
| `platform-34-ext7_r03.zip` | https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r03.zip | Android Platform 34，主要是 `android.jar` 等架构无关资源；SHA-1 `1f2e9478d6a7601425ceaa553311dc43191f103d` |
| `build-tools_r35_linux.zip` | https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip | 只使用其中 JAR/资源；安装脚本会删除非 ARM64 ELF；SHA-1 `2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc` |
| `android-sdk-tools-static-aarch64.zip` | https://ghfast.top/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip | ARM64 `aapt2/aidl/zipalign`；SHA-256 `DB1CEA2C4454D5F9C5A802646B2D1CF560B4EE7BADBE23E51AB8E1881BB50FC2` |
| `android-ndk-r29-aarch64.tar.gz` | 由上游 https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz 流式转码 | Linux AArch64 NDK；仅将外层 XZ 改为 GZip，tar 内容不变；SHA-256 `92ffb0343d2325f0bdb77c68c9ffb049e86b4266a0881db594dddbc2d6f9519f` |
| `cmake-linux-aarch64.tar.gz` | https://github.com/Kitware/CMake/releases/download/v3.31.7/cmake-3.31.7-linux-aarch64.tar.gz | CMake Linux AArch64 |
| `ninja-linux-aarch64.zip` | https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-linux-aarch64.zip | Ninja Linux AArch64 |
| `flutter-linux-arm64-android-only-slim.tar.gz` | https://github.com/MohamedAlkindi/flutter-native-arm64/releases/download/flutter-3.47.1-87-linux/flutter_v3.47.1_linux_arm64_android_web_sdk.tar.gz | 已从 ARM64 Flutter 包中移除 Android x86/x64、iOS、桌面、示例和源码目录，只保留 Android ARM/ARM64 与 Linux ARM64 编译所需缓存；最终约 848 MB |
| `android-tools_aarch64.deb` | https://packages.termux.dev/apt/termux-main/pool/main/a/android-tools/android-tools_36.0.1%2Breally35.0.2_aarch64.deb | Termux AArch64 `adb`；SHA-256 `82e48bf8038250fb0997b1f2cf5f780730104f2544a5532298c453d94cfe1537` |
| `jadx-1.5.0.zip` | https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip | Skylot JADX-CLI Java 反编译引擎与启动器 |
| `apktool_2.10.0.jar` | https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar | Apktool APK 资源与 Smali 处理套件 |
| `dex-tools-v2.4.zip` | https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip | pxb1988 dex2jar、smali/baksmali 转换工具集 |
| `ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz` | https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz | BurntSushi Ripgrep (`rg`) Linux AArch64 静态单二进制毫秒级代码检索器 |
| `rust-1.85.0-aarch64-unknown-linux-gnu.tar.gz` | https://static.rust-lang.org/dist/rust-1.85.0-aarch64-unknown-linux-gnu.tar.gz | Rust 1.85.0 ARM64 独立工具链（含 rustc、cargo、宿主标准库） |
| `rust-std-1.85.0-aarch64-linux-android.tar.gz` | https://static.rust-lang.org/dist/rust-std-1.85.0-aarch64-linux-android.tar.gz | Rust aarch64-linux-android 交叉编译目标库 |
| `uber-apk-signer-1.3.0.jar` | https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar | 独立一键 APK 对齐与 v1/v2/v3/v4 签名工具（可选） |
| `baksmali-3.0.8.jar` | https://github.com/google/smali/releases/download/v3.0.8/baksmali-3.0.8.jar | Google 官方原版 baksmali 反汇编独立 JAR（可选备用） |
| `smali-3.0.8.jar` | https://github.com/google/smali/releases/download/v3.0.8/smali-3.0.8.jar | Google 官方原版 smali 汇编独立 JAR（可选备用） |

## 放置位置

最终目录必须是：

```text
D:\work\wanxiang\assets\plugins\android-suite-offline\payload\archives\
```

PowerShell 示例：

```powershell
$archiveDir = 'D:\work\wanxiang\assets\plugins\android-suite-offline\payload\archives'
New-Item -ItemType Directory -Force $archiveDir | Out-Null
Move-Item 'D:\Downloads\OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz' "$archiveDir\jdk-17-aarch64-linux.tar.gz"
Move-Item 'D:\Downloads\flutter_v3.47.1_linux_arm64_android_web_sdk.tar.gz' "$archiveDir\flutter-source-arm64.tar.gz"
```

下载完成后不要解压，直接把原始归档放入 `payload/archives/`。`install-android-suite.sh` 会在 PRoot 内解压到 `/opt`。

## 下载后校验

```powershell
Get-FileHash "$archiveDir\jdk-17-aarch64-linux.tar.gz" -Algorithm SHA256
Get-FileHash "$archiveDir\android-ndk-r29-aarch64.tar.gz" -Algorithm SHA256
Get-FileHash "$archiveDir\android-sdk-tools-static-aarch64.zip" -Algorithm SHA256
Get-FileHash "$archiveDir\android-tools_aarch64.deb" -Algorithm SHA256
```

如果 GitHub 直连失败，可以只替换为 `ghfast.top` 代理，但文件名和 SHA-256 必须保持一致。不要用同名的 `x86_64`、`amd64` 或 `linux-x64` 资源替换。
