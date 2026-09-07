#!/bin/sh
# ==============================================================================
# WanXiang - pinned Linux AArch64 NDK provisioner
#
# Installs lzhiyong/termux-ndk as an immutable, checksum-addressed artifact.
# The upstream directory is intentionally named linux-x86_64 because AGP/NDK
# use that Linux host tag; the executables inside must be AArch64.
# ==============================================================================
set -e

SDK_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK_VERSION="29.0.14206865"
NDK_RELEASE="r29"
NDK_ARCHIVE_NAME="android-ndk-r29-aarch64.tar.xz"
NDK_SHA256="02e10e4ddfe8deaeb0bd0cf29d04c981ed5bc8a5d6b560ebb9e7661f472d684b"
NDK_UPSTREAM_URL="https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/${NDK_ARCHIVE_NAME}"
# 镜像节点与测速函数统一来自 wanxiang-mirror-lib.sh（github.akams.cn 全量节点）

TOOLCHAIN_ROOT="/opt/wanxiang/toolchains/android"
NDK_ARTIFACT_DIR="$TOOLCHAIN_ROOT/ndk/artifacts/$NDK_SHA256"
NDK_PATH="$NDK_ARTIFACT_DIR/ndk"
NDK_ENV_FILE="$TOOLCHAIN_ROOT/ndk/wanxiang-ndk.env"
NDK_SDK_VIEW="$SDK_HOME/ndk/$NDK_VERSION"
LOCK_FILE="/opt/wanxiang/locks/android-toolchain.lock"

mkdir -p /opt/wanxiang/locks "$TOOLCHAIN_ROOT/ndk/artifacts" "$SDK_HOME/ndk" /tmp

if [ "${WANXIANG_TOOLCHAIN_LOCK_HELD:-0}" != "1" ]; then
    command -v flock >/dev/null 2>&1 || {
        echo "!! [WanXiang] 缺少 flock，无法安全装配 Android 工具链"
        exit 1
    }
    exec 9>"$LOCK_FILE"
    flock -x -w 1800 9 || {
        echo "!! [WanXiang] Android 工具链正被构建任务使用，等待超时"
        exit 1
    }
fi

# 引入共享镜像库（全量节点 + 并行测速函数）
. /opt/wanxiang/scripts/wanxiang-mirror-lib.sh

elf_machine_is_aarch64() {
    target="$1"
    [ -f "$target" ] || return 1
    machine=$(od -An -tu2 -j18 -N2 "$target" 2>/dev/null | tr -d '[:space:]')
    [ "$machine" = "183" ]
}

validate_ndk() {
    root="$1"
    strip="$root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    clang="$root/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    [ -f "$root/source.properties" ] || return 1
    grep -Eq "^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*$NDK_VERSION([[:space:]]*)$" \
        "$root/source.properties" || return 1
    [ -x "$strip" ] && [ -x "$clang" ] || return 1
    elf_machine_is_aarch64 "$strip" || return 1
    elf_machine_is_aarch64 "$clang" || return 1
    "$strip" --version >/dev/null 2>&1 || return 1
    "$clang" --version >/dev/null 2>&1 || return 1
}

if ! validate_ndk "$NDK_PATH"; then
    if [ -e "$NDK_ARTIFACT_DIR" ]; then
        echo "!! [WanXiang] ARM64 NDK 不可变制品发生完整性漂移，拒绝原位替换: $NDK_ARTIFACT_DIR"
        echo "!! [WanXiang] 请先通过诊断/清理流程隔离损坏制品后再装配"
        exit 1
    fi
    echo "==> [WanXiang] 正在部署 lzhiyong/termux-ndk $NDK_RELEASE ($NDK_VERSION, Linux AArch64)..."
    archive="/tmp/$NDK_ARCHIVE_NAME"
    staging="/tmp/wanxiang-termux-ndk-staging.$$"
    pending="$TOOLCHAIN_ROOT/ndk/artifacts/.pending-$NDK_SHA256-$$"
    rm -f "$archive"
    rm -rf "$staging" "$pending"

    # 自动测速选最快镜像，最快失败则按序回退其余全量节点，官方直连兜底。
    echo "==> [WanXiang] 正在并行测速选择最快镜像 (github.akams.cn 全量节点)..."
    NDK_FAST_BASE=$(pick_fastest_mirror "$NDK_UPSTREAM_URL")
    ORDERED_URLS=""
    if [ -n "$NDK_FAST_BASE" ]; then
        ORDERED_URLS="${NDK_FAST_BASE}${NDK_UPSTREAM_URL}"
        echo "==> [WanXiang] 已选择最快镜像: $NDK_FAST_BASE"
    else
        echo "==> [WanXiang] 镜像全部不可用，直接官方直连"
    fi
    for base in $WANXIANG_MIRROR_NODES; do
        [ -n "$base" ] || continue
        ORDERED_URLS="$ORDERED_URLS ${base}${NDK_UPSTREAM_URL}"
    done
    ORDERED_URLS="$ORDERED_URLS $NDK_UPSTREAM_URL"
    downloaded=0
    for url in $ORDERED_URLS; do
        echo "==> [WanXiang] 尝试下载 ARM64 NDK: $url"
        if curl -fL --retry 4 --retry-all-errors --connect-timeout 20 --max-time 1800 \
            "$url" -o "$archive" 2>/dev/null && [ -s "$archive" ]; then
            if echo "$NDK_SHA256  $archive" | sha256sum -c - >/dev/null 2>&1; then
                downloaded=1
                break
            fi
            echo "!! [WanXiang] ARM64 NDK SHA-256 校验失败，切换镜像"
            rm -f "$archive"
        fi
    done
    [ "$downloaded" -eq 1 ] || {
        echo "!! [WanXiang] lzhiyong/termux-ndk 下载失败"
        exit 1
    }

    # The archive is trusted only after the pinned digest matches. Still reject
    # absolute and parent-traversal entries before extraction.
    if tar -tJf "$archive" | awk '
        /^\// { bad=1 }
        /(^|\/)\.\.($|\/)/ { bad=1 }
        END { exit bad ? 1 : 0 }
    '; then
        :
    else
        echo "!! [WanXiang] ARM64 NDK 归档包含不安全路径"
        rm -f "$archive"
        exit 1
    fi

    mkdir -p "$staging"
    tar -xJf "$archive" -C "$staging"
    rm -f "$archive"
    source_properties=$(find "$staging" -mindepth 2 -maxdepth 4 -type f -name source.properties -print -quit 2>/dev/null || true)
    [ -n "$source_properties" ] || {
        echo "!! [WanXiang] ARM64 NDK 归档缺少 source.properties"
        rm -rf "$staging"
        exit 1
    }
    extracted_ndk=$(dirname "$source_properties")
    chmod 755 "$extracted_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/"* 2>/dev/null || true
    validate_ndk "$extracted_ndk" || {
        echo "!! [WanXiang] ARM64 NDK ELF、版本或运行验证失败"
        rm -rf "$staging"
        exit 1
    }

    mkdir -p "$pending"
    mv "$extracted_ndk" "$pending/ndk"
    validate_ndk "$pending/ndk" || {
        echo "!! [WanXiang] ARM64 NDK staging 验证失败"
        rm -rf "$staging" "$pending"
        exit 1
    }

    mv "$pending" "$NDK_ARTIFACT_DIR"
    rm -rf "$staging"
