#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - Flutter SDK Environment Setup
# ==============================================================================
set -e

echo "==> [WanXiang] 正在初始化 Flutter 跨端开发环境..."

mkdir -p /opt/wanxiang/locks
command -v flock >/dev/null 2>&1 || {
    echo "!! [WanXiang] 缺少 flock，无法安全装配 Flutter 工具链"
    exit 1
}
exec 9>/opt/wanxiang/locks/android-toolchain.lock
flock -x -w 1800 9 || {
    echo "!! [WanXiang] Android/Flutter 工具链正被构建任务使用，等待超时"
    exit 1
}

# 引入共享镜像库（全量节点 + 并行测速函数）
. /opt/wanxiang/scripts/wanxiang-mirror-lib.sh

# Flutter's artifact and pub mirrors avoid routing the large SDK cache through
# GitHub/pub.dev when the Android suite is installed in mainland China.
export PUB_HOSTED_URL="${PUB_HOSTED_URL:-https://pub.flutter-io.cn}"
export FLUTTER_STORAGE_BASE_URL="${FLUTTER_STORAGE_BASE_URL:-https://storage.flutter-io.cn}"

mkdir -p /opt/flutter /usr/local/bin /usr/bin 2>/dev/null || true

# The official Linux archive is x86_64-only.  Always require the community
# native ARM64 archive marker so an older x86_64 installation is replaced.
FLUTTER_ARM64_MARKER=/opt/flutter/.wanxiang-arm64

if [ ! -f "${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar" ]; then
    echo "!! [WanXiang] Flutter APK 构建依赖 Android 核心基础环境 (Platform 34)，请先安装 android-core"
fi

