#!/bin/sh
set -eu

PAYLOAD="${WANXIANG_PLUGIN_PAYLOAD:?missing WANXIANG_PLUGIN_PAYLOAD}"
ARCHIVES="$PAYLOAD/archives"
TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"
COMPAT_ROOT="/opt/wanxiang/compat/x86_64"

need() { test -s "$1" || { echo "missing offline resource: ${2:-$1}" >&2; exit 2; }; }
progress() { percent="$1"; shift; printf '[WANXIANG_PROGRESS:%s] %s\n' "$percent" "$*"; }
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }
is_x86_64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "3e00"; }

QEMU_ARCHIVE="$ARCHIVES/qemu-user-static_8.2.2_arm64.deb"
ROOTFS_ARCHIVE="$ARCHIVES/ubuntu-base-24.04.3-base-amd64.tar.gz"
need "$PAYLOAD/checksums/SHA256SUMS"
need "$QEMU_ARCHIVE"
need "$ROOTFS_ARCHIVE"

progress 5 "[VERIFY] 校验 QEMU user-mode 与最小 RootFS"
(cd "$PAYLOAD" && sha256sum -c checksums/SHA256SUMS)

progress 20 "[EXTRACT] 解包 ARM64 qemu-x86_64 user-mode"
rm -rf /tmp/wanxiang-qemu-user
mkdir -p /tmp/wanxiang-qemu-user
if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$QEMU_ARCHIVE" /tmp/wanxiang-qemu-user
elif command -v ar >/dev/null 2>&1; then
    (cd /tmp/wanxiang-qemu-user && ar x "$QEMU_ARCHIVE" && tar -xf data.tar.*)
else
    echo "missing deb extractor: dpkg-deb or ar is required" >&2
    exit 6
fi
QEMU_SOURCE=$(find /tmp/wanxiang-qemu-user -type f \( -name qemu-x86_64-static -o -name qemu-x86_64 \) -print -quit)
need "$QEMU_SOURCE" "qemu-x86_64 user-mode binary"
is_aarch64_elf "$QEMU_SOURCE" || { echo "qemu-x86_64 is not an ARM64 ELF" >&2; exit 4; }

rm -rf "$COMPAT_ROOT"
mkdir -p "$COMPAT_ROOT/rootfs" "$TOOL_DIR/bin" /opt/wanxiang/bin
cp "$QEMU_SOURCE" "$COMPAT_ROOT/qemu-x86_64"
chmod 755 "$COMPAT_ROOT/qemu-x86_64"
rm -rf /tmp/wanxiang-qemu-user

progress 55 "[EXTRACT] 解压最小 x86_64 Ubuntu Base RootFS"
tar -xzf "$ROOTFS_ARCHIVE" -C "$COMPAT_ROOT/rootfs"
is_x86_64_elf "$COMPAT_ROOT/rootfs/bin/sh" || { echo "rootfs/bin/sh is not an x86_64 ELF" >&2; exit 4; }

progress 80 "[COMMAND] 创建 qemu-x86_64 命令入口"
printf '%s\n' '#!/bin/sh' 'exec /opt/wanxiang/compat/x86_64/qemu-x86_64 "$@"' > "$TOOL_DIR/bin/qemu-x86_64"
chmod 755 "$TOOL_DIR/bin/qemu-x86_64"
ln -sfn "$TOOL_DIR/bin/qemu-x86_64" /opt/wanxiang/bin/qemu-x86_64

progress 95 "[VERIFY] 验证 QEMU user-mode 兼容插件"
/bin/sh "$PAYLOAD/scripts/verify-qemu-x86-64-compat.sh"
progress 100 "[VERIFY] QEMU user-mode 兼容插件已就绪"
