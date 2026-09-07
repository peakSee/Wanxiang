# WanXiang 插件开发准则

本文档描述当前 WanXiang 插件导入器、Registry、安装器和 PRoot 运行环境的真实行为。除明确标记为“建议”的内容外，示例均以当前实现为准。

## 1. 插件来源

WanXiang 将插件分为两类：

| 来源 | `source` | 安装方式 | 资源位置 |
| --- | --- | --- | --- |
| 在线插件 | `REMOTE` | 通常为 `SCRIPT` | 内置或签名 Registry |
| 本地插件 | `LOCAL` | `LOCAL_PACKAGE` | 用户导入的 `.txplugin` |

本地包导入时，应用会强制改写为：

```json
{
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE"
}
```

本地插件与在线 Registry 使用相同 `id` 时，本地插件优先显示。`permissions` 当前用于清单校验和界面展示，**不是安装脚本的权限沙箱**；安装脚本仍在 PRoot 内以当前 Linux 用户权限执行。

## 2. `.txplugin` 包格式

本地插件使用 ZIP 容器，扩展名为 `.txplugin`：

```text
my-plugin.txplugin
├── manifest.json
└── payload/
    ├── archives/       # SDK、Gradle、Flutter、NDK 等归档
    ├── scripts/        # 安装、验证和卸载脚本
    ├── config/         # 配置模板
    └── bin/            # 可直接部署的 ARM64 文件
```

硬性限制：

- ZIP 根目录只允许 `manifest.json` 和 `payload/`。
- 条目不得使用绝对路径、反斜杠逃逸或 `..`。
- `manifest.json` 最大 1 MiB。
- 所有解包条目的累计数据最大 8 GiB。
- 大包导入需要同时容纳应用私有插件副本、发行版内的 payload 副本、解压 staging 和最终安装目录；预留空间应明显大于 `.txplugin` 文件本身。

导入后，包会解压到应用私有的版本目录；安装前，`payload/` 会复制到当前发行版：

```text
/opt/wanxiang/imports/<id>
```

## 3. `manifest.json`

最小本地插件清单：

```json
{
  "schemaVersion": 1,
  "id": "hello-arm64",
  "name": "Hello ARM64",
  "description": "WanXiang 本地插件示例",
  "version": "1.0.0",
  "publisher": "Your Name",
  "category": "DEVELOPER",
  "launchType": "command",
  "architectures": ["ARM64"],
  "permissions": [],
  "updateStrategy": "REINSTALL",
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE",
  "installSteps": [
    "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/install.sh\""
  ],
  "uninstallSteps": [
    "/bin/sh \"$WANXIANG_PLUGIN_PAYLOAD/scripts/uninstall.sh\""
  ],
  "launchCommand": "hello",
  "verifyCommand": "hello",
  "commandLinks": ["hello"]
}
```

字段约束：

| 字段 | 当前约束 |
| --- | --- |
| `schemaVersion` | 当前必须为 `1`。 |
| `id` | 正则为 `[a-z0-9][a-z0-9-]{1,63}`，长度 2～64。 |
| `version` | 必须非空；建议使用规范 `x.y.z`。 |
| `architectures` | 必须包含 `ARM64`，不区分大小写。 |
| `launchType` | `one_shot`、`command`、`pty`、`web` 或 `service`。 |
| `permissions` | 只接受 `NETWORK`、`WORKSPACE_READ`、`WORKSPACE_WRITE`、`LOCAL_WEB`。 |
| `updateStrategy` | `REINSTALL` 或 `IN_PLACE`。 |
| `homepage` | 如提供，必须使用 HTTPS。 |
| `servicePort` | 如提供，必须为 1～65535，且启动类型必须为 `web/service`。 |
| `servicePath` | 必须以 `/` 开头且不能包含 `..`。 |

在线插件应使用 `source=REMOTE` 和 `installMethod=SCRIPT`。当前 Validator 尚未强制 REMOTE 与 SCRIPT 的组合，Registry 发布者仍必须遵循该约定。

## 4. 安装器提供的环境变量

```text
$WANXIANG_TOOL_ID         当前插件 ID
$WANXIANG_TOOL_DIR        /opt/wanxiang/tools/<id>
$WANXIANG_TOOL_DATA       /opt/wanxiang/data/<id>
$WANXIANG_PLUGIN_PAYLOAD  /opt/wanxiang/imports/<id>（仅本地插件）
```

程序文件放在 `$WANXIANG_TOOL_DIR`；用户配置、缓存、模型或项目数据放在 `$WANXIANG_TOOL_DATA`。后者在普通卸载时默认保留。

`commandLinks` 中的每个名称默认指向：

```text
$WANXIANG_TOOL_DIR/bin/<command>
```

