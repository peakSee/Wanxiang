#!/bin/sh
set -eu

PAYLOAD="${WANXIANG_PLUGIN_PAYLOAD:?missing WANXIANG_PLUGIN_PAYLOAD}"
TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"

need() { test -s "$1" || { echo "missing offline resource: ${2:-$1}" >&2; exit 2; }; }
progress() { percent="$1"; shift; printf '[WANXIANG_PROGRESS:%s] %s\n' "$percent" "$*"; }

need "$PAYLOAD/checksums/SHA256SUMS"
need "$PAYLOAD/bin/rtk"

progress 20 "[VERIFY] 校验 RTK 二进制哈希"
(cd "$PAYLOAD" && sha256sum -c checksums/SHA256SUMS)

progress 60 "[INSTALL] 部署 RTK 二进制到 /opt/wanxiang/bin 与工具目录"
mkdir -p "$TOOL_DIR/bin" /opt/wanxiang/bin /opt/wanxiang/data/rtk/config/rtk /opt/wanxiang/data/rtk/data
cp "$PAYLOAD/bin/rtk" "$TOOL_DIR/bin/rtk"
chmod 755 "$TOOL_DIR/bin/rtk"
ln -sfn "$TOOL_DIR/bin/rtk" /opt/wanxiang/bin/rtk

if [ -f "$PAYLOAD/config/rtk/config.toml" ]; then
    cp "$PAYLOAD/config/rtk/config.toml" /opt/wanxiang/data/rtk/config/rtk/config.toml
    chmod 644 /opt/wanxiang/data/rtk/config/rtk/config.toml
fi

progress 90 "[VERIFY] 验证 RTK 命令"
/bin/sh "$PAYLOAD/scripts/verify-rtk.sh"
progress 100 "[VERIFY] RTK 终端命令优化插件已就绪"
