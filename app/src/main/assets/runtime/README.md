Phase 1 runtime payloads

Linux ARM64 rootfs 默认在线下载，不放入 APK。Android 10+ 不允许目标应用从
可写私有目录执行 ELF，因此 APK 只携带很小的 ARM64 PRoot 主程序、外置 loader
及其动态依赖；Linux 系统由首次引导使用 `proot-distro 5.8.0` OCI 流程安装。

安装器从 Docker/OCI Registry 选择 `linux/arm64` manifest，将通过 digest 校验的
layer 缓存到应用私有目录，按 OCI whiteout 规则合并后再原子启用 RootFS。
PRoot 的主程序与 loader 必须来自同一官方 Termux 包；构建前运行
`tools/prepare-proot-runtime.ps1` 可校验包 SHA-256 并同时准备两者。