因此脚本必须创建对应文件并恢复可执行权限。

## 5. 精简 RootFS 兼容规则

插件只能默认依赖 `/bin/sh` 和实际 RootFS 已提供的基础命令。不要假设下列可选工具存在：

```text
unzip  xz  file  readelf  jq  python  node
```

具体规则：

- 独立脚本以 `#!/bin/sh` 开头，使用 POSIX Shell 语法和 LF 换行。
- 清单中使用绝对 `/bin/sh`，不要依赖 `PATH` 中的 `sh`。
- 优先发布 `.tar.gz`；使用 `.tar.xz` 时必须随包提供兼容解压器，不能假设系统有 `xz`。
- ZIP 不保证保留 Unix 执行位；解压后必须显式 `chmod`。
- 不要用 `find -type f` 查找可能为符号链接的工具；使用 `\( -type f -o -type l \)`。
- 不要把通配符直接作为单个 `test -x` 参数；先用 `find ... -print -quit` 得到明确路径。
- 不要依赖 `file` 或 `readelf` 检查架构，可直接读取 ELF 头。

POSIX ELF64 AArch64 检查示例：

```sh
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_elf() { test "$(elf_bytes -N 4 "$1")" = "7f454c46"; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }
```

`e_machine` 位于 ELF 头偏移 18；AArch64 为 183，即小端字节 `b7 00`。

## 6. Hello 插件

目录：

```text
hello-arm64/
├── manifest.json
└── payload/
    └── bin/
        └── hello
```

`payload/bin/hello`：

```sh
#!/bin/sh
echo "hello from WanXiang"
```

清单中的安装步骤：

```json
{
  "installSteps": [
    "test -f \"$WANXIANG_PLUGIN_PAYLOAD/bin/hello\"",
    "mkdir -p \"$WANXIANG_TOOL_DIR/bin\"",
    "cp \"$WANXIANG_PLUGIN_PAYLOAD/bin/hello\" \"$WANXIANG_TOOL_DIR/bin/hello\"",
    "chmod 755 \"$WANXIANG_TOOL_DIR/bin/hello\""
  ],
  "uninstallSteps": [
    "rm -f \"$WANXIANG_TOOL_DIR/bin/hello\""
  ],
  "launchCommand": "hello",
  "verifyCommand": "hello",
  "commandLinks": ["hello"]
}
```

## 7. 大型离线依赖

大型资源放入 `payload/archives/`，脚本先校验摘要，再解压到 staging，验证后提交：

```sh
#!/bin/sh
set -eu

archive="$WANXIANG_PLUGIN_PAYLOAD/archives/flutter-arm64.tar.gz"
expected_sha256="<SHA256>"

test -s "$archive"
printf '%s  %s\n' "$expected_sha256" "$archive" | sha256sum -c -

rm -rf /opt/flutter.staging
mkdir -p /opt/flutter.staging
tar -xzf "$archive" -C /opt/flutter.staging

test -x /opt/flutter.staging/flutter/bin/flutter
rm -rf /opt/flutter
mv /opt/flutter.staging/flutter /opt/flutter
```

要求：

- `offlineOnly=true` 会跳过 Node/Python/Curl 等 Runtime 依赖获取，但不会阻止脚本主动联网；离线插件自身不得调用网络或包管理器。
- 主机工具必须能在 Linux ARM64 PRoot 中运行。Android ARM64/Bionic 程序与 Linux ARM64/glibc 程序也不能仅凭架构相同就混用。
- 所有大型归档必须固定 SHA-256。
- staging 必须位于同一文件系统，验证完成后再 `mv` 到最终目录。
- 安装脚本默认超时 15 分钟；单次 `verifyCommand` 默认超时 60 秒。

## 8. 实时进度与日志

安装器会记录阶段事件、标准输出和标准错误。插件脚本可以输出结构化相对进度：

```text
[WANXIANG_PROGRESS:47] [EXTRACT] 正在解压 Android NDK r29
```

格式：

```text
[WANXIANG_PROGRESS:<0..100>] <用户可读消息>
```

进度必须单调递增。推荐消息标签：

- `[COPY]`：复制资源。
- `[EXTRACT]`：解压归档。
- `[COMMAND]`：执行明确、已脱敏的命令或配置步骤。
- `[VERIFY]`：摘要、架构和最终状态验证。

不要使用 `set -x` 生成安装日志，它可能输出 Token、代理地址或其他环境变量。结构化进度是安装配方内部的相对进度，应用会映射到完整安装进度条。

## 9. 打包、版本与导入

### 小型插件

Windows PowerShell：

```powershell
Compress-Archive `
  -LiteralPath .\hello-arm64\manifest.json, .\hello-arm64\payload `
  -DestinationPath .\hello-arm64.zip `
  -Force
