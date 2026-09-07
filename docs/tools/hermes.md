# Hermes Agent 兼容性记录

- 适配器：`HermesToolInstaller`
- 官方平台状态：Linux/WSL2 支持 x86_64 与 aarch64；Android Termux 为 aarch64 Tier 2
- 安装方式：Hermes 官方 `install.sh`，使用 `--skip-setup --skip-browser --non-interactive`，代码目录固定为 `/opt/wanxiang/tools/hermes-agent`，数据目录为 `/opt/wanxiang/data/hermes-agent`
- 安装前提：Manifest 声明 Python `>=3.11`、curl、CA 证书和 Git；安装脚本会继续处理 uv 等其余依赖
- 验证：`hermes --version`
- 本地 Dashboard：绑定 `0.0.0.0:9119`（支持局域网访问），由 `ToolManager.startGateway` 等待端口就绪后才标记「运行中」，避免假启动状态
- 访问 Token：`startService` 启动时注入 `HERMES_DASHBOARD_TOKEN` / `WANXIANG_GATEWAY_TOKEN` 环境变量（与 OpenClaw 对齐）；重新生成 Token 后，运行中的实例会自动重启以应用新 Token
- 数据策略：默认卸载只移除 `/opt/wanxiang/tools/hermes-agent` 和命令入口，保留 `/opt/wanxiang/data/hermes-agent`；用户勾选删除数据时额外移除该目录。
- 最后核对：2026-08-21

来源：[Hermes 官方平台支持](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/getting-started/platform-support.md)、[官方安装脚本](https://github.com/NousResearch/hermes-agent/blob/main/scripts/install.sh)

正式发布前仍需在目标 Android ARM64 设备上确认 `.[termux]` 相关限制、Dashboard Web extra 和上游许可。