fi

validate_ndk "$NDK_PATH" || {
    echo "!! [WanXiang] 固定 ARM64 NDK 不可用: $NDK_PATH"
    exit 1
}

# Compatibility views are replaceable symlinks; build injection always uses
# the immutable digest path above, never these links.
if [ -e "$NDK_SDK_VIEW" ] && [ ! -L "$NDK_SDK_VIEW" ]; then
    displaced="$SDK_HOME/ndk/.replaced-non-wanxiang-$NDK_VERSION-$(date +%s)"
    mv "$NDK_SDK_VIEW" "$displaced"
    echo "==> [WanXiang] 已隔离同版本的非万象 NDK: $displaced"
fi
sdk_link_tmp="$SDK_HOME/ndk/.wanxiang-$NDK_VERSION-$$"
rm -f "$sdk_link_tmp"
ln -s "$NDK_PATH" "$sdk_link_tmp"
mv -Tf "$sdk_link_tmp" "$NDK_SDK_VIEW"
ln -sfn "$NDK_PATH" "$TOOLCHAIN_ROOT/ndk/current"

env_tmp="${NDK_ENV_FILE}.tmp.$$"
cat > "$env_tmp" << EOF
export WANXIANG_NDK_PATH="$NDK_PATH"
export WANXIANG_NDK_VERSION="$NDK_VERSION"
export WANXIANG_NDK_SHA256="$NDK_SHA256"
export ANDROID_NDK_HOME="$NDK_PATH"
export ANDROID_NDK_ROOT="$NDK_PATH"
export WANXIANG_LLVM_STRIP_PATH="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
EOF
mv -f "$env_tmp" "$NDK_ENV_FILE"

# Force all AGP builds (including direct ./gradlew invocations) to the pinned
# immutable NDK path. android.ndkPath has precedence in AGP's NDK locator. A
# projectsEvaluated guard rejects any later script that tries to redirect it.
mkdir -p /root/.gradle/init.d
init_tmp="/root/.gradle/init.d/wanxiang-android-ndk.gradle.tmp.$$"
cat > "$init_tmp" << EOF
// Managed by WanXiang. Local policy only; never downloaded from a remote catalog.
def wanxiangNdkPath = '$NDK_PATH'
def wanxiangAndroidPluginIds = [
    'com.android.application',
    'com.android.library',
    'com.android.dynamic-feature',
    'com.android.test',
    'com.android.kotlin.multiplatform.library'
]

gradle.beforeProject { project ->
    wanxiangAndroidPluginIds.each { pluginId ->
        project.pluginManager.withPlugin(pluginId) {
            def androidExtension = project.extensions.findByName('android')
            if (androidExtension != null && androidExtension.hasProperty('ndkPath')) {
                androidExtension.ndkPath = wanxiangNdkPath
                project.logger.info("WanXiang ARM64 NDK: " + wanxiangNdkPath)
            }
        }
    }
}

gradle.projectsEvaluated {
    gradle.rootProject.allprojects.each { project ->
        def androidExtension = project.extensions.findByName('android')
        if (androidExtension != null && androidExtension.hasProperty('ndkPath')) {
            def effective = androidExtension.ndkPath == null ? '' : new File(androidExtension.ndkPath.toString()).canonicalPath
            def expected = new File(wanxiangNdkPath).canonicalPath
            if (effective != expected) {
                throw new GradleException("WanXiang blocked Android NDK path drift: " + effective + " != " + expected)
            }
        }
    }
}
EOF
mv -f "$init_tmp" /root/.gradle/init.d/wanxiang-android-ndk.gradle

echo "==> [WanXiang] ✅ 固定 ARM64 NDK: $NDK_PATH"
