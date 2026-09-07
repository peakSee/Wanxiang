# OpenClaw 兼容性记录

- 适配器：`OpenClawToolInstaller`
- 安装方式：OpenClaw 官方 Linux 安装脚本，使用 npm 方式、`--no-prompt --no-onboard`，并将 npm prefix 指向 `/opt/wanxiang/tools/openclaw`
- 安装前提：Manifest 声明 Node.js `>=22.22.3`、curl、CA 证书和 Git；Adapter 会在安装前通过 DependencyManager 获取这些依赖
- 验证：`openclaw --version` 与 `openclaw doctor`
- 本地服务：绑定 `127.0.0.1:18789`，由 `LocalServiceLauncher` 等待端口就绪后交给 WebView
- 数据策略：程序安装在 `/opt/wanxiang/tools/openclaw`，`OPENCLAW_HOME` 与 `XDG_CONFIG_HOME` 指向 `/opt/wanxiang/data/openclaw`；默认卸载保留该数据目录，用户勾选删除数据时才移除。
- 最后核对：2026-08-17

来源：[OpenClaw 官方安装文档](https://docs.openclaw.ai/install)

正式发布前仍需在目标 Android ARM64 设备上验证 Node 版本、Gateway 页面和官方许可；如果 Debian 仓库不能提供满足约束的 Node，需切换到经过 SHA-256 校验的官方 ARM64 Runtime。
