#!/usr/bin/env python3
import os
import hashlib
import json
import zipfile
import shutil

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC_RTK = os.path.join(ROOT_DIR, "app", "src", "main", "assets", "bin", "rtk")
SRC_LICENSE = os.path.join(ROOT_DIR, "app", "src", "main", "assets", "licenses", "rtk-LICENSE")

PLUGIN_DIR = os.path.join(ROOT_DIR, "assets", "plugins", "rtk-suite-offline")
DIST_DIR = os.path.join(ROOT_DIR, "dist", "plugins")
OUTPUT_PACKAGE = os.path.join(DIST_DIR, "wanxiang-plugin-rtk-v1.0.0-arm64.txplugin")

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def build_plugin():
    print("[*] Building RTK offline plugin package...")
    
    # Check if rtk binary exists in source or in already built plugin
    rtk_bin_path = SRC_RTK
    if not os.path.isfile(rtk_bin_path):
        rtk_bin_path = os.path.join(PLUGIN_DIR, "payload", "bin", "rtk")
    
    if not os.path.isfile(rtk_bin_path):
        print("[*] Local rtk binary not found. Downloading from official GitHub release...")
        import urllib.request, tarfile, ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        url = "https://github.com/rtk-ai/rtk/releases/download/v0.47.0/rtk-aarch64-unknown-linux-gnu.tar.gz"
        tmp_tar = os.path.join(PLUGIN_DIR, "rtk-download.tar.gz")
        os.makedirs(os.path.dirname(rtk_bin_path), exist_ok=True)
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, context=ctx) as resp, open(tmp_tar, "wb") as f:
            f.write(resp.read())
        with tarfile.open(tmp_tar, "r:gz") as tar:
            with open(rtk_bin_path, "wb") as f:
                f.write(tar.extractfile("rtk").read())
        if os.path.isfile(tmp_tar):
            os.remove(tmp_tar)
        print(f"[+] Downloaded and extracted official rtk binary to {rtk_bin_path}")
    
    rtk_hash = sha256_file(rtk_bin_path)
    print(f"[*] RTK Binary SHA256: {rtk_hash}")
    
    # Prepare directories
    payload_dir = os.path.join(PLUGIN_DIR, "payload")
    bin_dir = os.path.join(payload_dir, "bin")
    scripts_dir = os.path.join(payload_dir, "scripts")
    checksums_dir = os.path.join(payload_dir, "checksums")
    
    for d in [bin_dir, scripts_dir, checksums_dir, DIST_DIR]:
        os.makedirs(d, exist_ok=True)
        
    # Copy binary
    target_bin = os.path.join(bin_dir, "rtk")
    if os.path.abspath(rtk_bin_path) != os.path.abspath(target_bin):
        shutil.copy2(rtk_bin_path, target_bin)
        
    # Copy license
    if os.path.isfile(SRC_LICENSE):
        shutil.copy2(SRC_LICENSE, os.path.join(payload_dir, "LICENSE"))
        
    # Write SHA256SUMS
    with open(os.path.join(checksums_dir, "SHA256SUMS"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"{rtk_hash}  bin/rtk\n")
        
    # Write manifest.json
    manifest = {
        "schemaVersion": 1,
        "id": "rtk-suite-offline",
        "name": "RTK 终端命令优化器",
        "description": "万象内置 Agent 终端命令重写优化套件（ARM64 Linux 离线包），支持智能重写 git/cargo/find/grep 等命令并压缩输出，减少大体积结果的 Token 消耗。",
        "dependencies": [],
        "launchType": "command",
        "version": "1.0.0",
        "latestVersion": "1.0.0",
        "enabled": True,
        "publisher": "WanXiang",
        "category": "DEVELOPER_TOOL",
        "architectures": ["ARM64"],
        "permissions": ["WORKSPACE_READ", "WORKSPACE_WRITE"],
        "updateStrategy": "REINSTALL",
        "source": "LOCAL",
        "offlineOnly": True,
        "installMethod": "LOCAL_PACKAGE",
        "installSteps": [
            "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/install-rtk.sh\""
        ],
        "uninstallSteps": [
            "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/uninstall-rtk.sh\""
        ],
        "verifyCommand": "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/verify-rtk.sh\"",
        "launchCommand": "rtk --version",
        "commandLinks": ["rtk"],
        "environment": {
            "RTK_TEE": "0",
            "XDG_CONFIG_HOME": "/opt/wanxiang/data/rtk/config",
            "XDG_DATA_HOME": "/opt/wanxiang/data/rtk/data"
        }
    }
    
    manifest_path = os.path.join(PLUGIN_DIR, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
        
    # Copy default configuration to payload/config/rtk/config.toml
    config_dir = os.path.join(payload_dir, "config", "rtk")
    os.makedirs(config_dir, exist_ok=True)
    src_config = os.path.join(ROOT_DIR, "app", "src", "main", "assets", "rtk", "rtk", "config.toml")
    if os.path.isfile(src_config):
        shutil.copy2(src_config, os.path.join(config_dir, "config.toml"))

    # Write install-rtk.sh
    install_script = """#!/bin/sh
set -eu

PAYLOAD="${WANXIANG_PLUGIN_PAYLOAD:?missing WANXIANG_PLUGIN_PAYLOAD}"
TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"

need() { test -s "$1" || { echo "missing offline resource: ${2:-$1}" >&2; exit 2; }; }
progress() { percent="$1"; shift; printf '[WANXIANG_PROGRESS:%s] %s\\n' "$percent" "$*"; }

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
"""
    with open(os.path.join(scripts_dir, "install-rtk.sh"), "w", encoding="utf-8", newline="\n") as f:
        f.write(install_script)
        
    # Write uninstall-rtk.sh
    uninstall_script = """#!/bin/sh
set -eu

TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"

rm -f /opt/wanxiang/bin/rtk
rm -f "$TOOL_DIR/bin/rtk"
rm -rf /opt/wanxiang/data/rtk
echo "RTK 终端命令优化插件已卸载"
"""
    with open(os.path.join(scripts_dir, "uninstall-rtk.sh"), "w", encoding="utf-8", newline="\n") as f:
        f.write(uninstall_script)

    # Write verify-rtk.sh
    verify_script = """#!/bin/sh
set -eu

test -x /opt/wanxiang/bin/rtk || { echo "rtk binary is missing or not executable" >&2; exit 1; }
/opt/wanxiang/bin/rtk --version || exit 1
"""
    with open(os.path.join(scripts_dir, "verify-rtk.sh"), "w", encoding="utf-8", newline="\n") as f:
        f.write(verify_script)

    # Create .txplugin package (ZIP archive adhering to ToolRegistry requirement)
    print(f"[*] Packaging into {OUTPUT_PACKAGE}...")
    with zipfile.ZipFile(OUTPUT_PACKAGE, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(manifest_path, "manifest.json")
        for root, _, files in os.walk(payload_dir):
            for file in files:
                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, PLUGIN_DIR)
                rel_path = rel_path.replace(os.sep, "/")
                # Ensure Linux file permissions in zip info
                zinfo = zipfile.ZipInfo.from_file(full_path, rel_path)
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                if rel_path.endswith(".sh") or "bin/" in rel_path:
                    zinfo.external_attr = 0o755 << 16  # rwxr-xr-x
                else:
                    zinfo.external_attr = 0o644 << 16  # rw-r--r--
                with open(full_path, "rb") as f:
                    z.writestr(zinfo, f.read())
                    
    package_size = os.path.getsize(OUTPUT_PACKAGE)
    print(f"[+] Successfully generated: {OUTPUT_PACKAGE} ({package_size / (1024*1024):.2f} MB)")

if __name__ == "__main__":
    build_plugin()
