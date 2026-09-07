#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - Android Core Development Environment Setup
# ------------------------------------------------------------------------------
# 由【Android & 移动全栈开发套件 · android-core】组件在插件安装阶段一次性执行。
# ==============================================================================
set -e

SDK_HOME="/opt/android-sdk"
GRADLE_VER="8.14.2"
GRADLE_SHA256="7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999"
PLATFORM_ZIP="platform-34-ext7_r03.zip"
PLATFORM_SHA1="1f2e9478d6a7601425ceaa553311dc43191f103d"
BUILD_TOOLS_VERSION="35.0.0"
BUILD_TOOLS_ZIP="build-tools_r35_linux.zip"
BUILD_TOOLS_SHA1="2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc"
# lzhiyong/android-sdk-tools provides statically linked ARM64 host tools.  The
# Google SDK build-tools archive is still used for Java tools (d8/r8), while
# these binaries replace the x86_64 host executables.
ARM64_TOOLS_VERSION="35.0.2"
ARM64_TOOLS_SHA256="DB1CEA2C4454D5F9C5A802646B2D1CF560B4EE7BADBE23E51AB8E1881BB50FC2"
ARM64_TOOLS_ARTIFACT_ID="db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2"
ARM64_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/${ARM64_TOOLS_VERSION}/android-sdk-tools-static-aarch64.zip"
# 镜像节点与测速函数统一来自 wanxiang-mirror-lib.sh（github.akams.cn 全量节点）
ARM64_TOOLS_DIR="/opt/wanxiang/toolchains/android/sdk-tools/artifacts/${ARM64_TOOLS_ARTIFACT_ID}"
AAPT2_STABLE_PATH="/opt/wanxiang/android-sdk-tools/aapt2"
TOOLCHAIN_LOCK_FILE="/opt/wanxiang/locks/android-toolchain.lock"

mkdir -p /opt/wanxiang/locks
command -v flock >/dev/null 2>&1 || {
    echo "!! [WanXiang] 缺少 flock，无法安全装配 Android 工具链"
    exit 1
}
exec 9>"$TOOLCHAIN_LOCK_FILE"
flock -x -w 1800 9 || {
    echo "!! [WanXiang] Android 工具链正被构建任务使用，等待超时"
    exit 1
}
export WANXIANG_TOOLCHAIN_LOCK_HELD=1

# 引入共享镜像库（全量节点 + 并行测速函数）
. /opt/wanxiang/scripts/wanxiang-mirror-lib.sh

# Remove the obsolete x86_64 AAPT2 wrapper and payload from upgraded sandboxes.
rm -rf /opt/wanxiang/android-sdk-tools/qemu 2>/dev/null || true

echo "==> [WanXiang] 正在初始化 Android 核心基础环境 (插件装配期一次性部署)..."

mkdir -p /opt /usr/local/bin /usr/bin ${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}/bin /tmp 2>/dev/null || true

# ------------------------------------------------------------------------------
# 步骤 1：定位 JDK 目录
# ------------------------------------------------------------------------------
JDK_DIR=""
for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-aarch64 /usr/lib/jvm/default-java; do
    if [ -x "$candidate/bin/java" ]; then
        JDK_DIR="$candidate"
        break
    fi
