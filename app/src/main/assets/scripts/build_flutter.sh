#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - Flutter Project One-Key Build Engine
# Usage: build_flutter.sh <project_path> [target]
# ==============================================================================
set -e

PROJECT_PATH="${1:-.}"
TARGET="${2:-apk --debug}"
GRADLE_VER="8.14.2"

echo "==> [WanXiang Build Engine] 启动 Flutter 项目跨端编译..."
echo "==> [WanXiang Build] 项目路径: $PROJECT_PATH"

TOOLCHAIN_LOCK_FILE="/opt/wanxiang/locks/android-toolchain.lock"
mkdir -p /opt/wanxiang/locks
command -v flock >/dev/null 2>&1 || {
    echo "==> [WanXiang Build] ❌ 缺少 flock，拒绝在无工具链锁的情况下构建"
    exit 1
}
exec 9>"$TOOLCHAIN_LOCK_FILE"
flock -s -w 1800 9 || {
    echo "==> [WanXiang Build] ❌ Android/Flutter 工具链正在装配，等待超时"
    exit 1
}

# 1. 注入 Flutter 与 Gradle PATH (优先加载插件装配期固化的环境变量)
workshop_java_home="${JAVA_HOME:-}"
workshop_android_home="${ANDROID_HOME:-}"
workshop_gradle_home="${GRADLE_HOME:-}"
workshop_flutter_home="${FLUTTER_HOME:-}"
workshop_ndk_path="${WANXIANG_NDK_PATH:-}"
workshop_android_ndk_home="${ANDROID_NDK_HOME:-}"
workshop_aapt2_path="${WANXIANG_AAPT2_PATH:-}"
workshop_cmake_home="${WANXIANG_CMAKE_HOME:-}"
workshop_ninja_home="${WANXIANG_NINJA_HOME:-}"
workshop_gradle_user_home="${GRADLE_USER_HOME:-}"
workshop_pub_cache="${PUB_CACHE:-}"
workshop_tool_dir="${WANXIANG_TOOL_DIR:-}"
if [ -f /etc/profile.d/wanxiang-android.sh ]; then . /etc/profile.d/wanxiang-android.sh; fi
[ -z "$workshop_java_home" ] || JAVA_HOME="$workshop_java_home"
[ -z "$workshop_android_home" ] || ANDROID_HOME="$workshop_android_home"
[ -z "$workshop_gradle_home" ] || GRADLE_HOME="$workshop_gradle_home"
[ -z "$workshop_flutter_home" ] || FLUTTER_HOME="$workshop_flutter_home"
[ -z "$workshop_ndk_path" ] || WANXIANG_NDK_PATH="$workshop_ndk_path"
[ -z "$workshop_android_ndk_home" ] || ANDROID_NDK_HOME="$workshop_android_ndk_home"
[ -z "$workshop_aapt2_path" ] || WANXIANG_AAPT2_PATH="$workshop_aapt2_path"
[ -z "$workshop_cmake_home" ] || WANXIANG_CMAKE_HOME="$workshop_cmake_home"
[ -z "$workshop_ninja_home" ] || WANXIANG_NINJA_HOME="$workshop_ninja_home"
[ -z "$workshop_gradle_user_home" ] || GRADLE_USER_HOME="$workshop_gradle_user_home"
[ -z "$workshop_pub_cache" ] || PUB_CACHE="$workshop_pub_cache"
[ -z "$workshop_tool_dir" ] || WANXIANG_TOOL_DIR="$workshop_tool_dir"
export FLUTTER_HOME="${FLUTTER_HOME:-/opt/flutter}"
export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle-$GRADLE_VER}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export WANXIANG_TOOL_DIR="${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}"
export PATH="/opt/wanxiang/bin:$FLUTTER_HOME/bin:$GRADLE_HOME/bin:${WANXIANG_CMAKE_HOME:-/opt/wanxiang/tools/android-suite-offline/cmake}/bin:${WANXIANG_NINJA_HOME:-/opt/wanxiang/tools/android-suite-offline/bin}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PUB_HOSTED_URL="https://pub.flutter-io.cn"
export FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"
export PUB_CACHE="${PUB_CACHE:-/opt/wanxiang/cache/flutter-pub}"
NDK_PATH="${WANXIANG_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/wanxiang/toolchains/android/ndk}}"
LLVM_STRIP=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name llvm-strip -print -quit 2>/dev/null)
NDK_CLANG=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit 2>/dev/null)
if [ -z "$NDK_PATH" ] || [ ! -f "$NDK_PATH/source.properties" ] || \
   [ ! -x "$LLVM_STRIP" ] || [ ! -x "$NDK_CLANG" ]; then
    echo "==> [WanXiang Build] ❌ 固定 ARM64 NDK 未就位，请重新装配 Android 核心基础环境"
    exit 126
