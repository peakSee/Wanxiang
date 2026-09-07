#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - Android Project One-Key Build Engine
# Usage: build_android.sh <project_path> [task]
# ------------------------------------------------------------------------------
# 纯执行器：所有环境部署均由【Android & 移动全栈开发套件】插件装配完成。
# 本脚本只负责：加载环境 → 调度 Gradle 构建。不做任何修复/下载/自愈。
# ==============================================================================
set -e

PROJECT_PATH="${1:-.}"
TASK="${2:-assembleDebug}"
GRADLE_VER="8.14.2"

echo "==> [WanXiang Build Engine] 启动 Android 项目编译..."
echo "==> [WanXiang Build] 项目路径: $PROJECT_PATH"
echo "==> [WanXiang Build] 构建任务: $TASK"

# Builds take a shared lock for their entire lifetime. Android toolchain setup
# takes the exclusive side of the same lock, so immutable views cannot change
# between preflight and a later AGP worker launch.
TOOLCHAIN_LOCK_FILE="/opt/wanxiang/locks/android-toolchain.lock"
mkdir -p /opt/wanxiang/locks
command -v flock >/dev/null 2>&1 || {
    echo "==> [WanXiang Build] ❌ 缺少 flock，拒绝在无工具链锁的情况下构建"
    exit 1
}
exec 9>"$TOOLCHAIN_LOCK_FILE"
flock -s -w 1800 9 || {
    echo "==> [WanXiang Build] ❌ Android 工具链正在装配，等待超时"
    exit 1
}

# 1. 加载插件装配期固化的环境变量
workshop_java_home="${JAVA_HOME:-}"
workshop_android_home="${ANDROID_HOME:-}"
workshop_gradle_home="${GRADLE_HOME:-}"
workshop_ndk_path="${WANXIANG_NDK_PATH:-}"
workshop_android_ndk_home="${ANDROID_NDK_HOME:-}"
workshop_aapt2_path="${WANXIANG_AAPT2_PATH:-}"
workshop_cmake_home="${WANXIANG_CMAKE_HOME:-}"
workshop_ninja_home="${WANXIANG_NINJA_HOME:-}"
workshop_gradle_user_home="${GRADLE_USER_HOME:-}"
workshop_tool_dir="${WANXIANG_TOOL_DIR:-}"
if [ -f /etc/profile.d/wanxiang-android.sh ]; then . /etc/profile.d/wanxiang-android.sh; fi
[ -z "$workshop_java_home" ] || JAVA_HOME="$workshop_java_home"
[ -z "$workshop_android_home" ] || ANDROID_HOME="$workshop_android_home"
[ -z "$workshop_gradle_home" ] || GRADLE_HOME="$workshop_gradle_home"
[ -z "$workshop_ndk_path" ] || WANXIANG_NDK_PATH="$workshop_ndk_path"
[ -z "$workshop_android_ndk_home" ] || ANDROID_NDK_HOME="$workshop_android_ndk_home"
[ -z "$workshop_aapt2_path" ] || WANXIANG_AAPT2_PATH="$workshop_aapt2_path"
[ -z "$workshop_cmake_home" ] || WANXIANG_CMAKE_HOME="$workshop_cmake_home"
[ -z "$workshop_ninja_home" ] || WANXIANG_NINJA_HOME="$workshop_ninja_home"
[ -z "$workshop_gradle_user_home" ] || GRADLE_USER_HOME="$workshop_gradle_user_home"
[ -z "$workshop_tool_dir" ] || WANXIANG_TOOL_DIR="$workshop_tool_dir"
# /etc/environment 由插件装配期写入，PRoot 非登录 shell 场景下兜底
if [ -f /etc/environment ]; then
    while IFS= read -r line; do
        case "$line" in
            *=*) key="${line%%=*}"
                 val="${line#*=}"
                 eval "current=\${$key}"
                 [ -z "$current" ] && export "$key=$val"
                 ;;
        esac
    done < /etc/environment
fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle-$GRADLE_VER}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export WANXIANG_TOOL_DIR="${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}"

# The local offline suite is authoritative when present. A distro package may
# leave an x86_64 Java earlier on PATH, which is unusable in the ARM64 PRoot.
if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/wanxiang/toolchains/android/jdk/bin/java ]; then
    JAVA_HOME=/opt/wanxiang/toolchains/android/jdk
fi

