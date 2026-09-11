#!/usr/bin/env bash
# Linux/CI counterpart of tools/prepare-proot-runtime.ps1 — same pinned version & SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."

JNI=app/src/main/jniLibs/arm64-v8a
PROOT="$JNI/libproot.so"
LOADER="$JNI/libproot-loader.so"
VERSION="5.1.107.92"
PKG_SHA="1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9"
PROOT_ENTRY="./data/data/com.termux/files/usr/bin/proot"
LOADER_ENTRY="./data/data/com.termux/files/usr/libexec/proot/loader"

if [ -f "$PROOT" ] && [ -f "$LOADER" ]; then
  echo "PRoot runtime already present, skipping."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok=0
for url in \
  "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${VERSION}_aarch64.deb" \
  "https://termux.librehat.com/apt/termux-main/pool/main/p/proot/proot_${VERSION}_aarch64.deb"; do
  echo "Downloading PRoot runtime: $url"
  if curl -fsSL --retry 2 -o "$TMP/proot.deb" "$url"; then ok=1; break; fi
done
[ "$ok" = "1" ] || { echo "All PRoot download mirrors failed" >&2; exit 1; }

echo "$PKG_SHA  $TMP/proot.deb" | sha256sum -c -

mkdir -p "$TMP/x"
dpkg-deb -x "$TMP/proot.deb" "$TMP/x"

PROOT_BIN="$TMP/x/data/data/com.termux/files/usr/bin/proot"
LOADER_BIN="$TMP/x/data/data/com.termux/files/usr/libexec/proot/loader"
[ -f "$PROOT_BIN" ] || { echo "missing $PROOT_ENTRY in package" >&2; exit 1; }
[ -f "$LOADER_BIN" ] || { echo "missing $LOADER_ENTRY in package" >&2; exit 1; }

SZ=$(stat -c%s "$PROOT_BIN"); [ "$SZ" -gt 4096 ] && [ "$SZ" -le 4194304 ] || { echo "proot size invalid: $SZ" >&2; exit 1; }
SZ=$(stat -c%s "$LOADER_BIN"); [ "$SZ" -gt 4096 ] && [ "$SZ" -le 4194304 ] || { echo "loader size invalid: $SZ" >&2; exit 1; }
head -c4 "$PROOT_BIN" | grep -q $'\x7fELF' || { echo "proot not ELF" >&2; exit 1; }

mkdir -p "$JNI"
cp "$PROOT_BIN" "$PROOT"
cp "$LOADER_BIN" "$LOADER"
chmod 644 "$PROOT" "$LOADER"
echo "Prepared $PROOT and $LOADER (PRoot $VERSION)"