fi
STRIP_MACHINE=$(od -An -t x1 -j 18 -N 2 "$LLVM_STRIP" 2>/dev/null | tr -d '[:space:]')
CLANG_MACHINE=$(od -An -t x1 -j 18 -N 2 "$NDK_CLANG" 2>/dev/null | tr -d '[:space:]')
if [ "$STRIP_MACHINE" != "b700" ] || [ "$CLANG_MACHINE" != "b700" ] || \
   ! "$LLVM_STRIP" --version >/dev/null 2>&1 || \
   ! "$NDK_CLANG" --version >/dev/null 2>&1; then
    echo "==> [WanXiang Build] ❌ NDK 主机工具不是可执行的 Linux AArch64 制品"
    exit 126
fi
export ANDROID_NDK_HOME="$NDK_PATH"
export ANDROID_NDK_ROOT="$NDK_PATH"
export WANXIANG_LLVM_STRIP_PATH="$LLVM_STRIP"
echo "==> [WanXiang Build] 固定 ARM64 NDK: $NDK_PATH"

# Java 启动器防回环守卫（与 build_android.sh 同一规则）：Flutter 的 Gradle
# 宿主构建最终经 JAVA_HOME 启动 JVM。包装脚本回环在 PRoot 下是零输出、
# CPU 满载的死循环，必须在 JVM 启动前拒绝。
JAVA_GUARD="${JAVA_HOME:-/opt/wanxiang/toolchains/android/jdk}/bin/java"
if [ -e "$JAVA_GUARD" ]; then
    JAVA_GUARD_REAL=$(readlink -f "$JAVA_GUARD" 2>/dev/null || echo "$JAVA_GUARD")
    JAVA_GUARD_MAGIC=$(od -An -t x1 -N 4 "$JAVA_GUARD_REAL" 2>/dev/null | tr -d '[:space:]')
    if [ "$JAVA_GUARD_MAGIC" != "7f454c46" ]; then
        echo "==> [WanXiang Build] ❌ Java 启动器不是 ELF 二进制（疑似包装脚本/回环软链）: $JAVA_GUARD_REAL"
        echo "==> [WanXiang Build] 请在插件中心重新装配【Android 全栈开发套件】以修复 JDK"
        exit 126
    fi
fi

# 2. 自愈软链接与原生 gen_snapshot 映射
if [ -d "$FLUTTER_HOME/bin" ] && [ ! -f /usr/local/bin/flutter ]; then
    ln -sf "$FLUTTER_HOME/bin/flutter" /usr/local/bin/flutter 2>/dev/null || true
    ln -sf "$FLUTTER_HOME/bin/dart" /usr/local/bin/dart 2>/dev/null || true
fi

# Flutter 引擎在 Linux ARM64 主机上构建 Android release/profile APK 时，
# 按 artifacts.dart 寻址 android-arm64-{release,profile}/linux-arm64/gen_snapshot。
# 若缺失，将原生 linux-arm64/gen_snapshot (AArch64 ELF) 自愈链接至对应目录。
ENGINE_DIR="$FLUTTER_HOME/bin/cache/artifacts/engine"
if [ -x "$ENGINE_DIR/linux-arm64/gen_snapshot" ]; then
    for mode in release profile; do
        target_dir="$ENGINE_DIR/android-arm64-$mode/linux-arm64"
        mkdir -p "$target_dir" 2>/dev/null || true
        if [ ! -e "$target_dir/gen_snapshot" ]; then
            ln -sf "$ENGINE_DIR/linux-arm64/gen_snapshot" "$target_dir/gen_snapshot" 2>/dev/null || true
            echo "==> [WanXiang Build] 已自愈链接 ARM64 gen_snapshot -> $target_dir/gen_snapshot"
        fi
    done
