# QEMU x86_64 兼容环境 — 离线插件包

本目录是 `qemu-x86-64-compat` 插件的源文件布局，用于打包为可直接导入的本地 `.txplugin`。

## 用途

在 ARM64（`arm64-v8a`）Android 运行时上，通过 PRoot + QEMU 用户态模拟器（`qemu-x86_64`）运行一个隔离的 x86_64 Linux 构建环境，用于第三方项目的兼容构建（仅 APK 编译链路）。

- 宿主 QEMU 二进制：`usr/bin/qemu-x86_64-static`（ARM64 ELF）
- 隔离 rootfs：`ubuntu-base-24.04.3-base-amd64`（x86_64）
- 不包含 JDK、Gradle、Android SDK 或 Flutter；这些工具链由其它插件提供。

## 打包内容

```
manifest.json
payload/
  checksums/SHA256SUMS
  scripts/install-qemu-x86-64-compat.sh
  scripts/verify-qemu-x86-64-compat.sh
  scripts/uninstall-qemu-x86-64-compat.sh
  archives/<2 个离线归档>
```

## 归档清单（SHA256）

| 文件名 | 大小 | SHA256 |
|---|---|---|
| `ubuntu-base-24.04.3-base-amd64.tar.gz` | ~30 MB | `6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9` |
| `qemu-user-static_8.2.2_arm64.deb` | ~17 MB | `b43890498d911499d19ef029884734562b018234eb76acbafbb81ee5078423ad` |

## 来源 URL

- Ubuntu Base: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-amd64.tar.gz`
- QEMU: `https://ports.ubuntu.com/pool/universe/q/qemu/qemu-user-static_8.2.2+ds-0ubuntu1_arm64.deb`

## 校验

`payload/checksums/SHA256SUMS` 使用 `sha256sum -c checksums/SHA256SUMS` 校验（相对 `payload/` 目录执行，条目路径为 `archives/<filename>`）。

## 打包成 `.txplugin`

在 `assets/plugins/qemu-x86-64-compat/` 目录内执行：

```powershell
Compress-Archive -Path "manifest.json", "payload" -DestinationPath "$env:USERPROFILE\Desktop\qemu-x86-64-compat.txplugin" -Force
```

打包结果必须只包含：
- 根目录 `manifest.json`
- 根目录 `payload/`（含 `scripts/`、`checksums/`、`archives/`）

## 注意事项

- 全部脚本为 POSIX `sh`，安装过程不访问网络，仅解包本地 `archives/` 并做 SHA256 校验。
- 安装目标固定为 `/opt/wanxiang/compat/x86_64`（宿主 `wanxiangRoot/compat/x86_64`），与 `runtime` 模块 `QemuCompatibilityLayout` 一致。
- 命令链接只写入 `$WANXIANG_TOOL_DIR/bin` 与 `/opt/wanxiang/bin`：`qemu-x86_64`。
