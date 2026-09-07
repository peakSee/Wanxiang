#!/bin/sh
set -eu

test -x /opt/wanxiang/bin/rtk || { echo "rtk binary is missing or not executable" >&2; exit 1; }
/opt/wanxiang/bin/rtk --version || exit 1
