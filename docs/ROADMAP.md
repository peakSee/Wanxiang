# 万象（WanXiang）— 路线图

> 一句话：把 **Debian** 当 Runtime，把 **OpenClaw/Hermes/Codex** 当受管理的工具，把 **Android** 当产品层，再由一个内置 **Agent**（类 pi）用自然语言驱动一切。

## 架构（多模块）

```
:app                装配层：Application / MainActivity / DI / 前台服务 / res
:feature:nav        导航（汇总各屏）
:feature:{home,chat,terminal,workspace,settings,developer,components,theme}
:harness            Agent：HarnessLoop / ProviderClient(流式) / ToolExecutor(read/write/edit/base)
:tools              ToolManager / 安装器（core/tools + runtime/tools）
:runtime            PRoot / PTY / rootfs / shell
:core:{common,model,database,datastore,network,security}
```

## 已完成

- **Linux 沙箱**：Debian 13 真机初始化、PRoot、真机 `cat /etc/os-release` 通过
- **终端**：JNI forkpty（Termux 同款）、多会话（Room 持久化元数据）、会话列表/重命名、快捷键行
- **Agent**：四工具循环、LLM 流式输出、模型管理（Room）、会话⇄工作区关联
- **工具系统**：ToolCenter 安装/验证/更新/卸载/回滚、共享依赖引用计数、注册表（Ed25519 签名）
- **工程质量**：多模块拆分、单元测试、Room 迁移链 5→11

## 接下来（按优先级）

1. **真机验收**（设备连上后）：装最新 APK → 跑通 Codex / OpenClaw / Hermes 任一完整 安装→启动→健康检查
2. **终端打磨**：触摸滚动查看历史、IME 中文输入直通
3. **稳定性**：进程被杀后会话/安装任务恢复；长驻 Agent（前台服务通知）
4. **合规**：THIRD_PARTY 复核、商店分发策略（动态下载可执行代码影响）

## 当前限制

- 终端输入层挡住 LazyColumn 触摸滚动；中文输入法组合未完全直通
- 远程 Registry 未预置签名地址，默认用 APK 内置清单
- API Key 走加密存储；日志/存储全链脱敏