done
if [ -z "$JDK_DIR" ]; then
    JDK_DIR=$(ls -d /usr/lib/jvm/*/ 2>/dev/null | while read d; do
        [ -x "${d}bin/java" ] && echo "${d%/}" && break
    done)
fi
[ -z "$JDK_DIR" ] && JDK_DIR="/usr/lib/jvm/java-17-openjdk-arm64"
JAVA_HOME_RESOLVED="$JDK_DIR"
echo "==> [WanXiang] JAVA_HOME: $JAVA_HOME_RESOLVED"

if [ -x "$JAVA_HOME_RESOLVED/bin/java" ] && [ ! -x /usr/bin/java ]; then
    ln -sf "$JAVA_HOME_RESOLVED/bin/java" /usr/local/bin/java 2>/dev/null || true
    ln -sf "$JAVA_HOME_RESOLVED/bin/java" /usr/bin/java 2>/dev/null || true
fi

# 工坊签名管理器（WorkshopSigningManager）与离线套件统一使用
# /opt/wanxiang/toolchains/android/jdk 作为 JAVA_HOME 规范路径。在线安装的
# apt JDK 位于 /usr/lib/jvm/*，这里补一个规范路径软链接，保证 keytool
# 在在线/离线两条安装路径下都从默认路径可达。若离线插件已部署 JDK 真身
# 目录（非软链接），保留不动。
TOOLCHAIN_JDK_LINK="/opt/wanxiang/toolchains/android/jdk"
if [ -d "$TOOLCHAIN_JDK_LINK" ] && [ ! -L "$TOOLCHAIN_JDK_LINK" ]; then
    echo "==> [WanXiang] 检测到离线套件 JDK 真身，跳过规范路径链接"
else
    mkdir -p /opt/wanxiang/toolchains/android /opt/wanxiang/bin 2>/dev/null || true
    ln -sfn "$JAVA_HOME_RESOLVED" "$TOOLCHAIN_JDK_LINK"
    # keytool / jarsigner 进 /opt/wanxiang/bin（PATH 首位），终端可直接调用。
    for jdk_cmd in keytool jarsigner; do
        if [ -x "$JAVA_HOME_RESOLVED/bin/$jdk_cmd" ]; then
            ln -sfn "$JAVA_HOME_RESOLVED/bin/$jdk_cmd" "/opt/wanxiang/bin/$jdk_cmd" 2>/dev/null || true
        fi
    done
    echo "==> [WanXiang] JDK 规范路径已就位: $TOOLCHAIN_JDK_LINK -> $JAVA_HOME_RESOLVED"
fi

# ------------------------------------------------------------------------------
# 步骤 2：修复 JDK 安全配置目录
# JDK 9+ 从 $JAVA_HOME/conf/security/java.security 读取主配置；同时维护
# lib/security 与 Debian 兼容路径，避免 PRoot 下链接断裂导致 JVM 崩溃。
# ------------------------------------------------------------------------------
SECURITY_DIR="$JAVA_HOME_RESOLVED/lib/security"
SECURITY_CONF_DIR="$JAVA_HOME_RESOLVED/conf/security"
mkdir -p "$SECURITY_DIR" "$SECURITY_CONF_DIR" /etc/java-17-openjdk/security /etc/ssl/certs/java 2>/dev/null || true
mkdir -p "$SECURITY_CONF_DIR/policy/unlimited" "$SECURITY_CONF_DIR/policy/limited" 2>/dev/null || true

echo "==> [WanXiang] 正在修复 JDK java.security 配置文件..."

SECURITY_TMP="${SECURITY_CONF_DIR}/java.security.wanxiang.tmp"
cat > "$SECURITY_TMP" << 'EOF'
security.provider.1=SUN
security.provider.2=SunRsaSign
security.provider.3=SunEC
security.provider.4=SunJSSE
security.provider.5=SunJCE
security.provider.6=SunJGSS
security.provider.7=SunSASL
security.provider.8=XMLDSig
security.provider.9=SunPCSC
security.provider.10=JdkLDAP
security.provider.11=JdkSASL
security.provider.12=SunPKCS11
securerandom.source=file:/dev/urandom
securerandom.strongAlgorithms=NativePRNGBlocking:SUN,DRBG:SUN
policy.provider=sun.security.provider.PolicyFile
policy.url.1=file:${java.home}/conf/security/java.policy
policy.url.2=file:${user.home}/.java.policy
policy.expandProperties=true
policy.allowSystemProperty=true
keystore.type=pkcs12
keystore.type.compat=true
ssl.KeyManagerFactory.algorithm=SunX509
ssl.TrustManagerFactory.algorithm=PKIX
crypto.policy=unlimited
networkaddress.cache.ttl=30
networkaddress.cache.negative.ttl=0
jdk.certpath.disabledAlgorithms=MD2, MD5, SHA1 jdkCA & usage TLSServer
jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1, DTLSv1.0, RC4, DES
jdk.tls.legacyAlgorithms=NULL, anon, RC4, DES, 3DES_EDE_CBC
jdk.tls.alpnCharset=ISO_8859_1
jdk.tls.client.protocols=TLSv1.2,TLSv1.3
jdk.tls.ephemeralDHKeySize=2048
ocsp.enable=false
EOF
mv -f "$SECURITY_TMP" "$SECURITY_CONF_DIR/java.security"
cp -f "$SECURITY_CONF_DIR/java.security" "$SECURITY_DIR/java.security" 2>/dev/null || true
cp -f "$SECURITY_CONF_DIR/java.security" /etc/java-17-openjdk/security/java.security 2>/dev/null || true

# 某些精简 rootfs 只带 java 二进制，没有 JDK 自带的 jurisdiction policy 文件。
# crypto.policy=unlimited 找不到这两个文件时，Gradle 初始化 SSL 会直接失败。
cat > "$SECURITY_CONF_DIR/policy/unlimited/default_local.policy" << 'EOF'
grant {
    permission javax.crypto.CryptoAllPermission;
};
EOF
cat > "$SECURITY_CONF_DIR/policy/unlimited/default_US_export.policy" << 'EOF'
grant {
    permission javax.crypto.CryptoAllPermission;
};
EOF

chmod 644 "$SECURITY_DIR/java.security" 2>/dev/null || true
chmod 644 "$SECURITY_CONF_DIR/java.security" /etc/java-17-openjdk/security/java.security 2>/dev/null || true
echo "==> [WanXiang] java.security 已写入 conf/security 与兼容路径"

# 部署 cacerts
if [ -s /opt/wanxiang/certs/cacerts ]; then
    cp -f /opt/wanxiang/certs/cacerts "$SECURITY_DIR/cacerts" 2>/dev/null || true
    cp -f /opt/wanxiang/certs/cacerts /etc/ssl/certs/java/cacerts 2>/dev/null || true
    echo "==> [WanXiang] cacerts 已部署"
fi

# 验证 java.security 可被 Java 加载
echo "==> [WanXiang] 验证 java.security 可被 JVM 加载..."
if "$JAVA_HOME_RESOLVED/bin/java" -version >/dev/null 2>&1; then
    echo "==> [WanXiang] 验证通过: java.security 可被 JVM 加载"
else
    echo "==> [WanXiang] 警告: java.security 验证未完成"
fi

# unzip is bundled in the APK as a JDK-jar adapter. Keep a BusyBox fallback for
# older rootfs images, but never install Ubuntu's fragile unzip deb via dpkg.
if ! command -v unzip >/dev/null 2>&1; then
    if [ -x "${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}/unzip" ]; then
        ln -sf "${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}/unzip" /usr/local/bin/unzip 2>/dev/null || true
        export PATH="/usr/local/bin:/usr/bin:$PATH"
    fi
    if ! command -v unzip >/dev/null 2>&1 && [ -x /bin/busybox ]; then
        ln -sf /bin/busybox /usr/local/bin/unzip 2>/dev/null || true
    fi
    command -v unzip >/dev/null 2>&1 || { echo "!! [WanXiang] 无法部署 unzip 解压工具"; exit 1; }
fi

# ------------------------------------------------------------------------------
# 步骤 3：部署 Android SDK 平台包 (android-34)
# ------------------------------------------------------------------------------
if [ ! -f "$SDK_HOME/platforms/android-34/android.jar" ]; then
    echo "==> [WanXiang] 正在从国内镜像拉取 Android 34 平台包 (android.jar, ~60MB)..."
    rm -rf /tmp/"$PLATFORM_ZIP" /tmp/android-platform-staging 2>/dev/null || true
    (curl -fsSL -m 300 "https://mirrors.cloud.tencent.com/AndroidSDK/$PLATFORM_ZIP" -o /tmp/"$PLATFORM_ZIP" 2>/dev/null || \
     curl -fsSL -m 300 "https://dl.google.com/android/repository/$PLATFORM_ZIP" -o /tmp/"$PLATFORM_ZIP" 2>/dev/null || true)

    if [ -f /tmp/"$PLATFORM_ZIP" ]; then
        if command -v sha1sum >/dev/null 2>&1; then
            echo "$PLATFORM_SHA1  /tmp/$PLATFORM_ZIP" | sha1sum -c - >/dev/null 2>&1 || {
                echo "!! [WanXiang] 平台包 SHA-1 校验失败，丢弃损坏文件"; rm -f /tmp/"$PLATFORM_ZIP"; }
        fi
    fi

    if [ -f /tmp/"$PLATFORM_ZIP" ]; then
        echo "==> [WanXiang] 正在解压平台包到 $SDK_HOME/platforms/android-34 ..."
        mkdir -p /tmp/android-platform-staging "$SDK_HOME/platforms"
        (unzip -qo /tmp/"$PLATFORM_ZIP" -d /tmp/android-platform-staging 2>/dev/null || \
         python3 -c "import zipfile; zipfile.ZipFile('/tmp/$PLATFORM_ZIP').extractall('/tmp/android-platform-staging')" 2>/dev/null || true)
        PLATFORM_SRC=$(dirname "$(find /tmp/android-platform-staging -name android.jar -print -quit 2>/dev/null)")
        if [ -n "$PLATFORM_SRC" ] && [ -f "$PLATFORM_SRC/android.jar" ]; then
            rm -rf "$SDK_HOME/platforms/android-34"
            mkdir -p "$SDK_HOME/platforms"
            mv "$PLATFORM_SRC" "$SDK_HOME/platforms/android-34"
        else
            echo "!! [WanXiang] 平台包解压失败，保留骨架目录待下次装配重试"
        fi
        rm -rf /tmp/"$PLATFORM_ZIP" /tmp/android-platform-staging 2>/dev/null || true
    else
        echo "!! [WanXiang] 平台包下载失败，保留骨架目录待下次装配重试"
    fi
else
    echo "==> [WanXiang] Android 34 平台包已存在，跳过"
fi

# ------------------------------------------------------------------------------
# 步骤 4：固定 SDK licenses 与 Build-Tools 组件元数据
# ------------------------------------------------------------------------------
mkdir -p "$SDK_HOME/licenses" /root/.android/licenses 2>/dev/null || true
cat > "$SDK_HOME/licenses/android-sdk-license" << 'EOF'
8933bad161af4178b1185d1a37fbf41ea5269ab8
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
84831b9409646a918e30573bab4c9c91346d8abd
EOF
cat > "$SDK_HOME/licenses/android-sdk-preview-license" << 'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
442ba07be04be7e4a4026ec1b1952dd8223c973a
d975f751698a77b662f1254ddbeed3901e976f5a
EOF
cp -f "$SDK_HOME/licenses/"* /root/.android/licenses/ 2>/dev/null || true

# 安装官方 Build-Tools 的 Java 工具（d8/r8/lib），再覆盖其中无法在 ARM64
# rootfs 运行的 x86_64 原生工具。这样 AGP 不会在构建阶段再次联网补组件。
BUILD_TOOLS_DIR="$SDK_HOME/build-tools/$BUILD_TOOLS_VERSION"
if [ ! -f "$BUILD_TOOLS_DIR/lib/d8.jar" ]; then
    echo "==> [WanXiang] 正在部署 Android Build-Tools $BUILD_TOOLS_VERSION (Java d8/r8)..."
    rm -rf /tmp/"$BUILD_TOOLS_ZIP" /tmp/android-build-tools-staging 2>/dev/null || true
    (curl -fsSL -m 300 "https://mirrors.cloud.tencent.com/AndroidSDK/$BUILD_TOOLS_ZIP" -o /tmp/"$BUILD_TOOLS_ZIP" 2>/dev/null || \
     curl -fsSL -m 300 "https://dl.google.com/android/repository/$BUILD_TOOLS_ZIP" -o /tmp/"$BUILD_TOOLS_ZIP" 2>/dev/null || true)
    if [ -f /tmp/"$BUILD_TOOLS_ZIP" ] && command -v sha1sum >/dev/null 2>&1; then
        echo "$BUILD_TOOLS_SHA1  /tmp/$BUILD_TOOLS_ZIP" | sha1sum -c - >/dev/null 2>&1 || rm -f /tmp/"$BUILD_TOOLS_ZIP"
    fi
    if [ -f /tmp/"$BUILD_TOOLS_ZIP" ]; then
        mkdir -p /tmp/android-build-tools-staging
        (unzip -qo /tmp/"$BUILD_TOOLS_ZIP" -d /tmp/android-build-tools-staging 2>/dev/null || \
         python3 -c "import zipfile; zipfile.ZipFile('/tmp/$BUILD_TOOLS_ZIP').extractall('/tmp/android-build-tools-staging')" 2>/dev/null || true)
        BUILD_TOOLS_SRC=$(find /tmp/android-build-tools-staging -name source.properties -print -quit 2>/dev/null | xargs -r dirname)
        if [ -n "$BUILD_TOOLS_SRC" ] && [ -f "$BUILD_TOOLS_SRC/lib/d8.jar" ]; then
            rm -rf "$BUILD_TOOLS_DIR"
            mkdir -p "$SDK_HOME/build-tools"
            mv "$BUILD_TOOLS_SRC" "$BUILD_TOOLS_DIR"
        fi
        rm -rf /tmp/"$BUILD_TOOLS_ZIP" /tmp/android-build-tools-staging 2>/dev/null || true
    fi
fi
mkdir -p "$BUILD_TOOLS_DIR"
if [ ! -f "$BUILD_TOOLS_DIR/source.properties" ]; then
    cat > "$BUILD_TOOLS_DIR/source.properties" << EOF
Pkg.Desc=Android SDK Build-Tools 35
Pkg.Revision=$BUILD_TOOLS_VERSION
Pkg.Path=build-tools;$BUILD_TOOLS_VERSION
EOF
fi
for tool in aapt aapt2 aidl zipalign apksigner; do
    toolPath=$(command -v "$tool" 2>/dev/null || true)
    if [ -n "$toolPath" ] && [ -x "$toolPath" ]; then
        ln -sf "$toolPath" "$BUILD_TOOLS_DIR/$tool" 2>/dev/null || true
    fi
done

# ------------------------------------------------------------------------------
# ARM64 native SDK tools (aapt2 is the important one for AGP)
# ------------------------------------------------------------------------------
if [ ! -x "$ARM64_TOOLS_DIR/build-tools/aapt2" ] || \
   ! "$ARM64_TOOLS_DIR/build-tools/aapt2" version >/dev/null 2>&1; then
    if [ -e "$ARM64_TOOLS_DIR" ]; then
        echo "!! [WanXiang] ARM64 SDK Tools 不可变制品发生完整性漂移，拒绝原位替换: $ARM64_TOOLS_DIR"
        exit 1
    fi
    echo "==> [WanXiang] 正在部署第三方 ARM64 Android SDK 工具 (aapt2/aidl/zipalign)..."
    ARM64_ARCHIVE="/tmp/android-sdk-tools-static-aarch64-${ARM64_TOOLS_VERSION}.zip"
    ARM64_STAGING="/tmp/android-sdk-tools-aarch64-staging"
    rm -f "$ARM64_ARCHIVE"
    rm -rf "$ARM64_STAGING"
    # 自动测速选最快镜像，最快失败则按序回退其余全量节点，官方直连兜底。
    echo "==> [WanXiang] 正在并行测速选择最快镜像 (github.akams.cn 全量节点)..."
    ARM64_FAST_BASE=$(pick_fastest_mirror "$ARM64_TOOLS_URL")
    ORDERED_TOOL_URLS=""
    if [ -n "$ARM64_FAST_BASE" ]; then
        ORDERED_TOOL_URLS="${ARM64_FAST_BASE}${ARM64_TOOLS_URL}"
        echo "==> [WanXiang] 已选择最快镜像: $ARM64_FAST_BASE"
    else
        echo "==> [WanXiang] 镜像全部不可用，直接官方直连"
    fi
    for base in $WANXIANG_MIRROR_NODES; do
        [ -n "$base" ] || continue
        ORDERED_TOOL_URLS="$ORDERED_TOOL_URLS ${base}${ARM64_TOOLS_URL}"
    done
    ORDERED_TOOL_URLS="$ORDERED_TOOL_URLS $ARM64_TOOLS_URL"
    ARM64_DOWNLOADED=0
    for ARM64_URL in $ORDERED_TOOL_URLS; do
        echo "==> [WanXiang] 尝试下载第三方 ARM64 工具包: $ARM64_URL"
        if curl -fsSL -m 300 "$ARM64_URL" -o "$ARM64_ARCHIVE" 2>/dev/null && [ -s "$ARM64_ARCHIVE" ]; then
            if command -v sha256sum >/dev/null 2>&1 &&
                ! echo "$ARM64_TOOLS_SHA256  $ARM64_ARCHIVE" | sha256sum -c - >/dev/null 2>&1; then
                echo "!! [WanXiang] ARM64 SDK 工具包 SHA-256 校验失败，尝试下一个镜像"
                rm -f "$ARM64_ARCHIVE"
            else
                ARM64_DOWNLOADED=1
                break
            fi
        fi
    done
    [ "$ARM64_DOWNLOADED" -eq 1 ] || {
        echo "!! [WanXiang] 第三方 ARM64 SDK 工具包下载失败，无法提供 ARM64 AAPT2"
        exit 1
    }
    if [ -f "$ARM64_ARCHIVE" ]; then
        mkdir -p "$ARM64_STAGING"
        if unzip -qo "$ARM64_ARCHIVE" -d "$ARM64_STAGING" 2>/dev/null; then
            # jar extraction does not preserve Unix executable bits; validate
            # presence first, then restore permissions after moving.
            if [ -f "$ARM64_STAGING/build-tools/aapt2" ]; then
                chmod 755 "$ARM64_STAGING/build-tools"/* 2>/dev/null || true
                AAPT2_MACHINE=$(od -An -tu2 -j18 -N2 "$ARM64_STAGING/build-tools/aapt2" 2>/dev/null | tr -d '[:space:]')
                [ "$AAPT2_MACHINE" = "183" ] || {
                    echo "!! [WanXiang] ARM64 AAPT2 ELF 架构校验失败: e_machine=$AAPT2_MACHINE"
                    rm -rf "$ARM64_STAGING"
                    exit 1
                }
                "$ARM64_STAGING/build-tools/aapt2" version >/dev/null 2>&1 || {
                    echo "!! [WanXiang] ARM64 AAPT2 无法在当前 RootFS 启动"
                    rm -rf "$ARM64_STAGING"
                    exit 1
                }
                mkdir -p "$(dirname "$ARM64_TOOLS_DIR")"
                mv "$ARM64_STAGING" "$ARM64_TOOLS_DIR"
            else
                echo "!! [WanXiang] ARM64 SDK 工具包缺少 build-tools/aapt2，忽略该包"
                rm -rf "$ARM64_STAGING"
            fi
        else
            echo "!! [WanXiang] ARM64 SDK 工具包解压失败"
            rm -rf "$ARM64_STAGING"
            exit 1
        fi
        rm -f "$ARM64_ARCHIVE"
    fi
fi

# Put the ARM64 tools first in PATH.  Keep the SDK directory's Java tools and
# metadata intact; only native host executables are replaced.
if [ -x "$ARM64_TOOLS_DIR/build-tools/aapt2" ] && \
   "$ARM64_TOOLS_DIR/build-tools/aapt2" version >/dev/null 2>&1; then
    for tool in aapt aapt2 aidl zipalign; do
        if [ -x "$ARM64_TOOLS_DIR/build-tools/$tool" ]; then
            ln -sf "$ARM64_TOOLS_DIR/build-tools/$tool" "$BUILD_TOOLS_DIR/$tool"
            ln -sf "$ARM64_TOOLS_DIR/build-tools/$tool" "/usr/local/bin/$tool" 2>/dev/null || true
            ln -sf "$ARM64_TOOLS_DIR/build-tools/$tool" "/usr/bin/$tool" 2>/dev/null || true
        fi
    done
    mkdir -p "$(dirname "$AAPT2_STABLE_PATH")"
    ln -sf "$ARM64_TOOLS_DIR/build-tools/aapt2" "$AAPT2_STABLE_PATH"
    # Formal builds use the immutable digest path. The stable link remains only
    # for old projects and interactive terminal compatibility.
    LEGACY_ARM64_TOOLS_DIR="/opt/wanxiang/android-sdk-tools/${ARM64_TOOLS_VERSION}"
    if [ -e "$LEGACY_ARM64_TOOLS_DIR" ] && [ ! -L "$LEGACY_ARM64_TOOLS_DIR" ]; then
        mv "$LEGACY_ARM64_TOOLS_DIR" "${LEGACY_ARM64_TOOLS_DIR}.legacy.$(date +%s)"
    fi
    ln -sfn "$ARM64_TOOLS_DIR" "$LEGACY_ARM64_TOOLS_DIR"
    export WANXIANG_AAPT2_PATH="$ARM64_TOOLS_DIR/build-tools/aapt2"
else
    export WANXIANG_AAPT2_PATH=""
fi

# Install the pinned Linux AArch64 NDK while the same exclusive toolchain lock
# is held. This prevents AGP from observing a half-installed SDK/NDK view.
/bin/sh /opt/wanxiang/scripts/setup_termux_ndk.sh
. /opt/wanxiang/toolchains/android/ndk/wanxiang-ndk.env

# Remove only WanXiang's legacy project-level AAPT2 overrides. Formal injection is
# now user-scoped and points at the immutable digest path, so projects cannot
# pin themselves back to a replaceable compatibility symlink.
if [ -d /workspace ]; then
    find /workspace -type f -name gradle.properties -exec sed -i \
        '\#^[[:space:]]*android\.aapt2FromMavenOverride[[:space:]]*=[[:space:]]*/opt/wanxiang/android-sdk-tools/#d' {} \; \
        2>/dev/null || true
    find /workspace -type f \( -name build.gradle -o -name build.gradle.kts \) -exec sed -i \
        -e 's/buildToolsVersion = "34\.0\.0"/buildToolsVersion = "35.0.0"/g' \
        -e 's/buildToolsVersion "34\.0\.0"/buildToolsVersion "35.0.0"/g' {} \; \
        2>/dev/null || true
fi

# ------------------------------------------------------------------------------
# 步骤 5：链接 ADB / AAPT / zipalign / apksigner
# ------------------------------------------------------------------------------
for tool in aapt aapt2 adb zipalign apksigner; do
    DEB_BIN=$(command -v "$tool" 2>/dev/null || echo "/usr/bin/$tool")
    if [ -x "$DEB_BIN" ] && [ ! -x "/usr/local/bin/$tool" ]; then
        ln -sf "$DEB_BIN" "/usr/local/bin/$tool" 2>/dev/null || true
    fi
done

# ------------------------------------------------------------------------------
# 步骤 6：部署官方独立 Gradle 8.14.2
# ------------------------------------------------------------------------------
if [ ! -f /opt/gradle-$GRADLE_VER/lib/gradle-launcher-$GRADLE_VER.jar ]; then
    echo "==> [WanXiang] 正在从国内加速镜像拉取 Gradle $GRADLE_VER (~120MB)..."
    rm -rf /tmp/gradle-$GRADLE_VER.zip /opt/gradle-$GRADLE_VER 2>/dev/null || true
    (curl -fsSL -m 600 https://mirrors.cloud.tencent.com/gradle/gradle-$GRADLE_VER-bin.zip -o /tmp/gradle-$GRADLE_VER.zip 2>/dev/null || \
     curl -fsSL -m 600 https://mirrors.huaweicloud.com/gradle/gradle-$GRADLE_VER-bin.zip -o /tmp/gradle-$GRADLE_VER.zip 2>/dev/null || true)

    if [ -f /tmp/gradle-$GRADLE_VER.zip ]; then
        if command -v sha256sum >/dev/null 2>&1; then
            echo "$GRADLE_SHA256  /tmp/gradle-$GRADLE_VER.zip" | sha256sum -c - >/dev/null 2>&1 || {
                echo "!! [WanXiang] Gradle 包 SHA-256 校验失败，丢弃损坏文件"; rm -f /tmp/gradle-$GRADLE_VER.zip; }
        fi
    fi

    if [ -f /tmp/gradle-$GRADLE_VER.zip ]; then
        echo "==> [WanXiang] 正在解压 Gradle $GRADLE_VER 到 /opt/..."
        (unzip -qo /tmp/gradle-$GRADLE_VER.zip -d /opt/ 2>/dev/null || \
         python3 -c "import zipfile; zipfile.ZipFile('/tmp/gradle-$GRADLE_VER.zip').extractall('/opt/')" 2>/dev/null || \
         busybox unzip /tmp/gradle-$GRADLE_VER.zip -d /opt/ 2>/dev/null || true)
        rm -f /tmp/gradle-$GRADLE_VER.zip
    fi
fi

if [ -d /opt/gradle-$GRADLE_VER/bin ]; then
    chmod +x /opt/gradle-$GRADLE_VER/bin/gradle 2>/dev/null || true
    ln -sf /opt/gradle-$GRADLE_VER/bin/gradle /usr/local/bin/gradle 2>/dev/null || true
    ln -sf /opt/gradle-$GRADLE_VER/bin/gradle /usr/bin/gradle 2>/dev/null || true
    echo "==> [WanXiang] Gradle $GRADLE_VER 就绪"
fi

# ------------------------------------------------------------------------------
# 步骤 7：全局 Gradle 镜像加速配置
# ------------------------------------------------------------------------------
mkdir -p /root/.gradle
cat << 'EOF' > /root/.gradle/init.gradle
// WanXiang: 全局强制阿里云镜像，避免 Gradle 从国外源拉取依赖（国内网络慢/被墙）。
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

# AGP normally resolves aapt2 from Maven and the Linux artifact is x86_64.
# Persist the native ARM64 override at Gradle user scope so plain `gradle` and
# project wrapper invocations are protected too, not only WanXiang's build scripts.
# Replace only the managed key and preserve every other user property.
GRADLE_PROPERTIES=/root/.gradle/gradle.properties
GRADLE_PROPERTIES_TMP="${GRADLE_PROPERTIES}.wanxiang.tmp"
if [ -n "$WANXIANG_AAPT2_PATH" ] && [ -x "$WANXIANG_AAPT2_PATH" ] && \
   "$WANXIANG_AAPT2_PATH" version >/dev/null 2>&1; then
    if [ -f "$GRADLE_PROPERTIES" ]; then
        sed \
            -e '/^[[:space:]]*android\.aapt2FromMavenOverride[[:space:]]*=/d' \
            -e '/^[[:space:]]*android\.builder\.sdkDownload[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.daemon[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.parallel[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.workers\.max[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.jvmargs[[:space:]]*=/d' \
            -e '/^[[:space:]]*kotlin\.daemon\.jvmargs[[:space:]]*=/d' \
            "$GRADLE_PROPERTIES" > "$GRADLE_PROPERTIES_TMP"
    else
        : > "$GRADLE_PROPERTIES_TMP"
    fi
    printf '\n# WanXiang: immutable ARM64 toolchain and mobile-safe build limits.\nandroid.aapt2FromMavenOverride=%s\nandroid.builder.sdkDownload=false\norg.gradle.daemon=false\norg.gradle.parallel=false\norg.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8\nsystemProp.org.gradle.internal.http.connectionTimeout=30000\nsystemProp.org.gradle.internal.http.socketTimeout=60000\nkotlin.daemon.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m\n' \
        "$WANXIANG_AAPT2_PATH" >> "$GRADLE_PROPERTIES_TMP"
    mv -f "$GRADLE_PROPERTIES_TMP" "$GRADLE_PROPERTIES"
    echo "==> [WanXiang] Gradle 全局 AAPT2 覆盖: $WANXIANG_AAPT2_PATH"
else
    echo "!! [WanXiang] AAPT2 无法启动，未写入 Gradle 全局覆盖"
    exit 1
fi

# ------------------------------------------------------------------------------
# 步骤 8：持久化核心环境变量（全局生效）
# ------------------------------------------------------------------------------
cat << EOF > /etc/profile.d/wanxiang-android.sh
# WanXiang Android development environment (managed by android-core plugin)
export JAVA_HOME="$JAVA_HOME_RESOLVED"
export ANDROID_HOME="$SDK_HOME"
export ANDROID_SDK_ROOT="$SDK_HOME"
export GRADLE_HOME="/opt/gradle-$GRADLE_VER"
export WANXIANG_AAPT2_PATH="${WANXIANG_AAPT2_PATH:-}"
export WANXIANG_NDK_PATH="$WANXIANG_NDK_PATH"
export WANXIANG_NDK_VERSION="$WANXIANG_NDK_VERSION"
export WANXIANG_NDK_SHA256="$WANXIANG_NDK_SHA256"
export ANDROID_NDK_HOME="$WANXIANG_NDK_PATH"
export ANDROID_NDK_ROOT="$WANXIANG_NDK_PATH"
export WANXIANG_LLVM_STRIP_PATH="$WANXIANG_LLVM_STRIP_PATH"
# /opt/wanxiang/bin 必须保持首位：终端与 Agent 直接执行 gradle/gradlew 时
# 先经过 WanXiang ARM64 工具链自检，再调度固定 Gradle。
export PATH="/opt/wanxiang/bin:\$JAVA_HOME/bin:\$GRADLE_HOME/bin:\$PATH"
# PRoot sandbox: use non-blocking entropy
export _JAVA_OPTIONS="-Djava.security.egd=file:/dev/urandom"
EOF

cat << EOF > /etc/environment
JAVA_HOME=$JAVA_HOME_RESOLVED
ANDROID_HOME=$SDK_HOME
ANDROID_SDK_ROOT=$SDK_HOME
GRADLE_HOME=/opt/gradle-$GRADLE_VER
WANXIANG_AAPT2_PATH=$WANXIANG_AAPT2_PATH
WANXIANG_NDK_PATH=$WANXIANG_NDK_PATH
WANXIANG_NDK_VERSION=$WANXIANG_NDK_VERSION
WANXIANG_NDK_SHA256=$WANXIANG_NDK_SHA256
ANDROID_NDK_HOME=$WANXIANG_NDK_PATH
ANDROID_NDK_ROOT=$WANXIANG_NDK_PATH
WANXIANG_LLVM_STRIP_PATH=$WANXIANG_LLVM_STRIP_PATH
PATH=/opt/wanxiang/bin:$JAVA_HOME_RESOLVED/bin:/opt/gradle-$GRADLE_VER/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
_JAVA_OPTIONS=-Djava.security.egd=file:/dev/urandom
EOF

if [ -f /root/.bashrc ] && ! grep -q "wanxiang-android" /root/.bashrc 2>/dev/null; then
    echo '. /etc/profile.d/wanxiang-android.sh 2>/dev/null || true' >> /root/.bashrc
fi
if [ -f /etc/bash.bashrc ] && ! grep -q "wanxiang-android" /etc/bash.bashrc 2>/dev/null; then
    echo '. /etc/profile.d/wanxiang-android.sh 2>/dev/null || true' >> /etc/bash.bashrc
fi

# ------------------------------------------------------------------------------
# 步骤 9：最终验证
# ------------------------------------------------------------------------------
echo "==> [WanXiang] 装配自检:"

[ -x "$JAVA_HOME_RESOLVED/bin/java" ] && echo "    [OK] Java 运行时" || { echo "    [MISS] Java 运行时"; exit 1; }
# 工坊签名（创建/导入 keystore）依赖规范路径下的 keytool，缺失时签名功能不可用。
[ -x /opt/wanxiang/toolchains/android/jdk/bin/keytool ] && echo "    [OK] keytool 签名工具链 (规范路径)" || { echo "    [MISS] keytool 签名工具链"; exit 1; }
[ -s "$SECURITY_CONF_DIR/java.security" ] && [ -f "$SECURITY_CONF_DIR/policy/unlimited/default_local.policy" ] && echo "    [OK] java.security + crypto policy" || { echo "    [MISS] java.security / crypto policy"; exit 1; }
[ -s "$SECURITY_DIR/cacerts" ] && "$JAVA_HOME_RESOLVED/bin/keytool" -list -keystore "$SECURITY_DIR/cacerts" -storetype PKCS12 -storepass changeit >/dev/null 2>&1 && echo "    [OK] Java cacerts (PKCS12)" || { echo "    [MISS] Java cacerts"; exit 1; }
[ -f "$SDK_HOME/platforms/android-34/android.jar" ] && echo "    [OK] Android Platform 34" || { echo "    [MISS] Android 平台包"; exit 1; }
[ -s "$SDK_HOME/licenses/android-sdk-license" ] && echo "    [OK] Android SDK licenses" || { echo "    [MISS] Android SDK licenses"; exit 1; }
[ -f "$BUILD_TOOLS_DIR/source.properties" ] && [ -f "$BUILD_TOOLS_DIR/lib/d8.jar" ] && echo "    [OK] Build-Tools $BUILD_TOOLS_VERSION (Java d8/r8 + ARM64 native tools)" || { echo "    [MISS] Build-Tools $BUILD_TOOLS_VERSION"; exit 1; }
[ -f "$WANXIANG_NDK_PATH/source.properties" ] && [ -x "$WANXIANG_NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ] && echo "    [OK] lzhiyong ARM64 NDK $WANXIANG_NDK_VERSION" || { echo "    [MISS] 固定 ARM64 NDK"; exit 1; }
[ -x "$WANXIANG_LLVM_STRIP_PATH" ] && "$WANXIANG_LLVM_STRIP_PATH" --version >/dev/null 2>&1 && echo "    [OK] NDK ARM64 llvm-strip" || { echo "    [MISS] NDK ARM64 llvm-strip"; exit 1; }
AAPT2_MACHINE=$(od -An -tu2 -j18 -N2 "$WANXIANG_AAPT2_PATH" 2>/dev/null | tr -d '[:space:]')
[ "$AAPT2_MACHINE" = "183" ] && [ -x "$WANXIANG_AAPT2_PATH" ] && "$WANXIANG_AAPT2_PATH" version >/dev/null 2>&1 && echo "    [OK] ARM64 AAPT2: $WANXIANG_AAPT2_PATH" || { echo "    [MISS] ARM64 AAPT2 未就位、架构错误或无法启动"; exit 1; }
[ -f /opt/gradle-$GRADLE_VER/lib/gradle-launcher-$GRADLE_VER.jar ] && echo "    [OK] Gradle $GRADLE_VER" || { echo "    [MISS] Gradle"; exit 1; }
[ -f /root/.gradle/init.gradle ] && echo "    [OK] 阿里云镜像" || { echo "    [MISS] Gradle 镜像配置"; exit 1; }
grep -Fqx "android.aapt2FromMavenOverride=$WANXIANG_AAPT2_PATH" /root/.gradle/gradle.properties && echo "    [OK] Gradle 全局 AAPT2 覆盖" || { echo "    [MISS] Gradle 全局 AAPT2 覆盖"; exit 1; }
grep -Fqx "android.builder.sdkDownload=false" /root/.gradle/gradle.properties && echo "    [OK] Gradle SDK 自动下载已禁用" || { echo "    [MISS] Gradle SDK 自动下载禁用策略"; exit 1; }
grep -Fqx "org.gradle.daemon=false" /root/.gradle/gradle.properties && \
grep -Fqx "org.gradle.parallel=false" /root/.gradle/gradle.properties && \
grep -Fqx "org.gradle.workers.max=2" /root/.gradle/gradle.properties && \
grep -Fq "org.gradle.jvmargs=-Xmx1024m" /root/.gradle/gradle.properties && \
    echo "    [OK] Gradle 移动端资源与 daemon 策略" || { echo "    [MISS] Gradle 移动端资源策略"; exit 1; }
[ -f /root/.gradle/init.d/wanxiang-android-ndk.gradle ] && grep -Fq "$WANXIANG_NDK_PATH" /root/.gradle/init.d/wanxiang-android-ndk.gradle && echo "    [OK] Gradle 固定 NDK 路径注入" || { echo "    [MISS] Gradle NDK 路径注入"; exit 1; }
grep -Fq "export JAVA_HOME=\"$JAVA_HOME_RESOLVED\"" /etc/profile.d/wanxiang-android.sh && echo "    [OK] JAVA_HOME 持久化环境" || { echo "    [MISS] JAVA_HOME 持久化环境"; exit 1; }

SSL_PROBE="/tmp/WanXiangSslProbe.java"
cat > "$SSL_PROBE" << 'EOF'
import javax.net.ssl.SSLContext;
public class WanXiangSslProbe {
    public static void main(String[] args) throws Exception {
        SSLContext.getDefault().createSSLEngine();
    }
}
EOF
if "$JAVA_HOME_RESOLVED/bin/java" \
    -Djavax.net.ssl.trustStore="$SECURITY_DIR/cacerts" \
    -Djavax.net.ssl.trustStoreType=PKCS12 \
    -Djavax.net.ssl.trustStorePassword=changeit \
    "$SSL_PROBE" >/dev/null 2>&1; then
    echo "    [OK] Java SSLContext"
else
    echo "    [MISS] Java SSLContext"
    rm -f "$SSL_PROBE"
    exit 1
fi
rm -f "$SSL_PROBE"

echo "==> [WanXiang] ✅ Android 核心基础环境插件装配完成！"