# AGP's default Maven artifact is a Linux x86_64 executable.  When the
# android-core plugin installed the ARM64 tool bundle, pass the real aapt2
# executable explicitly so AGP never tries to start the incompatible daemon.
AAPT2_OVERRIDE="${WANXIANG_AAPT2_PATH:-$ANDROID_HOME/build-tools/35.0.0/aapt2}"
NDK_PATH="${WANXIANG_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/wanxiang/toolchains/android/ndk}}"

# 非登录 shell 可能没有继承插件写入的 profile；变量为空时从标准 JDK
# 目录和 PATH 重新解析，避免环境变量漂移阻断构建。
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    JAVA_HOME=""
    for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-aarch64 /usr/lib/jvm/default-java; do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_BIN_FALLBACK=$(command -v java 2>/dev/null || true)
    if [ -n "$JAVA_BIN_FALLBACK" ]; then
        JAVA_REAL_FALLBACK=$(readlink -f "$JAVA_BIN_FALLBACK" 2>/dev/null || echo "$JAVA_BIN_FALLBACK")
        JAVA_HOME=$(dirname "$(dirname "$JAVA_REAL_FALLBACK")")
    fi
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# 固定本次构建使用的 Java，避免 PATH 中的旧软链接指向另一套 JDK。
JAVA_EXEC=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXEC="$JAVA_HOME/bin/java"
else
    JAVA_EXEC=$(command -v java 2>/dev/null || true)
    if [ -n "$JAVA_EXEC" ] && command -v readlink >/dev/null 2>&1; then
        JAVA_REAL=$(readlink -f "$JAVA_EXEC" 2>/dev/null || true)
        [ -n "$JAVA_REAL" ] && JAVA_EXEC="$JAVA_REAL"
    fi
fi
if [ -z "$JAVA_EXEC" ] || [ ! -x "$JAVA_EXEC" ]; then
    echo "==> [WanXiang Build] ❌ 未找到可执行 Java (JAVA_HOME=$JAVA_HOME)"
    exit 127
fi
# Java 启动器必须解析到真正的 ELF。包装脚本一旦与其 exec 目标形成回环
# （脚本 → 软链接 → 脚本），每次 exec 都要过 PRoot 的 ptrace 翻译，
# 表现为 JVM 零输出、CPU 持续满载的"卡死"。在启动 JVM 之前拒绝，
# 绝不让它烧满 30 分钟超时。
JAVA_REAL=$(readlink -f "$JAVA_EXEC" 2>/dev/null || echo "$JAVA_EXEC")
JAVA_MAGIC=$(od -An -t x1 -N 4 "$JAVA_REAL" 2>/dev/null | tr -d '[:space:]')
if [ "$JAVA_MAGIC" != "7f454c46" ]; then
    echo "==> [WanXiang Build] ❌ Java 启动器不是 ELF 二进制（疑似包装脚本/回环软链）: $JAVA_REAL"
    echo "==> [WanXiang Build] 请在插件中心重新装配【Android 全栈开发套件】以修复 JDK"
    exit 126
fi
JAVA_LAUNCHER_MACHINE=$(od -An -t x1 -j 18 -N 2 "$JAVA_REAL" 2>/dev/null | tr -d '[:space:]')
if [ "$JAVA_LAUNCHER_MACHINE" != "b700" ]; then
    echo "==> [WanXiang Build] ❌ Java 启动器不是 AArch64 ELF (machine=$JAVA_LAUNCHER_MACHINE): $JAVA_REAL"
    exit 126
fi
echo "==> [WanXiang Build] Java 执行文件: $JAVA_EXEC"

echo "==> [WanXiang Build] JAVA_HOME: $JAVA_HOME"
echo "==> [WanXiang Build] ANDROID_HOME: $ANDROID_HOME"

# AGP 8.11.1 requires SDK Build Tools 35.0.0. Fail before Gradle starts slow
# remote SDK probes, which hide the actionable error in restricted networks.
REQUIRED_BUILD_TOOLS="35.0.0"
if [ ! -f "$ANDROID_HOME/build-tools/$REQUIRED_BUILD_TOOLS/source.properties" ] || \
   [ ! -f "$ANDROID_HOME/build-tools/$REQUIRED_BUILD_TOOLS/lib/d8.jar" ]; then
    echo "==> [WanXiang Build] ❌ 缺少 Android Build-Tools $REQUIRED_BUILD_TOOLS"
    echo "==> [WanXiang Build] 请在插件中心重新装配【Android 核心基础环境】后再构建"
    exit 2