Move-Item .\hello-arm64.zip .\hello-arm64.txplugin
```

Linux/macOS：

```sh
cd hello-arm64
zip -r ../hello-arm64.txplugin manifest.json payload
```

大型插件应使用支持 Zip64 的工具。已经是 `.zip`、`.tar.gz`、`.deb` 等压缩归档的条目建议使用 ZIP Store/NoCompression，避免无效的二次压缩。

### 快速更新包内脚本

`.txplugin` 是 ZIP。只修改 `manifest.json` 或脚本时，可以复制原包并更新少量 ZIP 条目；不需要解压、重压缩所有大型归档。只有替换归档或改变其压缩格式时，才需要处理对应大文件。

### 版本行为

- 相同 `id + version` 已导入时会直接提示，不重复解包或安装。
- 相同 `id` 的不同版本目前会在应用私有目录中共存，不会删除旧版本。
- 当前版本选择按版本目录名做字符串排序，而不是完整 SemVer 比较。修复前应保持版本段宽度可比较，避免同时使用 `1.0.9` 与 `1.0.10` 这类字符串顺序不同的版本。
- 选择新版本并确认后，当前 UI 会连续完成导入和安装，不需要再点击第二次“安装”。

## 10. Web/Service 插件

Web 服务应显式声明端口和路径：

```json
{
  "launchType": "web",
  "servicePort": 8787,
  "servicePath": "/",
  "permissions": ["LOCAL_WEB"],
  "launchCommand": "dashboard --host 127.0.0.1 --port 8787"
}
```

当前 Validator 只在 `servicePort` 存在时校验端口，并未强制所有 `web/service` 清单提供端口。插件作者仍应声明端口；除非确有局域网访问需求，服务优先监听 `127.0.0.1`。

## 11. 验证、事务与卸载

`verifyCommand` 应返回 0，并实际执行核心二进制。当前安装器为兼容旧插件，在验证失败但发现任意命令入口可执行时可能保留成功状态；插件不得依赖该兜底行为。

安装事务只快照和恢复：

```text
/opt/wanxiang/tools/<id>
```

插件写入的 `/opt/android-sdk`、`/opt/flutter`、`/root/.gradle` 或其他全局目录不在框架事务快照中。因此：

- 全局资源必须自行使用 staging、校验和提交。
- 失败时脚本应通过 `trap` 清理 staging。
- 日志出现 `ROLLED_BACK` 不代表插件创建的所有全局文件都已恢复。
- `uninstallSteps` 必须删除插件拥有的全局程序文件，但默认保留 `$WANXIANG_TOOL_DATA`。

## 12. 发布前检查

```text
[ ] ZIP 根目录只有 manifest.json 与 payload/
[ ] manifest.json 小于 1 MiB，解包总量小于 8 GiB
[ ] id、version、ARM64、launchType、来源和权限字段正确
[ ] 所有大文件均有固定 SHA-256
[ ] 不依赖未声明的 unzip、xz、file、readelf 等可选命令
[ ] ELF、符号链接和 ZIP 可执行位处理正确
[ ] 断网可完成导入、安装、验证和启动
[ ] 全新安装、同版本重复导入、新版本升级和卸载均测试过
[ ] verifyCommand、命令链接、PTY/Web 服务均成功
[ ] 结构化进度单调递增，日志不包含密钥或敏感环境变量
[ ] 失败时 staging 可清理，且不误称全局目录已完整回滚
[ ] 已核算插件副本、沙盒副本、staging 与最终目录所需空间
```

## 13. 常见错误

### 导入后列表没有插件

检查 ZIP 根目录、JSON、`id`、`architectures` 和包大小限制。

### 相同包无法再次导入

相同 `id + version` 会被拒绝。修改内容后必须递增 `version`。

### `cannot create .../bin/...`

确保安装前创建 `$WANXIANG_TOOL_DIR/bin`。当前通用安装器也会提前创建该目录。

### `xz: Cannot exec`

RootFS 没有 `xz`。改用 `.tar.gz`，或随插件提供可运行的 ARM64 解压器。

### `unexpected operator`

通常是 `/bin/sh` 的 `test` 收到通配符展开后的多个参数。先用 `find ... -print -quit` 得到单一路径并加双引号。

### ARM64 程序仍无法启动

除 ELF `e_machine` 外，还要检查解释器和 ABI。Android/Bionic ARM64 与 Linux/glibc ARM64 并不自动兼容。

### 日志显示回滚但全局文件仍存在

框架事务仅覆盖 `$WANXIANG_TOOL_DIR`。插件拥有的全局路径必须由插件脚本自行清理。
