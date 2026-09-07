# RTK 终端命令优化器 — 离线插件包

本目录是 `rtk-suite-offline` 插件的源文件布局，用于打包为可直接导入万象的本地 `.txplugin`。

## 用途

在 ARM64（`arm64-v8a`）Android 运行时上，为 Agent 调度执行命令提供实时输出压缩与过滤重写（`rtk rewrite`），降低 60%~90% 的上下文 Token 消耗。

- 官方项目：[rtk-ai/rtk](https://github.com/rtk-ai/rtk)
- 对应版本：`v0.47.0`
- 架构：Linux `aarch64-unknown-linux-gnu`
- 许可证：Apache-2.0

## 打包内容

```text
manifest.json
payload/
  checksums/SHA256SUMS
  config/rtk/config.toml
  scripts/install-rtk.sh
  scripts/verify-rtk.sh
  scripts/uninstall-rtk.sh
  bin/rtk
  LICENSE
```

## 来源 URL 与哈希

- 归档：`https://github.com/rtk-ai/rtk/releases/download/v0.47.0/rtk-aarch64-unknown-linux-gnu.tar.gz`
- 归档 SHA256：`960ceb5f1f5f0b0939b32b5b1d41dec6d9a7113137b0703c68dca0d169a260fc`
- 二进制 `bin/rtk` SHA256：`e440fc61077925d98fdea5c6bf817df2c3c85e6b96aea5d02659c2a6f42d93ce`

## 打包成 `.txplugin`

在项目根目录下运行：

```bash
python tools/package-rtk-plugin.py
```

产物将输出至：`dist/plugins/wanxiang-plugin-rtk-v1.0.0-arm64.txplugin`。
