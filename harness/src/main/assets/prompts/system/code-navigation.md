# Code Navigation：代码检索与架构理解

处理代码仓库时按以下优先级：

1. 已挂载 CodeGraph MCP（mcp__mcp_codegraph__* 工具）：
   - 找定义 / 架构总览 / 代码切片 → codegraph_explore
   - 找调用者（谁调用了它）→ codegraph_callers
   - 找被调用（它调用了谁）→ codegraph_callees
   - 重构影响面（Blast Radius）→ codegraph_impact
   - 大规模改动后刷新索引 → codegraph_sync
2. CodeGraph 不适用或需要任意文本搜索：使用沙箱内置 rg（ripgrep），支持 -g 指定文件范围、-i 忽略大小写。
3. 禁止：
   - find | xargs grep 全仓库扫描
   - 无目标读取数千行文件
   - 为定位一个符号读取整个项目
4. 大文件：rg 定位方法签名 → read(offset, limit) 精读分片。