fi
case "$AAPT2_OVERRIDE" in
    /opt/android-sdk/build-tools/35.0.0/aapt2|/opt/wanxiang/toolchains/android/sdk-tools/artifacts/*/build-tools/aapt2) ;;
    *)
        echo "==> [WanXiang Build] ❌ AAPT2 未指向不可变 ARM64 制品目录"
        exit 2
        ;;
esac
AAPT2_MACHINE=$(od -An -t x1 -j 18 -N 2 "$AAPT2_OVERRIDE" 2>/dev/null | tr -d '[:space:]')
if [ "$AAPT2_MACHINE" != "b700" ] || [ ! -x "$AAPT2_OVERRIDE" ] || \
   ! "$AAPT2_OVERRIDE" version >/dev/null 2>&1; then
    echo "==> [WanXiang Build] ❌ ARM64 AAPT2 未就位"
    echo "==> [WanXiang Build] 请在插件中心重新装配【Android 核心基础环境】后再构建"
    exit 2
fi

NDK_STRIP=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name llvm-strip -print -quit 2>/dev/null)
NDK_CLANG=$(find "$NDK_PATH/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit 2>/dev/null)
if [ -z "$NDK_PATH" ] || [ ! -f "$NDK_PATH/source.properties" ] || \
   [ ! -x "$NDK_STRIP" ] || [ ! -x "$NDK_CLANG" ]; then
    echo "==> [WanXiang Build] ❌ 固定 ARM64 NDK 未就位，请重新装配 Android 核心基础环境"
    exit 2
fi
STRIP_MACHINE=$(od -An -t x1 -j 18 -N 2 "$NDK_STRIP" 2>/dev/null | tr -d '[:space:]')
CLANG_MACHINE=$(od -An -t x1 -j 18 -N 2 "$NDK_CLANG" 2>/dev/null | tr -d '[:space:]')
if [ "$STRIP_MACHINE" != "b700" ] || [ "$CLANG_MACHINE" != "b700" ] || \
   ! "$NDK_STRIP" --version >/dev/null 2>&1 || \
   ! "$NDK_CLANG" --version >/dev/null 2>&1; then
    echo "==> [WanXiang Build] ❌ NDK 主机工具不是可执行的 Linux AArch64 制品"
    exit 126
fi
echo "==> [WanXiang Build] 固定 ARM64 NDK: $NDK_PATH"
if ! grep -Fqx 'android.builder.sdkDownload=false' "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null; then
    echo "==> [WanXiang Build] ❌ Gradle SDK 自动下载未禁用，拒绝构建以防官方 x86_64 工具覆盖"
    exit 2
fi
if [ ! -f "$GRADLE_USER_HOME/init.d/wanxiang-android-ndk.gradle" ] || \
   ! grep -Fq 'androidExtension.ndkPath = wanxiangNdkPath' "$GRADLE_USER_HOME/init.d/wanxiang-android-ndk.gradle"; then
    echo "==> [WanXiang Build] ❌ 固定 NDK 路径注入缺失"
    exit 2
fi
# 2. 绑定 SDK 到当前工程。保留用户的其他 local.properties 键。
# NDK 路径由 wanxiang-android-ndk.gradle 通过 android.ndkPath 唯一注入。
# 清理旧 ndk.dir 但不再写回，避免 AGP 8.11.1 拒绝两种 locator 并存。
LOCAL_PROPERTIES="$PROJECT_PATH/local.properties"
LOCAL_PROPERTIES_TMP="${LOCAL_PROPERTIES}.wanxiang.tmp"
if [ -f "$LOCAL_PROPERTIES" ]; then
    sed -e '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' \
        -e '/^[[:space:]]*ndk\.dir[[:space:]]*=/d' \
        "$LOCAL_PROPERTIES" > "$LOCAL_PROPERTIES_TMP"
else
    : > "$LOCAL_PROPERTIES_TMP"
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" >> "$LOCAL_PROPERTIES_TMP"
mv -f "$LOCAL_PROPERTIES_TMP" "$LOCAL_PROPERTIES"
echo "==> [WanXiang Build] 绑定 ANDROID_HOME: $ANDROID_HOME"

# 3. SSL 信任库参数
SSL_OPTS=""
if [ -s "$JAVA_HOME/lib/security/cacerts" ]; then
    CACERTS_PATH="$JAVA_HOME/lib/security/cacerts"
elif [ -s /etc/ssl/certs/java/cacerts ]; then
    CACERTS_PATH="/etc/ssl/certs/java/cacerts"
fi
if [ -n "$CACERTS_PATH" ]; then
    # 内置 cacerts 是 PKCS12；显式声明格式，避免精简 OpenJDK 默认按 JKS 解析。
    SSL_OPTS="-Djavax.net.ssl.trustStore=$CACERTS_PATH -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
fi

export PATH="$GRADLE_HOME/bin:${WANXIANG_CMAKE_HOME:-/opt/wanxiang/tools/android-suite-offline/cmake}/bin:${WANXIANG_NINJA_HOME:-/opt/wanxiang/tools/android-suite-offline/bin}:${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}/bin:$PATH"

cd "$PROJECT_PATH"

# 4. 调度 Gradle 构建
EXTRA_ARGS="--console=plain --info --stacktrace --no-daemon --max-workers=2 -Dorg.gradle.native=false -Dorg.gradle.internal.http.connectionTimeout=30000 -Dorg.gradle.internal.http.socketTimeout=60000 -Pandroid.builder.sdkDownload=false"
if [ "${WANXIANG_OFFLINE:-0}" = "1" ]; then
    EXTRA_ARGS="$EXTRA_ARGS --offline"
    echo "==> [WanXiang Build] 离线模式：禁止 Gradle 网络请求，仅使用本地缓存"
fi
if [ "$AAPT2_MACHINE" = "b700" ] && [ -x "$AAPT2_OVERRIDE" ] && \
   "$AAPT2_OVERRIDE" version >/dev/null 2>&1; then
    EXTRA_ARGS="$EXTRA_ARGS -Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE"
    echo "==> [WanXiang Build] ARM64 AAPT2: $AAPT2_OVERRIDE"
else
    echo "==> [WanXiang Build] ❌ 固定 ARM64 AAPT2 在构建启动前失效"
    exit 2
fi
JAVA_RUNTIME_OPTS="-Djava.security.egd=file:/dev/urandom"
[ -n "$SSL_OPTS" ] && JAVA_RUNTIME_OPTS="$JAVA_RUNTIME_OPTS $SSL_OPTS"
export GRADLE_OPTS="${GRADLE_OPTS:-} $JAVA_RUNTIME_OPTS"
GRADLE_JVM_MEMORY_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8"

if [ -d "$GRADLE_HOME/lib" ]; then
    echo "==> [WanXiang Build] 调度 Gradle: $GRADLE_HOME"
    echo "==> [WanXiang Build] 依赖解析与任务执行即将开始；下载项、仓库地址与任务阶段会实时写入构建日志..."
    exec "$JAVA_EXEC" $GRADLE_JVM_MEMORY_OPTS \
        -Dorg.gradle.appname=gradle \
        -Dorg.gradle.installation.dir="$GRADLE_HOME" \
        $JAVA_RUNTIME_OPTS \
        -classpath "$GRADLE_HOME/lib/*" \
        org.gradle.launcher.GradleMain $TASK $EXTRA_ARGS
elif [ -d /opt/gradle-8.7/lib ]; then
    echo "==> [WanXiang Build] 调度官方独立完整版 Gradle 8.7 执行构建..."
    exec "$JAVA_EXEC" $GRADLE_JVM_MEMORY_OPTS \
        -Dorg.gradle.appname=gradle \
        -Dorg.gradle.installation.dir=/opt/gradle-8.7 \
        $JAVA_RUNTIME_OPTS \
        -classpath "/opt/gradle-8.7/lib/*" \
        org.gradle.launcher.GradleMain $TASK $EXTRA_ARGS
elif [ -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "==> [WanXiang Build] 调度 $GRADLE_HOME/bin/gradle 执行构建..."
    exec "$GRADLE_HOME/bin/gradle" $TASK $EXTRA_ARGS
elif [ -f ./gradlew ] && [ -f ./gradle/wrapper/gradle-wrapper.jar ]; then
    echo "==> [WanXiang Build] 调度项目本地 Gradle Wrapper 执行构建..."
    chmod +x ./gradlew
    exec ./gradlew $TASK $EXTRA_ARGS
elif command -v gradle >/dev/null 2>&1; then
    echo "==> [WanXiang Build] 调度系统 Gradle 执行构建..."
    exec gradle $TASK $EXTRA_ARGS
else
    echo "==> [WanXiang Build] ❌ 未找到有效的 Gradle 执行环境，请在插件中心装配【Android & 移动全栈开发套件】"
    exit 127
fi