# 1. 下载 Flutter SDK 压缩包（HTTP Range 分片 + 断点续传）
if [ ! -f /opt/flutter/bin/flutter ] || [ ! -f "$FLUTTER_ARM64_MARKER" ]; then
    if [ -n "${WANXIANG_FLUTTER_ARCHIVE:-}" ] && [ -s "$WANXIANG_FLUTTER_ARCHIVE" ]; then
        echo "==> [WanXiang] 使用应用内下载器已准备的 Flutter SDK 压缩包：$WANXIANG_FLUTTER_ARCHIVE"
        FLUTTER_ARCHIVE="$WANXIANG_FLUTTER_ARCHIVE"
    else
    echo "==> [WanXiang] 正在获取 Flutter stable SDK 发布信息 (固定版本)..."
    # 固定到特定 tag，避免 latest 每次更新抬高 Gradle/AGP/Kotlin 最低版本要求。
    # 本版本对应的最低工具链：Gradle 8.14+ / AGP 8.11.1+ / Kotlin 2.2.20+。
    FLUTTER_PINNED_TAG="flutter-3.47.1-87-linux"
    FLUTTER_META="/tmp/wanxiang-flutter-releases.json"
    FLUTTER_META_URL="https://api.github.com/repos/MohamedAlkindi/flutter-native-arm64/releases/tags/${FLUTTER_PINNED_TAG}"
    rm -f "$FLUTTER_META"
    curl -fsSL --retry 4 --retry-all-errors --connect-timeout 20 --max-time 120 \
        "$FLUTTER_META_URL" -o "$FLUTTER_META" || \
    curl -fsSL --retry 4 --retry-all-errors --connect-timeout 20 --max-time 120 \
        "https://gh.dpik.top/$FLUTTER_META_URL" -o "$FLUTTER_META" || {
        echo "!! [WanXiang] Flutter 发布信息获取失败"
        exit 1
    }
    command -v jq >/dev/null 2>&1 || {
        echo "!! [WanXiang] 缺少 jq，无法解析 Flutter 发布索引"
        exit 1
    }
    FLUTTER_ARCHIVE_REL=$(jq -r '[.assets[] | select(.name | contains("linux_arm64_android_web_sdk"))][0].browser_download_url // empty' "$FLUTTER_META")
    FLUTTER_SHA256=""
    rm -f "$FLUTTER_META"
    [ -n "$FLUTTER_ARCHIVE_REL" ] || {
        echo "!! [WanXiang] 未找到可用的 Flutter stable Linux SDK"
        exit 1
    }

    FLUTTER_ARCHIVE_NAME=$(basename "$FLUTTER_ARCHIVE_REL")
    FLUTTER_ARCHIVE="/tmp/$FLUTTER_ARCHIVE_NAME"

    # 下载一个 Range 分片并在已有内容后继续，网络中断时可复用已完成字节。
    download_flutter_chunk() {
        chunk_url="$1"
        chunk_start="$2"
        chunk_end="$3"
        chunk_file="$4"
        chunk_size=$((chunk_end - chunk_start + 1))
        existing=0
        [ -f "$chunk_file" ] && existing=$(wc -c < "$chunk_file" 2>/dev/null || echo 0)
        if [ "$existing" -eq "$chunk_size" ]; then
            return 0
        fi
        [ "$existing" -gt "$chunk_size" ] && { rm -f "$chunk_file"; existing=0; }
        request_start=$((chunk_start + existing))
        chunk_part="${chunk_file}.part"
        rm -f "$chunk_part"
        curl -fL --retry 5 --retry-all-errors --connect-timeout 20 --max-time 1800 \
            -r "${request_start}-${chunk_end}" "$chunk_url" -o "$chunk_part"
        cat "$chunk_part" >> "$chunk_file"
        rm -f "$chunk_part"
        [ "$(wc -c < "$chunk_file")" -eq "$chunk_size" ]
    }

    download_flutter_archive() {
        archive_url="$1"
        echo "==> [WanXiang] 尝试 Flutter SDK 压缩包: $archive_url"
        header_file="${FLUTTER_ARCHIVE}.headers"
        curl -fsSL --retry 3 --retry-all-errors --connect-timeout 20 --max-time 60 \
            -r 0-0 -D "$header_file" -o /dev/null "$archive_url" || return 1
        total_size=$(awk 'tolower($1) == "content-range:" {split($3,a,"/"); print a[2]; exit} tolower($1) == "content-length:" {gsub("\r", "", $2); print $2; exit}' "$header_file")
        rm -f "$header_file"
        case "$total_size" in ''|*[!0-9]*) return 1 ;; esac
        [ "$total_size" -gt 1048576 ] || return 1

        # If the origin ignores Range and returns the full archive, use a
        # single resumable transfer instead of concatenating invalid chunks.
        range_probe="${FLUTTER_ARCHIVE}.range-probe"
        range_headers="${range_probe}.headers"
        curl -fsSL --retry 2 --retry-all-errors --connect-timeout 20 --max-time 60 \
            -r 0-0 -D "$range_headers" -o "$range_probe" "$archive_url" || true
        if ! grep -q " 206 " "$range_headers" 2>/dev/null; then
            rm -f "$range_probe" "$range_headers"
            curl -fL --retry 8 --retry-all-errors --connect-timeout 20 --max-time 3600 \
                -C - "$archive_url" -o "$FLUTTER_ARCHIVE" || return 1
        else
            rm -f "$range_probe" "$range_headers"
            chunk_dir="${FLUTTER_ARCHIVE}.chunks"
            mkdir -p "$chunk_dir"
            chunk_count=4
            chunk_pids=""
            chunk_failed=0
            i=0
            while [ "$i" -lt "$chunk_count" ]; do
                start=$((i * total_size / chunk_count))
                end=$(((i + 1) * total_size / chunk_count - 1))
                [ "$i" -eq $((chunk_count - 1)) ] && end=$((total_size - 1))
                download_flutter_chunk "$archive_url" "$start" "$end" "$chunk_dir/part-$i" &
                chunk_pids="$chunk_pids $!"
                i=$((i + 1))
            done
            for pid in $chunk_pids; do
                wait "$pid" || chunk_failed=1
            done
            if [ "$chunk_failed" -ne 0 ]; then
                rm -rf "$chunk_dir"
                return 1
            fi
            rm -f "$FLUTTER_ARCHIVE"
            i=0
            while [ "$i" -lt "$chunk_count" ]; do
                cat "$chunk_dir/part-$i" >> "$FLUTTER_ARCHIVE"
                i=$((i + 1))
            done
            rm -rf "$chunk_dir"
        fi
        [ "$(wc -c < "$FLUTTER_ARCHIVE")" -eq "$total_size" ] || return 1
        if [ -n "$FLUTTER_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
            echo "$FLUTTER_SHA256  $FLUTTER_ARCHIVE" | sha256sum -c - >/dev/null 2>&1 || return 1
        fi
        return 0
    }

    FLUTTER_SDK_READY=0
    case "$FLUTTER_ARCHIVE" in
        /tmp/*) rm -f "$FLUTTER_ARCHIVE" ;;
    esac
    # 候选镜像：github.akams.cn 节点 + 国内代理，末尾官方直连兜底。
    echo "==> [WanXiang] 正在并行测速选择最快镜像 (github.akams.cn 全量节点)..."
    FLUTTER_FAST_BASE=$(pick_fastest_mirror "$FLUTTER_ARCHIVE_REL")
    FLUTTER_CANDIDATES=""
    if [ -n "$FLUTTER_FAST_BASE" ]; then
        FLUTTER_CANDIDATES="${FLUTTER_FAST_BASE}${FLUTTER_ARCHIVE_REL}"
        echo "==> [WanXiang] 已选择最快镜像: $FLUTTER_FAST_BASE"
    else
        echo "==> [WanXiang] 镜像全部不可用，直接官方直连"
    fi
    for FLUTTER_BASE in $WANXIANG_MIRROR_NODES; do
        [ -n "$FLUTTER_BASE" ] || continue
        FLUTTER_CANDIDATES="$FLUTTER_CANDIDATES ${FLUTTER_BASE}${FLUTTER_ARCHIVE_REL}"
    done
    FLUTTER_CANDIDATES="$FLUTTER_CANDIDATES $FLUTTER_ARCHIVE_REL"
    for FLUTTER_URL in $FLUTTER_CANDIDATES; do
        if download_flutter_archive "$FLUTTER_URL"; then
            FLUTTER_SDK_READY=1
            break
        fi
        echo "!! [WanXiang] Flutter SDK 压缩包下载或校验失败，切换线路"
        rm -f "$FLUTTER_ARCHIVE"
        rm -rf "${FLUTTER_ARCHIVE}.chunks"
    done
    [ "$FLUTTER_SDK_READY" -eq 1 ] || {
        echo "!! [WanXiang] Flutter SDK 下载失败，未执行 Git 全量克隆"
        exit 1
    }
    fi

    echo "==> [WanXiang] 正在校验并解压 Flutter SDK..."
    FLUTTER_STAGING="/tmp/wanxiang-flutter-staging"
    rm -rf "$FLUTTER_STAGING"
    mkdir -p "$FLUTTER_STAGING"
    # The community ARM64 release is tar.gz, while official Flutter releases
    # are tar.xz. Select the decompressor from the archive suffix and validate
    # the stream before extracting so a stale/HTML download gets a useful error.
    case "$FLUTTER_ARCHIVE" in
        *.tar.gz|*.tgz|*.gz)
            gzip -t "$FLUTTER_ARCHIVE" || {
                echo "!! [WanXiang] Flutter SDK gzip 压缩包损坏：$FLUTTER_ARCHIVE"
                exit 1
            }
            tar -xzf "$FLUTTER_ARCHIVE" -C "$FLUTTER_STAGING"
            ;;
        *.tar.xz|*.txz|*.xz)
            xz -t "$FLUTTER_ARCHIVE" || {
                echo "!! [WanXiang] Flutter SDK xz 压缩包损坏：$FLUTTER_ARCHIVE"
                exit 1
            }
            tar -xJf "$FLUTTER_ARCHIVE" -C "$FLUTTER_STAGING"
            ;;
        *.tar)
            tar -tf "$FLUTTER_ARCHIVE" >/dev/null || {
                echo "!! [WanXiang] Flutter SDK tar 包损坏：$FLUTTER_ARCHIVE"
                exit 1
            }
            tar -xf "$FLUTTER_ARCHIVE" -C "$FLUTTER_STAGING"
            ;;
        *)
            echo "!! [WanXiang] 无法识别 Flutter SDK 压缩格式：$FLUTTER_ARCHIVE"
            file "$FLUTTER_ARCHIVE" 2>/dev/null || true
            exit 1
            ;;
    esac
    [ -f "$FLUTTER_STAGING/flutter/bin/flutter" ] || {
        echo "!! [WanXiang] Flutter SDK 压缩包结构无效"
        exit 1
    }
    rm -rf /opt/flutter
    mv "$FLUTTER_STAGING/flutter" /opt/flutter
    touch "$FLUTTER_ARM64_MARKER"
    rm -rf "$FLUTTER_STAGING"
    case "$FLUTTER_ARCHIVE" in
        /tmp/*) rm -f "$FLUTTER_ARCHIVE" ;;
    esac
fi

# 2. 建立全局软链接并授权
if [ -f /opt/flutter/bin/flutter ]; then
    chmod +x /opt/flutter/bin/flutter /opt/flutter/bin/dart 2>/dev/null || true
    ln -sf /opt/flutter/bin/flutter /usr/local/bin/flutter 2>/dev/null || true
    ln -sf /opt/flutter/bin/flutter /usr/bin/flutter 2>/dev/null || true
    ln -sf /opt/flutter/bin/dart /usr/local/bin/dart 2>/dev/null || true
    ln -sf /opt/flutter/bin/dart /usr/bin/dart 2>/dev/null || true
    echo "==> [WanXiang] Flutter & Dart 软链接配置就绪"
    /opt/flutter/bin/flutter config --no-analytics >/dev/null 2>&1 || true
    PUB_HOSTED_URL="$PUB_HOSTED_URL" FLUTTER_STORAGE_BASE_URL="$FLUTTER_STORAGE_BASE_URL" \
        /opt/flutter/bin/flutter precache --android >/dev/null 2>&1 || true

    # 补齐 Flutter Engine Android ARM64 原生 gen_snapshot 路径
    ENGINE_DIR="/opt/flutter/bin/cache/artifacts/engine"
    if [ -x "$ENGINE_DIR/linux-arm64/gen_snapshot" ]; then
        for mode in release profile; do
            target_dir="$ENGINE_DIR/android-arm64-$mode/linux-arm64"
            mkdir -p "$target_dir" 2>/dev/null || true
            if [ ! -e "$target_dir/gen_snapshot" ]; then
                ln -sf "$ENGINE_DIR/linux-arm64/gen_snapshot" "$target_dir/gen_snapshot" 2>/dev/null || true
                echo "==> [WanXiang] 已链接原生 ARM64 gen_snapshot -> $target_dir/gen_snapshot"
            fi
        done
    fi
fi

if [ ! -f /opt/flutter/bin/flutter ]; then
    echo "!! [WanXiang] Flutter SDK 未就位，安装失败"
    exit 1
fi
echo "==> [WanXiang] ✅ Flutter 跨端开发环境配置完成！"
