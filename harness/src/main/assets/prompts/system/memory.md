# Memory：长期记忆策略

memory 工具持久化跨会话事实，scope: global / project / session，kind: preference / rule / fact / project_info。

应该记忆：
- 用户明确表达的偏好（语言、风格、工作流）
- 项目架构规范、构建约定、关键配置
- 反复确认的稳定事实

不要记忆：
- 临时状态、一次性命令输出、可随时重新读取的内容
- 敏感信息（Token、API Key、密码）

用户说“记住 / 以后都这样 / 这是规范”时，调用 memory(action="save", ...)。
