#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - JADX-CLI Reverse Engineering Suite Setup
# 加固版：多镜像重试下载、解包产物完整性校验（bin + lib）、Java 运行时预检。
# ==============================================================================
set -e

echo "==> [WanXiang] 正在初始化 JADX-CLI 逆向审计工具包..."

mkdir -p /opt/jadx /usr/local/bin /usr/bin /tmp 2>/dev/null || true

# Use the APK-bundled JDK-jar unzip adapter when the rootfs has no native
# unzip/BusyBox. This keeps reverse-engineering setup independent of dpkg.
if ! command -v unzip >/dev/null 2>&1 && [ -x "${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}/unzip" ]; then
    ln -sf "${WANXIANG_TOOL_DIR:-/opt/wanxiang/tools}/unzip" /usr/local/bin/unzip 2>/dev/null || true
    export PATH="/usr/local/bin:/usr/bin:$PATH"
fi

JADX_VERSION="v1.5.0"
JADX_UPSTREAM_URL="https://github.com/skylot/jadx/releases/download/${JADX_VERSION}/jadx-${JADX_VERSION}.zip"
# 引入共享镜像库，按全量节点构建下载列表（末尾官方直连兜底）
. /opt/wanxiang/scripts/wanxiang-mirror-lib.sh
JADX_URLS=""
for JADX_BASE in $WANXIANG_MIRROR_NODES; do
    [ -n "$JADX_BASE" ] || continue
    JADX_URLS="$JADX_URLS ${JADX_BASE}${JADX_UPSTREAM_URL}"
done
JADX_URLS="$JADX_URLS $JADX_UPSTREAM_URL"

# Java 运行时预检（jadx 是 Java 程序，无 JVM 时给出明确指引而非静默失败）
JAVA_OK=0
if command -v java >/dev/null 2>&1 || [ -x /usr/lib/jvm/java-17-openjdk-arm64/bin/java ] || [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_OK=1
fi
if [ "$JAVA_OK" -ne 1 ]; then
    echo "==> [WanXiang] ⚠️ 未检测到 Java 运行时，JADX 需要 JVM 才能运行。"
    echo "==> [WanXiang] 💡 请确保已装配「Android & 移动全栈开发套件 → Android 核心基础环境」(OpenJDK 17)。"
fi

# 下载 + 解压（含产物校验，失败自动重试一轮）
download_and_extract() {
    rm -f /tmp/jadx.zip
    for url in $JADX_URLS; do
        echo "==> [WanXiang] 正在拉取 JADX-CLI ${JADX_VERSION} ($url)..."
        if curl -fsSL -m 300 "$url" -o /tmp/jadx.zip 2>/dev/null && [ -s /tmp/jadx.zip ]; then
            echo "==> [WanXiang] 下载完成 ($(wc -c < /tmp/jadx.zip) 字节)，正在解压到 /opt/jadx/..."
            rm -rf /opt/jadx/bin /opt/jadx/lib
            if (unzip -qo /tmp/jadx.zip -d /opt/jadx/ 2>/dev/null || \
                python3 -c 'import zipfile; zipfile.ZipFile("/tmp/jadx.zip").extractall("/opt/jadx/")' 2>/dev/null || \
                busybox unzip /tmp/jadx.zip -d /opt/jadx/ 2>/dev/null || true); then
                # 完整性校验：必须同时存在启动脚本与 lib 下的核心 jar
                if [ -f /opt/jadx/bin/jadx ] && ls /opt/jadx/lib/jadx-*.jar >/dev/null 2>&1; then
                    rm -f /tmp/jadx.zip
                    return 0
                fi
                echo "==> [WanXiang] ⚠️ 解压产物不完整 (缺少 bin/jadx 或 lib/jadx-*.jar)，尝试下一个镜像..."
            fi
            rm -f /tmp/jadx.zip
        fi
    done
    return 1
}

if [ ! -x /opt/jadx/bin/jadx ] || ! ls /opt/jadx/lib/jadx-*.jar >/dev/null 2>&1; then
    if ! download_and_extract; then
        echo "==> [WanXiang] ❌ JADX 下载失败：所有镜像均不可用，请检查网络后重新装配（可稍后在终端执行 /bin/sh /opt/wanxiang/scripts/setup_jadx.sh 重试）"
        exit 1
    fi
fi

# 建立全局软链接
if [ -f /opt/jadx/bin/jadx ]; then
    chmod +x /opt/jadx/bin/jadx 2>/dev/null || true
    ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx 2>/dev/null || true
    ln -sf /opt/jadx/bin/jadx /usr/bin/jadx 2>/dev/null || true
    echo "==> [WanXiang] JADX-CLI 软链接配置就绪 ($(ls /opt/jadx/lib/jadx-*.jar 2>/dev/null | head -1))"
fi

echo "==> [WanXiang] ✅ JADX 逆向分析工具包配置完成！"
