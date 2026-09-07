#!/bin/sh
# Verify a completed Android APK before it is exported or installed.
set -eu

apk="${1:-}"
test -n "$apk" || { echo "WANXIANG_VERIFY_FAIL: apk_missing" >&2; exit 2; }
test -s "$apk" || { echo "WANXIANG_VERIFY_FAIL: apk_empty" >&2; exit 2; }

list_entries() {
    if command -v unzip >/dev/null 2>&1; then unzip -Z1 "$apk" 2>/dev/null; return; fi
    if command -v zipinfo >/dev/null 2>&1; then zipinfo -1 "$apk" 2>/dev/null; return; fi
    if command -v python3 >/dev/null 2>&1; then python3 - "$apk" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for name in z.namelist(): print(name)
PY
        return
    fi
    echo "WANXIANG_VERIFY_FAIL: no_zip_reader" >&2
    exit 2
}

entries=$(list_entries)
printf '%s\n' "$entries" | grep -Eq '^lib/(x86|x86_64)/' && {
    echo "WANXIANG_VERIFY_FAIL: x86_abi_present" >&2
    printf '%s\n' "$entries" | grep -E '^lib/(x86|x86_64)/' >&2 || true
    exit 3
}
native=$(printf '%s\n' "$entries" | grep -E '^lib/[^/]+/' || true)
if test -n "$native"; then
    bad=$(printf '%s\n' "$native" | grep -Ev '^lib/arm64-v8a/' || true)
    if test -n "$bad"; then
        echo "WANXIANG_VERIFY_FAIL: non_arm64_abi_present" >&2
        printf '%s\n' "$bad" >&2
        exit 3
    fi
fi

printf '%s\n' "$entries" | grep -Eq '^META-INF/(MANIFEST.MF|CERT\.(RSA|DSA|EC))$' ||
    echo "WANXIANG_VERIFY_WARN: signature_metadata_not_found" >&2

echo "WANXIANG_VERIFY_OK: arm64-only APK $(basename "$apk")"
