#!/bin/sh
set -eu

TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"

rm -f /opt/wanxiang/bin/rtk
rm -f "$TOOL_DIR/bin/rtk"
rm -rf /opt/wanxiang/data/rtk
echo "RTK 终端命令优化插件已卸载"
