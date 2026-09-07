# Codex 兼容性记录

- 适配器：`CodexToolInstaller`
- 安装方式：OpenAI 官方 Linux 安装脚本
- 命令：通过 HTTPS 下载 OpenAI 官方 `install.sh` 到 Linux 临时目录，校验非空后执行；安装过程不使用 `curl | sh`，并将 `HOME` 指向 `/opt/wanxiang/tools/codex`，再创建 `/opt/wanxiang/bin/codex` 入口。
- 验证：`codex --version`
- 运行时：Linux ARM64；安装前提供 `curl` 和 CA 证书
- 数据策略：程序安装在 `/opt/wanxiang/tools/codex`，`CODEX_HOME` 指向 `/opt/wanxiang/data/codex`；默认卸载保留该数据目录，用户勾选删除数据时才移除。
- 最后核对：2026-08-17

来源：[OpenAI Codex 官方 README](https://github.com/openai/codex/blob/main/README.md)

正式发布前仍需在目标 Android ARM64 设备上确认官方二进制或 npm fallback 的兼容性，并复核许可。
