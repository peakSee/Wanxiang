# 移动端构建第四阶段

第四阶段把第三阶段的“环境可执行”推进到“项目和产物可证明”。

## 新能力

```sh
wanxiang-build analyze /workspace/project
wanxiang-build analyze /workspace/project --offline
wanxiang-build android /workspace/project assembleDebug --offline
wanxiang-build flutter /workspace/project apk --debug --offline
```

`analyze` 是只读检查，报告：

- Android/Flutter 项目类型；
- `compileSdk` 是否超过内置 Platform 34；
- Gradle Wrapper 是否偏离 8.14.2；
- 是否声明 `x86`/`x86_64` ABI；
- 离线模式下 Gradle/Pub 缓存是否存在。

分析器不会修改第三方项目。`mobile_project_align` Skill 必须先展示当前值、目标值、修改文件和风险，得到用户确认后才允许对齐。

## 离线策略

`--offline` 会贯穿 Gradle 和 Flutter：

- Gradle 使用 `--offline`；
- Flutter Pub 使用 `pub get --offline`；
- Flutter 构建使用 `build ... --offline`；
- 缓存不存在时在构建启动前失败，不静默联网。

## 产物闸门

构建成功后，控制台入口和工作区宿主都会扫描 APK ZIP：

- 允许 `lib/arm64-v8a/`；
- 拒绝 `lib/x86/` 和 `lib/x86_64/`；
- 拒绝任何其他原生 ABI；
- 失败时不导出、不安装。

QEMU 只解决 x86_64 Linux 主机工具的执行问题，不改变最终 APK ABI。
