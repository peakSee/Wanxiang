# QEMU x86_64 兼容插件资源

此目录只提供插件契约和资源清单，不会把一个不完整的 QEMU 文件冒充成可用环境。离线插件必须包含以下内容：

```text
payload/
  compat/x86_64/qemu-x86_64       # ARM64 ELF，可在本机直接执行
  compat/x86_64/rootfs/bin/sh     # x86_64 ELF
  compat/x86_64/rootfs/lib64/ld-linux-x86-64.so.2
  compat/x86_64/rootfs/usr/...    # glibc、libstdc++、zlib 等用户态库
```

推荐的 x86_64 RootFS 基础包（仅作为被模拟用户态，不是 Android APK ABI）：

- URL: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-amd64.tar.gz
- SHA256: `6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9`

ARM64 QEMU 本体应从项目的 ARM64 构建产物获取，并在安装时校验 ELF machine=183。不要放入 x86 或 x86_64 的 QEMU 可执行文件；那会无法在 ARM64 Android 上启动。

QEMU 本身不能替代 RootFS。只有 QEMU、没有 x86_64 动态链接器和用户态库时，插件必须保持“未就绪”，不得在 PRoot 中追加 `-q`。
