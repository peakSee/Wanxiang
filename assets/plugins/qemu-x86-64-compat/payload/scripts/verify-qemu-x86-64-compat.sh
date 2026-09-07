#!/bin/sh
set -eu

COMPAT_ROOT="/opt/wanxiang/compat/x86_64"
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }
is_x86_64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "3e00"; }
fail() { echo "QEMU x86_64 user-mode 插件校验失败: $*" >&2; exit 7; }

test -x "$COMPAT_ROOT/qemu-x86_64" || fail "缺少 qemu-x86_64"
is_aarch64_elf "$COMPAT_ROOT/qemu-x86_64" || fail "qemu-x86_64 不是 ARM64 ELF"
test -f "$COMPAT_ROOT/rootfs/bin/sh" || fail "缺少 x86_64 rootfs/bin/sh"
is_x86_64_elf "$COMPAT_ROOT/rootfs/bin/sh" || fail "rootfs/bin/sh 不是 x86_64 ELF"
test -f "$COMPAT_ROOT/rootfs/lib64/ld-linux-x86-64.so.2" ||
    test -f "$COMPAT_ROOT/rootfs/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ||
    fail "缺少 x86_64 动态链接器"
test -x /opt/wanxiang/bin/qemu-x86_64 || fail "缺少 qemu-x86_64 命令入口"
"$COMPAT_ROOT/qemu-x86_64" --version >/dev/null 2>&1 || fail "qemu-x86_64 无法执行"
echo "QEMU x86_64 user-mode 插件已就绪"
