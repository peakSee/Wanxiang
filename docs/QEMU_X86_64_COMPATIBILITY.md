# QEMU x86_64 兼容构建

## 目标

万象设备与默认构建工具链保持 `arm64-v8a`。QEMU 兼容环境只处理第三方项目依赖的 **x86_64 Linux 主机构建工具**，不会让最终 APK 自动包含 x86 ABI。

## 启用方式

进入「设置 → 系统保活与开发者诊断」，在本地插件中心导入并安装 `qemu-x86-64-compat`，检测到插件就绪后才能打开「QEMU x86_64 兼容模式」。

插件下载并校验：

- ARM64 `qemu-x86_64` 用户态模拟器；
- 隔离的 x86_64 Ubuntu Base RootFS；
- x86_64 动态链接器、glibc 和基础用户态库。

插件只提供 QEMU user-mode 兼容层，不再捆绑 JDK、Gradle、Android SDK 或 Flutter。

关闭开关只会停止后续自动选择兼容模式，不会立即删除已下载资源。资源应通过插件中心卸载，避免误删正在使用的兼容环境。

## 隔离目录

```text
/opt/wanxiang/compat/x86_64/
├── qemu-x86_64       # ARM64 可执行文件
└── rootfs/            # x86_64 Linux 用户态
```

普通 ARM64 RootFS 不添加 `proot -q`。只有同时满足以下条件的专用会话才能追加 `-q`：

1. 用户已开启兼容模式；
2. QEMU 插件验证通过；
3. 选择的是隔离 x86_64 RootFS；
4. 构建入口明确请求兼容模式。

## 与 proot-distro 的关系

QEMU user-mode 并不是 `proot-distro` 专属能力。`proot-distro` 只是替你准备 RootFS、QEMU 路径和启动参数；实际生效的是 PRoot 自身的 `-q` 参数，例如：

```text
proot -q /path/to/qemu-x86_64 -r /path/to/x86_64-rootfs /bin/sh
```

先进入普通 ARM64 Linux，再执行一次 `proot-distro` 的 QEMU 关联命令，不会让当前 Linux 永久或全局获得 x86_64 能力。每个需要运行 x86_64 ELF 的进程都必须位于带 `-q` 的专用 PRoot 会话里。万象因此使用隔离的 x86_64 RootFS 会话，而不会给普通 ARM64 RootFS 全局追加 QEMU。

## 当前边界

第二阶段已接入统一工作区构建入口：默认先执行 ARM64 的 `build_android.sh` / `build_flutter.sh`；只有脚本明确返回 ELF/主机架构不兼容（通常为退出码 126），且设置开关开启、兼容插件校验通过时，才会重试 `build_android_qemu.sh` / `build_flutter_qemu.sh`。不会静默把普通终端整体切到 QEMU。

终端里直接执行 `./gradlew` 或 `flutter build apk` 仍可能绕过统一构建入口。正式兜底应通过命令包装器或 Agent 构建 Skill 完成，而不是全局篡改 Shell。

## 安全说明

- QEMU 本体必须是 ARM64 ELF（ELF machine `183`）。
- x86_64 RootFS 的 `/bin/sh` 和动态链接器必须存在。
- 插件下载的 Ubuntu Base 包使用固定 SHA256 校验。
- QEMU/PRoot 不提供完整 namespace、cgroup、内核模块或硬件虚拟化能力。
- 跨架构构建速度和内存占用会显著高于原生 ARM64。