fi

cd "$PROJECT_PATH"

# ==============================================================================
# 构建工具版本临时对齐（构建后自动恢复，不修改工具链本身）
#
# 1. NDK：Flutter Gradle 插件默认注入 ndkVersion（如 27.0.12077973），可能与
#    万象实际安装的 NDK（如 29.0.14206865）不一致，AGP 抛 CXX1100。
# 2. CMake：Flutter 硬编码要求 CMake 3.22.1，万象环境可能装了更新版本，
#    AGP 抛 CXX1300。
#
# 处理方式：临时修改项目 android/app/build.gradle 注入实际版本，构建结束后
# 通过 trap EXIT 自动恢复原文件。不修改 NDK/CMake 本身，不影响 Android 项目。
# ==============================================================================
WANXIANG_GRADLE_ALIGN_BAK=""
wanxiang_align_build_versions() {
    app_gradle="android/app/build.gradle"
    [ -f "$app_gradle" ] || return 0
    modified=0
    backup_once() {
        if [ $modified -eq 0 ]; then
            WANXIANG_GRADLE_ALIGN_BAK="${app_gradle}.wanxiang-align.bak"
            cp "$app_gradle" "$WANXIANG_GRADLE_ALIGN_BAK"
            modified=1
        fi
    }

    # --- NDK 版本对齐 ---
    ndk_home="${ANDROID_NDK_HOME:-/opt/wanxiang/toolchains/android/ndk}"
    if [ -f "$ndk_home/source.properties" ]; then
        actual_ndk=$(sed -n 's/^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_home/source.properties" | head -n1 | tr -d '[:space:]')
        if [ -n "$actual_ndk" ]; then
            declared_ndk=$(grep -oE 'ndkVersion[[:space:]]*["'"'"'][^"'"'"']*["'"'"']' "$app_gradle" | head -1 | grep -oE '["'"'"'][^"'"'"']*["'"'"']' | tr -d "\"'")
            if [ -z "$declared_ndk" ] || [ "$declared_ndk" != "$actual_ndk" ]; then
                echo "==> [WanXiang Build] NDK 版本对齐：项目声明=${declared_ndk:-<Flutter默认>}, 实际=$actual_ndk"
                backup_once
                sed -i "s/^\([[:space:]]*android[[:space:]]*{\)/\1\n    ndkVersion \"$actual_ndk\"/" "$app_gradle"
                if grep -q "ndkVersion \"$actual_ndk\"" "$app_gradle"; then
                    echo "==> [WanXiang Build] ✅ 已注入 ndkVersion=$actual_ndk"
                else
                    echo "==> [WanXiang Build] ⚠️ ndkVersion 注入失败"
                fi
            fi
        else
            echo "==> [WanXiang Build] ⚠️ 无法读取 NDK 版本，跳过 NDK 对齐"
        fi
    fi

    # --- CMake 版本对齐 ---
    # Flutter/AGP 默认要求 CMake 3.22.1，万象环境可能装了更新版本。
    # 必须在 android {} 配置块内设置 externalNativeBuild.cmake.version，
    # afterEvaluate 阶段再设会被 AGP 拒绝（"It is too late to set version"）。
    if command -v cmake >/dev/null 2>&1; then
        actual_cmake=$(cmake --version 2>/dev/null | head -n1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1)
        flutter_cmake="3.22.1"
        if [ -n "$actual_cmake" ] && [ "$actual_cmake" != "$flutter_cmake" ]; then
            echo "==> [WanXiang Build] CMake 版本对齐：Flutter 要求=$flutter_cmake, 实际=$actual_cmake"
            backup_once
            # 在 android { 块内注入 externalNativeBuild.cmake.version
            sed -i "s/^\([[:space:]]*android[[:space:]]*{\)/\1\n    externalNativeBuild {\n        cmake {\n            version \"$actual_cmake\"\n        }\n    }/" "$app_gradle"
            if grep -q "version \"$actual_cmake\"" "$app_gradle"; then
                echo "==> [WanXiang Build] ✅ 已注入 externalNativeBuild.cmake.version=$actual_cmake"
            else
                echo "==> [WanXiang Build] ⚠️ CMake 版本注入失败"
            fi
        fi
    fi
}
wanxiang_restore_gradle() {
    if [ -n "$WANXIANG_GRADLE_ALIGN_BAK" ] && [ -f "$WANXIANG_GRADLE_ALIGN_BAK" ]; then
        mv "$WANXIANG_GRADLE_ALIGN_BAK" "android/app/build.gradle"
        echo "==> [WanXiang Build] 已恢复 android/app/build.gradle（版本对齐临时修改已撤销）"
    fi
}
trap wanxiang_restore_gradle EXIT
wanxiang_align_build_versions

if ! command -v flutter >/dev/null 2>&1; then
    echo "==> [WanXiang Build] ❌ 未找到 Flutter SDK，请安装 Flutter 跨平台开发套件"
    exit 127
fi

# Flutter 工具链自身依赖 unzip 解压引擎缓存（bin/cache/downloads/*.zip）。
# 精简 rootfs 没有系统 unzip；安装期的临时 shim 只在 TOOL_DIR/bin 下、
# 不在构建 PATH 上。这里在调起 flutter 之前自愈：用 JDK 的 jar 造一个
# 常驻 /opt/wanxiang/bin/unzip（PATH 首位），避免 "Missing unzip tool" 中断。
if ! command -v unzip >/dev/null 2>&1; then
    JAR_BIN=""
    for candidate in "${JAVA_HOME:-/opt/wanxiang/toolchains/android/jdk}/bin/jar" /opt/wanxiang/toolchains/android/jdk/bin/jar /usr/bin/jar /usr/lib/jvm/default-java/bin/jar; do
        if [ -x "$candidate" ]; then JAR_BIN="$candidate"; break; fi
    done
    if [ -n "$JAR_BIN" ]; then
        mkdir -p /opt/wanxiang/bin
        printf '%s\n' \
            '#!/bin/sh' \
            'archive=' \
            'dest=.' \
            'while [ "$#" -gt 0 ]; do' \
            '  case "$1" in' \
            '    -q|-qq|-o) shift ;;' \
            '    -d) dest="$2"; shift 2 ;;' \
            '    -*) shift ;;' \
            '    *) archive="$1"; shift ;;' \
            '  esac' \
            'done' \
            '[ -n "$archive" ] || exit 2' \
            'mkdir -p "$dest"' \
            "(cd \"\$dest\" && '$JAR_BIN' xf \"\$archive\")" \
            > /opt/wanxiang/bin/unzip
        chmod 755 /opt/wanxiang/bin/unzip
        echo "==> [WanXiang Build] 已部署 unzip 兼容层（基于 JDK jar）：/opt/wanxiang/bin/unzip"
    else
        echo "==> [WanXiang Build] ❌ 缺少 unzip 且无 JDK jar 可用，无法解压 Flutter 引擎缓存"
        echo "==> [WanXiang Build] 请重新装配【Android 全栈开发套件】"
        exit 127
    fi
fi

if [ ! -x "$FLUTTER_HOME/bin/flutter" ] || [ ! -x "$FLUTTER_HOME/bin/cache/dart-sdk/bin/dart" ]; then
    echo "==> [WanXiang Build] ❌ Flutter SDK 不是可用的 Linux ARM64 版本，请在工坊重新装配 Flutter 套件"
    exit 126
fi
# Flutter 工具的 locateAndroidSdk 需要 $ANDROID_HOME/platform-tools/adb 存在
# 才认这个 SDK（套件历史版本只装 build-tools + platform，adb 在 TOOL_DIR/bin）。
# 缺了会报 "No Android SDK found. Try setting the ANDROID_HOME"，这里自愈补齐
# platform-tools 布局与 licenses，存量沙箱无需重装插件。
if [ ! -e "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB_CANDIDATE=""
    for candidate in "${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools/android-suite-offline}/bin/adb" /opt/wanxiang/bin/adb; do
        if [ -x "$candidate" ]; then ADB_CANDIDATE="$candidate"; break; fi
    done
    mkdir -p "$ANDROID_HOME/platform-tools"
    if [ -n "$ADB_CANDIDATE" ]; then
        ln -sfn "$ADB_CANDIDATE" "$ANDROID_HOME/platform-tools/adb"
        echo "==> [WanXiang Build] 已补齐 Flutter SDK 布局：$ANDROID_HOME/platform-tools/adb -> $ADB_CANDIDATE"
    fi
fi
mkdir -p "$ANDROID_HOME/licenses"
if [ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]; then
    printf '\x89\x50\x41\x59\x0d\x0a\x1a\x0a\xd0\x4a\x87\x95\x6d\x7d\x3c\xcf\x9d\nd56f5187d9450ff8409f4ab7c8ab84e9\n' \
        > "$ANDROID_HOME/licenses/android-sdk-license"
fi

if [ ! -f "${ANDROID_HOME}/platforms/android-34/android.jar" ]; then
    echo "==> [WanXiang Build] ❌ 缺少 Android SDK Platform 34，请同时安装 Android 核心基础环境"
    exit 126
fi
if [ ! -f "${ANDROID_HOME}/build-tools/35.0.0/lib/d8.jar" ]; then
    echo "==> [WanXiang Build] ❌ 缺少 Android Build-Tools 35.0.0，请重新装配 Android 核心基础环境"
    exit 126
fi

# Migrate projects generated by the previous WanXiang Flutter template. That
# template disabled stripping for every .so to bypass the x86_64 NDK tool,
# which could inflate a debug APK beyond 1 GB. Only remove the exact managed
# block; user-defined, selective keepDebugSymbols rules are left untouched.
FLUTTER_APP_GRADLE="android/app/build.gradle"
if [ -f "$FLUTTER_APP_GRADLE" ] && grep -Fq 'keepDebugSymbols += "**/*.so"' "$FLUTTER_APP_GRADLE"; then
    python3 - "$FLUTTER_APP_GRADLE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
managed_block = '''    // NDK 只提供 linux-x86_64 的 llvm-strip，在 ARM64 PRoot 里无法启动。
    // 保留 native 调试符号，避免 AGP 调用它导致构建失败。
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

'''
if managed_block in text:
    path.write_text(text.replace(managed_block, "", 1), encoding="utf-8")
    print("==> [WanXiang Build] 已迁移旧版 Flutter 模板：启用 native 符号剥离")
PY
    if grep -Fq 'keepDebugSymbols += "**/*.so"' "$FLUTTER_APP_GRADLE"; then
        echo "==> [WanXiang Build] ❌ 检测到全量 keepDebugSymbols 配置，请删除后重试"
        exit 126
    fi
fi

mkdir -p "$PUB_CACHE" android
if [ -f "$FLUTTER_HOME/bin/flutter" ]; then
    LOCAL_PROPERTIES=android/local.properties
    LOCAL_PROPERTIES_TMP="${LOCAL_PROPERTIES}.wanxiang.tmp"
    if [ -f "$LOCAL_PROPERTIES" ]; then
        sed -e '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' \
            -e '/^[[:space:]]*ndk\.dir[[:space:]]*=/d' \
            -e '/^[[:space:]]*flutter\.sdk[[:space:]]*=/d' \
            "$LOCAL_PROPERTIES" > "$LOCAL_PROPERTIES_TMP"
    else
        : > "$LOCAL_PROPERTIES_TMP"
    fi
    printf 'sdk.dir=%s\nflutter.sdk=%s\n' "$ANDROID_HOME" "$FLUTTER_HOME" >> "$LOCAL_PROPERTIES_TMP"
    mv -f "$LOCAL_PROPERTIES_TMP" "$LOCAL_PROPERTIES"
    echo "==> [WanXiang Build] 绑定 ANDROID_HOME/Flutter SDK: $ANDROID_HOME / $FLUTTER_HOME"
fi
AAPT2_PATH="${WANXIANG_AAPT2_PATH:-$ANDROID_HOME/build-tools/35.0.0/aapt2}"
case "$AAPT2_PATH" in
    /opt/android-sdk/build-tools/35.0.0/aapt2|/opt/wanxiang/toolchains/android/sdk-tools/artifacts/*/build-tools/aapt2) ;;
    *)
        echo "==> [WanXiang Build] ❌ AAPT2 未指向不可变 ARM64 制品目录"
        exit 126
        ;;
esac
AAPT2_MACHINE=$(od -An -t x1 -j 18 -N 2 "$AAPT2_PATH" 2>/dev/null | tr -d '[:space:]')
if [ "$AAPT2_MACHINE" = "b700" ] && [ -x "$AAPT2_PATH" ] && \
   "$AAPT2_PATH" version >/dev/null 2>&1; then
    export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="$AAPT2_PATH"
    echo "==> [WanXiang Build] 使用 ARM64 原生 AAPT2: $AAPT2_PATH"
else
    echo "==> [WanXiang Build] ❌ 固定 ARM64 AAPT2 在构建启动前失效"
    exit 126
fi

if ! grep -Fqx 'android.builder.sdkDownload=false' "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null; then
    echo "==> [WanXiang Build] ❌ Gradle SDK 自动下载未禁用，拒绝构建以防官方 x86_64 工具覆盖"
    exit 126
fi
if [ ! -f "$GRADLE_USER_HOME/init.d/wanxiang-android-ndk.gradle" ] || \
   ! grep -Fq 'androidExtension.ndkPath = wanxiangNdkPath' "$GRADLE_USER_HOME/init.d/wanxiang-android-ndk.gradle"; then
    echo "==> [WanXiang Build] ❌ 固定 NDK 路径注入缺失"
    exit 126
fi

# WanXiang: 兜底在全局 Gradle 配置注入 HTTP 连接/读超时，避免国内镜像慢或被重置时
# 依赖解析无限静默阻塞（SocketException: connection abort）。幂等追加，仅补缺失行。
GRADLE_PROPS="$GRADLE_USER_HOME/gradle.properties"
if [ -f "$GRADLE_PROPS" ]; then
    for k in \
        'systemProp.org.gradle.internal.http.connectionTimeout=30000' \
        'systemProp.org.gradle.internal.http.socketTimeout=60000'; do
        if ! grep -Fqx "$k" "$GRADLE_PROPS" 2>/dev/null; then
            printf '%s\n' "$k" >> "$GRADLE_PROPS"
        fi
    done
fi

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.jvmargs=-Xmx1024m"

if [ "${WANXIANG_OFFLINE:-0}" = "1" ]; then
    echo "==> [WanXiang Build] 离线模式：使用本地 Flutter Pub 缓存"
    flutter pub get --offline --verbose
else
    echo "==> [WanXiang Build] 正在拉取 Flutter 依赖 (flutter pub get)..."
    flutter pub get --verbose
fi

# 3. 确保 Android 宿主使用本地 Gradle 8.14.2 + 国内镜像，避免 Wrapper 从 services.gradle.org
#    下载发行版（国内网络易被重置，报 SocketException: connection abort）。
mkdir -p android/gradle/wrapper
cat > android/gradle/wrapper/gradle-wrapper.properties <<'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.2-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
cat > android/gradlew <<'EOF'
#!/bin/sh
# 优先复用插件装配期已装好的本地 Gradle 8.14.2，避免 Wrapper 重新下载发行版。
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    exec "$GRADLE_HOME/bin/gradle" "$@"
elif [ -x /opt/gradle-8.7/bin/gradle ]; then
    exec /opt/gradle-8.7/bin/gradle "$@"
elif [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
elif [ -x /usr/local/bin/gradle ]; then
    exec /usr/local/bin/gradle "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    exec /usr/bin/gradle "$@"
fi
EOF
chmod +x android/gradlew

# 4. 全局强制阿里云镜像：Flutter 每次会重写工程文件，这里兜底写入全局 init 脚本，
#    并把 GRADLE_USER_HOME 固定到 /root/.gradle，确保依赖解析一定走国内源。
mkdir -p "$GRADLE_USER_HOME"
cat > "$GRADLE_USER_HOME/init.gradle" <<'EOF'
// WanXiang: 全局强制阿里云镜像。
gradle.beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://storage.flutter-io.cn/download.flutter.io' }
        google()
        mavenCentral()
    }
}
EOF

echo "==> [WanXiang Build] 正在执行 Flutter 打包编译 (flutter build $TARGET)..."
# 注意：不能用 exec，否则 trap EXIT 不会触发，NDK 版本对齐的临时修改无法恢复。
if [ "${WANXIANG_OFFLINE:-0}" = "1" ]; then
    flutter build $TARGET --offline --verbose
else
    flutter build $TARGET --verbose
fi
build_exit=$?
exit $build_exit
