#!/bin/sh
set -eu

TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"
COMPAT_ROOT="/opt/wanxiang/compat/x86_64"

rm -f /opt/wanxiang/bin/qemu-x86_64

rm -rf "$TOOL_DIR"
rm -rf "$COMPAT_ROOT"

echo "QEMU x86_64 user-mode 插件已卸载"
