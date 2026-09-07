package top.wanxiang.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.res.stringResource
import top.wanxiang.app.feature.settings.R

@StringRes
private fun legacyStringResource(source: String): Int? = when (source) {
        "关闭" -> R.string.settings_legacy_0001
        "取消" -> R.string.settings_legacy_0002
        "保存" -> R.string.settings_legacy_0003
        "删除" -> R.string.settings_legacy_0004
        "确定" -> R.string.settings_legacy_0005
        "完成" -> R.string.settings_legacy_0006
        "素白浅色 (Light)" -> R.string.settings_legacy_0007
        "明澈素雅，适合日间光线明亮环境" -> R.string.settings_legacy_0008
        "深邃曜石 (Dark)" -> R.string.settings_legacy_0009
        "服务商预设" -> R.string.settings_legacy_0010
        "Base URL（接口地址）" -> R.string.settings_legacy_0011
        "稍后再说" -> R.string.settings_legacy_0012
        "正在下载…" -> R.string.settings_legacy_0013
        "万象 · 乾坤" -> R.string.settings_legacy_0014
        "万象 · WanXiang" -> R.string.settings_legacy_0015
        "系统设置与控制中枢" -> R.string.settings_legacy_0016
        "系统与配置分类" -> R.string.settings_legacy_0017
        "智能体与 AI 模型" -> R.string.settings_legacy_0018
        "智能体与模型" -> R.string.settings_legacy_0019
        "模型档案 · 插件工具中心 · 技能与 MCP 生态" -> R.string.settings_legacy_0020
        "模型档案" -> R.string.settings_legacy_0021
        "模型档案管理" -> R.string.settings_legacy_0022
        "模型档案与提供商" -> R.string.settings_legacy_0023
        "配置 OpenAI / DeepSeek / Claude / 本地大模型密钥与端点" -> R.string.settings_legacy_0024
        "新增模型" -> R.string.settings_legacy_0025
        "新增模型档案" -> R.string.settings_legacy_0026
        "编辑模型" -> R.string.settings_legacy_0027
        "编辑模型档案" -> R.string.settings_legacy_0028
        "暂无模型档案" -> R.string.settings_legacy_0029
        "未配置模型" -> R.string.settings_legacy_0030
        "点击上方按钮添加 OpenAI / DeepSeek / Claude 等模型配置" -> R.string.settings_legacy_0031
        "档案名称" -> R.string.settings_legacy_0032
        "名称" -> R.string.settings_legacy_0033
        "模型 ID（可选择或输入）" -> R.string.settings_legacy_0034
        "保存模型配置" -> R.string.settings_legacy_0035
        "刷新在线模型" -> R.string.settings_legacy_0036
        "刷新中…" -> R.string.settings_legacy_0037
        "测试连接" -> R.string.settings_legacy_0038
        "测试中…" -> R.string.settings_legacy_0039
        "连接成功" -> R.string.settings_legacy_0040
        "高级设置" -> R.string.settings_legacy_0041
        "采样、上下文、推理、工具与请求头" -> R.string.settings_legacy_0042
        "API Key 池（每行一个）" -> R.string.settings_legacy_0043
        "同一接口地址的多个 Key 将按请求自动轮询并在 429 时切换" -> R.string.settings_legacy_0044
        "单 Key 每分钟请求上限" -> R.string.settings_legacy_0045
        "0 或留空表示不限制；达到上限时优先轮换其他 Key" -> R.string.settings_legacy_0046
        "推理思考模式" -> R.string.settings_legacy_0047
        "开启深度推理（更深入思考）" -> R.string.settings_legacy_0048
        "跟随模型默认" -> R.string.settings_legacy_0049
        "支持函数调用 (Tool Call)" -> R.string.settings_legacy_0050
        "使用 OpenAI 标准函数调用执行沙箱与扩展命令" -> R.string.settings_legacy_0051
        "支持识图" -> R.string.settings_legacy_0052
        "开启后图片直接发送给 AI 识别；关闭后自动调用工具读取图片" -> R.string.settings_legacy_0053
        "上下文 Token 上限" -> R.string.settings_legacy_0054
        "自定义请求头（可选）" -> R.string.settings_legacy_0055
        "不注入工具和提示词" -> R.string.settings_legacy_0056
        "关闭系统提示词和工具定义注入，仅发送用户消息（用于排查问题）" -> R.string.settings_legacy_0057
        "Agent 智能体管理" -> R.string.settings_legacy_0059
        "思考流呈现、上下文压缩阈值与技能插件" -> R.string.settings_legacy_0060
        "工具与插件生态" -> R.string.settings_legacy_0061
        "插件与工具生态中心" -> R.string.settings_legacy_0062
        "一键安装 Claude Code、OpenClaw 等 AI CLI 与开发环境" -> R.string.settings_legacy_0063
        "MCP 协议生态与服务" -> R.string.settings_legacy_0064
        "管理 SQLite、Git、Fetch 等 Model Context Protocol 协议服务" -> R.string.settings_legacy_0065
        "Linux 容器与存储" -> R.string.settings_legacy_0066
        "容器系统与沙箱管理" -> R.string.settings_legacy_0067
        "多发行版管理 · 宿主存储映射 · 运行特权模式" -> R.string.settings_legacy_0068
        "Linux 发行版管理" -> R.string.settings_legacy_0069
        "多沙箱并存 · 镜像拉取 · 一键切换主系统" -> R.string.settings_legacy_0070
        "存储挂载与共享" -> R.string.settings_legacy_0071
        "PRoot 宿主存储映射 (-b /sdcard)" -> R.string.settings_legacy_0072
        "系统运行特权模式" -> R.string.settings_legacy_0073
        "PRoot 用户态沙箱 · Shizuku · Root" -> R.string.settings_legacy_0074
        "外观、字号与终端定制" -> R.string.settings_legacy_0075
        "深浅色主题 · 应用字号缩放 · 终端配色与字体" -> R.string.settings_legacy_0076
        "系统保活与开发者诊断" -> R.string.settings_legacy_0077
        "保活与诊断" -> R.string.settings_legacy_0078
        "电池优化与后台运行" -> R.string.settings_legacy_0079
        "豁免系统电池限制，防止 Agent 息屏被冻结" -> R.string.settings_legacy_0080
        "Android 12 子进程限制" -> R.string.settings_legacy_0081
        "解除 Phantom Process 最多 32 个的后台限制" -> R.string.settings_legacy_0082
        "已解除" -> R.string.settings_legacy_0083
        "未解除" -> R.string.settings_legacy_0084
        "无需处理" -> R.string.settings_legacy_0085
        "待检测" -> R.string.settings_legacy_0086
        "已解除限制" -> R.string.settings_legacy_0087
        "限制仍生效" -> R.string.settings_legacy_0088
        "当前系统无需处理" -> R.string.settings_legacy_0089
        "暂时无法检测" -> R.string.settings_legacy_0090
        "正在检测" -> R.string.settings_legacy_0091
        "尚未检测" -> R.string.settings_legacy_0092
        "读取系统实际配置，确认幽灵进程限制是否仍在生效。" -> R.string.settings_legacy_0093
        "使用 Shizuku / Root 一键解除" -> R.string.settings_legacy_0094
        "正在处理" -> R.string.settings_legacy_0095
        "也可以在已连接手机的电脑终端执行：" -> R.string.settings_legacy_0096
        "复制命令" -> R.string.settings_legacy_0097
        "命令已复制" -> R.string.settings_legacy_0098
        "开发者诊断模式" -> R.string.settings_legacy_0099
        "开启底层健康监控与调试控制台" -> R.string.settings_legacy_0100
        "开发者控制台" -> R.string.settings_legacy_0101
        "开发者调试与控制台" -> R.string.settings_legacy_0102
        "实时查看 PRoot 进程与命令追踪" -> R.string.settings_legacy_0103
        "关于与社区" -> R.string.settings_legacy_0104
        "关于万象 · WanXiang" -> R.string.settings_legacy_0105
        "关于、更新与官方社区" -> R.string.settings_legacy_0106
        "应用版本与更新" -> R.string.settings_legacy_0107
        "检查新版本" -> R.string.settings_legacy_0108
        "检查中…" -> R.string.settings_legacy_0109
        "已是最新版本" -> R.string.settings_legacy_0110
        "检查更新失败" -> R.string.settings_legacy_0111
        "启动时自动检查更新" -> R.string.settings_legacy_0112
        "应用启动时在后台静默检测新版本" -> R.string.settings_legacy_0113
        "官方社区与开源" -> R.string.settings_legacy_0114
        "GitHub 开源项目" -> R.string.settings_legacy_0115
        "万象官方交流群" -> R.string.settings_legacy_0116
        "官方 QQ 交流群" -> R.string.settings_legacy_0117
        "前往 GitHub 下载" -> R.string.settings_legacy_0118
        "运行平稳" -> R.string.settings_legacy_0119
        "未配置" -> R.string.settings_legacy_0120
        "当前激活" -> R.string.settings_legacy_0121
        "设为激活" -> R.string.settings_legacy_0122
        "浅色" -> R.string.settings_legacy_0123
        "曜石" -> R.string.settings_legacy_0124
        "跟随系统" -> R.string.settings_legacy_0125
        "选择外观主题" -> R.string.settings_legacy_0126
        "选择系统运行模式" -> R.string.settings_legacy_0127
        "系统底层特权" -> R.string.settings_legacy_0128
        "正在进行特权探测与授权申请…" -> R.string.settings_legacy_0129
        "运行模式授权未通过" -> R.string.settings_legacy_0130
        "环境变量" -> R.string.settings_legacy_0131
        "为终端、Agent 和工具注入加密变量" -> R.string.settings_legacy_0132
        "添加环境变量" -> R.string.settings_legacy_0133
        "编辑环境变量" -> R.string.settings_legacy_0134
        "删除环境变量" -> R.string.settings_legacy_0135
        "暂无环境变量" -> R.string.settings_legacy_0136
        "值" -> R.string.settings_legacy_0137
        "新值（留空保留原值）" -> R.string.settings_legacy_0138
        "备注（可选）" -> R.string.settings_legacy_0139
        "隐私模式" -> R.string.settings_legacy_0140
        "发送给 Agent 前遮盖已配置变量值" -> R.string.settings_legacy_0141
        "知道了" -> R.string.settings_legacy_0142
        "复制" -> R.string.settings_legacy_0143
        "请求批准" -> R.string.settings_legacy_0144
        "帮我批准" -> R.string.settings_legacy_0145
        "失败" -> R.string.settings_legacy_0146
        "完全访问" -> R.string.settings_legacy_0147
        "- 关联专精能力：@Linux沙箱运维专精" -> R.string.settings_text_0001
        "- 失败上下文与日志输出：" -> R.string.settings_text_0002
        "AI 插件" -> R.string.settings_text_0003
        "AI 自愈" -> R.string.settings_text_0004
        "Agent 智能体管理与配置" -> R.string.settings_text_0005
        "Android 12 子进程限制命令" -> R.string.settings_text_0006
        "Android 原生 Linux PRoot 沙箱与 AI 结对中枢" -> R.string.settings_text_0007
        "Android 原生 Linux PRoot 沙箱与 AI 结对编程中枢" -> R.string.settings_text_0008
        "GGUF 下载地址" -> R.string.settings_text_0009
        "GGUF 文件路径" -> R.string.settings_text_0010
        "JSON 语法解析错误" -> R.string.settings_text_0011
        "JSON 配置已复制" -> R.string.settings_text_0012
        "M3 曜石天鹅绒暗色，沉浸专注" -> R.string.settings_text_0013
        "MCP JSON 配置" -> R.string.settings_text_0014
        "MCP JSON 配置 (Cursor / Claude 格式)" -> R.string.settings_text_0015
        "MCP 插件与协议生态" -> R.string.settings_text_0016
        "Max Tokens 需为正整数" -> R.string.settings_text_0017
        "PRoot 宿主存储映射 (-b)" -> R.string.settings_text_0018
        "SHA-256（可选）" -> R.string.settings_text_0019
        "SSE 端点 URL" -> R.string.settings_text_0020
        "Temperature 需为 0.0 ~ 2.0 的数字" -> R.string.settings_text_0021
        "Top P 需为 0.0 ~ 1.0 的数字" -> R.string.settings_text_0022
        "Web 网关服务" -> R.string.settings_text_0023
        "llama.cpp 沙箱离线模型" -> R.string.settings_text_0024
        "mcpServers 不是有效的 JSON 对象" -> R.string.settings_text_0025
        "v0.3.0 稳定版" -> R.string.settings_text_0026
        "✓ 已就绪" -> R.string.settings_text_0027
        "【Agent 自愈目标与行动指南】：" -> R.string.settings_text_0028
        "【系统工具安装失败诊断与沙箱自愈任务】" -> R.string.settings_text_0029
        "一次性命令" -> R.string.settings_text_0030
        "一键安装" -> R.string.settings_text_0031
        "上下文 Token 需为正整数" -> R.string.settings_text_0032
        "上下文 Token 预算上限" -> R.string.settings_text_0033
        "上下文记忆与智能压缩" -> R.string.settings_text_0034
        "下载" -> R.string.settings_text_0035
        "下载目录 (Download)" -> R.string.settings_text_0036
        "下载线路与镜像加速：" -> R.string.settings_text_0037
        "主智能体按任务需要自主选择角色并并行委派" -> R.string.settings_text_0038
        "主系统" -> R.string.settings_text_0039
        "交互式终端" -> R.string.settings_text_0040
        "仅作为新建会话初始值，已存在会话可在聊天顶部单独切换。" -> R.string.settings_text_0041
        "仅在用户明确要求并行或子智能体协同时派发" -> R.string.settings_text_0042
        "仅支持英文、数字、下划线和连字符" -> R.string.settings_text_0043
        "从路径导入" -> R.string.settings_text_0044
        "优化长任务 Token 消耗，防止触及模型上下文窗口上限" -> R.string.settings_text_0045
        "传输协议类型" -> R.string.settings_text_0046
        "低内存首选 · 中英日常对话" -> R.string.settings_text_0047
        "使用上方按钮下载或导入 GGUF 模型" -> R.string.settings_text_0048
        "例如：相册照片、项目源码" -> R.string.settings_text_0049
        "依赖项" -> R.string.settings_text_0050
        "假运行中" -> R.string.settings_text_0051
        "停止" -> R.string.settings_text_0052
        "停止服务失败" -> R.string.settings_text_0053
        "全栈开发套件" -> R.string.settings_text_0054
        "全部生态" -> R.string.settings_text_0055
        "全部组件已就绪" -> R.string.settings_text_0056
        "内存不足" -> R.string.settings_text_0057
        "冻结进程，表现为 Agent 推理或命令执行中途停住。建议开启以下两项：" -> R.string.settings_text_0058
        "准备拉取镜像..." -> R.string.settings_text_0059
        "切换特权模式将自动发起授权检测；授权成功后即刻释放对应的高级系统与硬件能力。" -> R.string.settings_text_0060
        "删除子智能体" -> R.string.settings_text_0061
        "删除服务" -> R.string.settings_text_0062
        "删除模型失败" -> R.string.settings_text_0063
        "加入 QQ 交流群 (905971993)" -> R.string.settings_text_0064
        "助手名称" -> R.string.settings_text_0065
        "协议: Apache-2.0 License" -> R.string.settings_text_0066
        "单 Key RPM 需为非负整数" -> R.string.settings_text_0067
        "单回合最大工具轮次上限" -> R.string.settings_text_0068
        "单轮最大工具调用数" -> R.string.settings_text_0069
        "卸载" -> R.string.settings_text_0070
        "卸载工具" -> R.string.settings_text_0071
        "压缩策略" -> R.string.settings_text_0072
        "压缩触发阈值（用户轮次）" -> R.string.settings_text_0073
        "参数（空格分隔）" -> R.string.settings_text_0074
        "可呼叫万象 Agent 在 PRoot 沙箱内自主排查与自愈" -> R.string.settings_text_0075
        "可更新" -> R.string.settings_text_0076
        "后台正在装配开发套件..." -> R.string.settings_text_0077
        "后台电池优化白名单 · 调试监控 · PRoot 控制台" -> R.string.settings_text_0078
        "向 Agent 系统提示词注入领域专业规范与操作指导" -> R.string.settings_text_0079
        "否则厂商省电策略仍会终止进程。" -> R.string.settings_text_0080
        "启动" -> R.string.settings_text_0081
        "启动中" -> R.string.settings_text_0082
        "启动中…" -> R.string.settings_text_0083
        "启动命令" -> R.string.settings_text_0084
        "启动失败" -> R.string.settings_text_0085
        "启动类型" -> R.string.settings_text_0086
        "启动网关后此处将展示可访问链接" -> R.string.settings_text_0087
        "呼叫自愈" -> R.string.settings_text_0088
        "国内镜像" -> R.string.settings_text_0089
        "在此输入自定义系统提示词，支持 {{cur_datetime}} 等宏变量..." -> R.string.settings_text_0090
        "基于 GitHub Releases 自动检测与在线升级" -> R.string.settings_text_0091
        "复制 JSON" -> R.string.settings_text_0092
        "复杂且可并行的任务将由主智能体自行决定是否派发" -> R.string.settings_text_0093
        "多沙箱并存与动态切换" -> R.string.settings_text_0094
        "多轮工具调用超出阈值时，自动压缩历史中间工具输出日志，保留任务首尾与关键状态" -> R.string.settings_text_0095
        "如: sqlite / github / fetch" -> R.string.settings_text_0096
        "安装" -> R.string.settings_text_0097
        "安装中" -> R.string.settings_text_0098
        "安装失败" -> R.string.settings_text_0099
        "安装成功" -> R.string.settings_text_0100
        "安装新 Linux 发行版" -> R.string.settings_text_0101
        "安装新系统" -> R.string.settings_text_0102
        "安装方式" -> R.string.settings_text_0103
        "安装日志正在接收..." -> R.string.settings_text_0104
        "安装路径" -> R.string.settings_text_0105
        "完整共享存储 (/sdcard)" -> R.string.settings_text_0106
        "定制 Agent 初始人设、环境说明与动态宏变量注入" -> R.string.settings_text_0107
        "容器挂载路径 (Linux)" -> R.string.settings_text_0108
        "宿主路径 (Android)" -> R.string.settings_text_0109
        "导入" -> R.string.settings_text_0110
        "导入或下载 GGUF，在 ARM64 设备端通过 llama.cpp 离线推理" -> R.string.settings_text_0111
        "局域网 IP" -> R.string.settings_text_0112
        "工具 ID" -> R.string.settings_text_0113
        "工具信息" -> R.string.settings_text_0114
        "工具安装/环境自检未通过" -> R.string.settings_text_0115
        "工具尚未安装，请先在工具中心完成安装" -> R.string.settings_text_0116
        "工具未找到" -> R.string.settings_text_0117
        "工具调用限制" -> R.string.settings_text_0118
        "已下载" -> R.string.settings_text_0119
        "已停止" -> R.string.settings_text_0120
        "已安装插件" -> R.string.settings_text_0121
        "已导入模型" -> R.string.settings_text_0122
        "已就绪" -> R.string.settings_text_0123
        "已应用" -> R.string.settings_text_0124
        "已提交 llama.cpp 推理引擎安装任务，可在工具中心查看进度" -> R.string.settings_text_0125
        "已有模型传输任务正在进行" -> R.string.settings_text_0126
        "已绑定 0.0.0.0" -> R.string.settings_text_0127
        "已豁免" -> R.string.settings_text_0128
        "已豁免电池优化" -> R.string.settings_text_0129
        "已连通" -> R.string.settings_text_0130
        "应用" -> R.string.settings_text_0131
        "应用内立即更新" -> R.string.settings_text_0132
        "开发套件安装日志" -> R.string.settings_text_0133
        "开发者全栈套件 (Dev Bundles)" -> R.string.settings_text_0134
        "开启上下文智能压缩 (Context Compaction)" -> R.string.settings_text_0135
        "开始下载" -> R.string.settings_text_0136
        "开始安装" -> R.string.settings_text_0137
        "异常" -> R.string.settings_text_0138
        "强制中文 (推荐)" -> R.string.settings_text_0139
        "强制模型思考过程全程使用英文 (English)" -> R.string.settings_text_0140
        "当会话关联了工作区时，执行 base 命令默认以该目录为工作路径 (cwd)" -> R.string.settings_text_0141
        "当会话历史超过该轮数时，启动智能剪裁，最近 4 轮保持无损" -> R.string.settings_text_0142
        "当前活跃" -> R.string.settings_text_0143
        "必选基座" -> R.string.settings_text_0144
        "思考与推理语言偏好 (Thinking Language)" -> R.string.settings_text_0145
        "思考呈现" -> R.string.settings_text_0146
        "思考流、子智能体、Skill 与插件" -> R.string.settings_text_0147
        "思考流与执行表现" -> R.string.settings_text_0148
        "思考过程（包括生成中内容）默认折叠，点击可展开查看" -> R.string.settings_text_0149
        "所有预置 Linux 发行版均已安装完毕！" -> R.string.settings_text_0150
        "手机小模型推荐" -> R.string.settings_text_0151
        "打开应用详情（自启动/后台运行）" -> R.string.settings_text_0152
        "打开终端" -> R.string.settings_text_0153
        "执行上下文" -> R.string.settings_text_0154
        "执行命令（如 npx / uvx / python3）" -> R.string.settings_text_0155
        "技能名称（如: Rust 编译专家）" -> R.string.settings_text_0156
        "技能管理" -> R.string.settings_text_0157
        "挂载点名称" -> R.string.settings_text_0158
        "授权失败" -> R.string.settings_text_0159
        "探测工具" -> R.string.settings_text_0160
        "控制 DeepSeek 等推理模型的思考过程呈现与工具执行上限" -> R.string.settings_text_0161
        "控制台日志已复制" -> R.string.settings_text_0162
        "描述（可选）" -> R.string.settings_text_0163
        "提示词模板内容：" -> R.string.settings_text_0164
        "提示词编辑" -> R.string.settings_text_0165
        "插件与工具中心" -> R.string.settings_text_0166
        "操作" -> R.string.settings_text_0167
        "操作未完成" -> R.string.settings_text_0168
        "效果优先 · 适合 6 GB 以上手机" -> R.string.settings_text_0169
        "数据目录" -> R.string.settings_text_0170
        "文档目录 (Documents)" -> R.string.settings_text_0171
        "新一代通用模型 · 当前按文本模式运行" -> R.string.settings_text_0172
        "新会话默认工具权限" -> R.string.settings_text_0173
        "新增子智能体角色" -> R.string.settings_text_0174
        "新增存储挂载点" -> R.string.settings_text_0175
        "新增挂载" -> R.string.settings_text_0176
        "新增自定义 Skill 技能" -> R.string.settings_text_0177
        "无法读取所选文件" -> R.string.settings_text_0178
        "日志" -> R.string.settings_text_0179
        "日志已复制到剪贴板" -> R.string.settings_text_0180
        "日期" -> R.string.settings_text_0181
        "日期时间" -> R.string.settings_text_0182
        "时区" -> R.string.settings_text_0183
        "时间" -> R.string.settings_text_0184
        "显示名称" -> R.string.settings_text_0185
        "智能体生态" -> R.string.settings_text_0186
        "智能体生态与服务 (Agents & Services)" -> R.string.settings_text_0187
        "暂无服务日志。点击【启动】网关后将在此实时输出控制台信息与异常报错。" -> R.string.settings_text_0188
        "暂无模型" -> R.string.settings_text_0189
        "暂无相关事件日志" -> R.string.settings_text_0190
        "暂无自定义挂载点" -> R.string.settings_text_0191
        "更新" -> R.string.settings_text_0192
        "更新日志：" -> R.string.settings_text_0193
        "最近一次开发套件装配" -> R.string.settings_text_0194
        "服务名称" -> R.string.settings_text_0195
        "服务实时控制台日志" -> R.string.settings_text_0196
        "服务未启动" -> R.string.settings_text_0197
        "服务端口" -> R.string.settings_text_0198
        "服务端点 URL" -> R.string.settings_text_0199
        "未启用" -> R.string.settings_text_0200
        "未安装" -> R.string.settings_text_0201
        "未找到有效的 MCP 服务配置" -> R.string.settings_text_0202
        "未检测" -> R.string.settings_text_0203
        "未豁免" -> R.string.settings_text_0204
        "未豁免 · 后台可能被冻结" -> R.string.settings_text_0205
        "未运行" -> R.string.settings_text_0206
        "本地 LLM" -> R.string.settings_text_0207
        "本地 LLM 推理" -> R.string.settings_text_0208
        "权限:" -> R.string.settings_text_0209
        "权限清单" -> R.string.settings_text_0210
        "查看安装日志" -> R.string.settings_text_0211
        "查看指导词 (Prompt)" -> R.string.settings_text_0212
        "查看详情" -> R.string.settings_text_0213
        "检查新版本 · GitHub 开源仓库 · 官方 QQ 交流群" -> R.string.settings_text_0214
        "检查更新失败，请检查网络" -> R.string.settings_text_0215
        "检测中" -> R.string.settings_text_0216
        "模型ID" -> R.string.settings_text_0217
        "模型传输失败" -> R.string.settings_text_0218
        "模型发现失败" -> R.string.settings_text_0219
        "模型名称" -> R.string.settings_text_0220
        "模型启动失败" -> R.string.settings_text_0221
        "模型服务已启动，并已设为当前对话模型" -> R.string.settings_text_0222
        "模型配置" -> R.string.settings_text_0223
        "正在下载并配置 RootFS..." -> R.string.settings_text_0224
        "正在下载更新安装包..." -> R.string.settings_text_0225
        "正在下载模型" -> R.string.settings_text_0226
        "正在安装 Linux 沙箱..." -> R.string.settings_text_0227
        "正在导入模型" -> R.string.settings_text_0228
        "正在执行后台批量装配流水线，你可自由切换到其他页面" -> R.string.settings_text_0229
        "正在执行批量原子装配流水线..." -> R.string.settings_text_0230
        "正在校验 GGUF" -> R.string.settings_text_0231
        "每行一个请求头，格式 \"Key: Value\"，会追加到 API 请求中" -> R.string.settings_text_0232
        "沙箱运行时探测、高危操作安全拦截与扩展能力" -> R.string.settings_text_0233
        "沙箱进程" -> R.string.settings_text_0234
        "沙箱连通性与工具探测" -> R.string.settings_text_0235
        "注入系统的指导提示词 (System Prompt)：" -> R.string.settings_text_0236
        "添加" -> R.string.settings_text_0237
        "添加 MCP 服务" -> R.string.settings_text_0238
        "添加并启用" -> R.string.settings_text_0239
        "添加挂载" -> R.string.settings_text_0240
        "添加服务" -> R.string.settings_text_0241
        "清空" -> R.string.settings_text_0242
        "清除 Token" -> R.string.settings_text_0243
        "点击上方“新增挂载”将任意 Android 目录映射到 Linux 沙箱中" -> R.string.settings_text_0244
        "点击右上角 + 添加" -> R.string.settings_text_0245
        "点击快捷插入动态宏变量：" -> R.string.settings_text_0246
        "版本: v0.3.0 (Material 3 Expressive)" -> R.string.settings_text_0247
        "生成安全 Token" -> R.string.settings_text_0248
        "用户名称" -> R.string.settings_text_0249
        "由模型根据上下文或底层默认策略自主决定思考语言" -> R.string.settings_text_0250
        "申请豁免电池优化" -> R.string.settings_text_0251
        "电池优化与后台保活" -> R.string.settings_text_0252
        "电池电量" -> R.string.settings_text_0253
        "直接粘贴 Claude Desktop / Cursor 标准配置：" -> R.string.settings_text_0254
        "确认卸载" -> R.string.settings_text_0255
        "离线" -> R.string.settings_text_0256
        "移动端编程助手 · 适合 6 GB 以上手机" -> R.string.settings_text_0257
        "移动端高质量模型 · 当前按文本模式运行" -> R.string.settings_text_0258
        "端点未返回可用的 Agent 模型" -> R.string.settings_text_0259
        "简要描述" -> R.string.settings_text_0260
        "系统提示词与人设自定义" -> R.string.settings_text_0261
        "系统提示词规则 (System Prompt)" -> R.string.settings_text_0262
        "系统核心常驻" -> R.string.settings_text_0263
        "系统版本" -> R.string.settings_text_0264
        "系统预设快捷挂载" -> R.string.settings_text_0265
        "组件管理与配置" -> R.string.settings_text_0266
        "编程助手" -> R.string.settings_text_0267
        "编辑子智能体角色" -> R.string.settings_text_0268
        "网关服务" -> R.string.settings_text_0269
        "网络下载" -> R.string.settings_text_0270
        "网络下载 GGUF" -> R.string.settings_text_0271
        "群号: 905971993 · 点击一键加群 / 复制群号" -> R.string.settings_text_0272
        "聊天界面中新生成的思考过程将默认展开呈现" -> R.string.settings_text_0273
        "职责简介" -> R.string.settings_text_0274
        "自动 (Auto)" -> R.string.settings_text_0275
        "自动判断并拆分任务" -> R.string.settings_text_0276
        "自动加速" -> R.string.settings_text_0277
        "自动委派策略" -> R.string.settings_text_0278
        "自动注入关联工作区路径" -> R.string.settings_text_0279
        "自定义" -> R.string.settings_text_0280
        "自定义子智能体角色" -> R.string.settings_text_0281
        "自定义技能" -> R.string.settings_text_0282
        "自定义挂载" -> R.string.settings_text_0283
        "自定义映射绑定" -> R.string.settings_text_0284
        "自定义系统提示词 (System Prompt)" -> R.string.settings_text_0285
        "英文 (English)" -> R.string.settings_text_0286
        "装配任务已结束，可查看完整日志" -> R.string.settings_text_0287
        "装配套件" -> R.string.settings_text_0288
        "覆盖全局默认 System Prompt，支持动态宏变量注入" -> R.string.settings_text_0289
        "角色列表" -> R.string.settings_text_0290
        "角色指导词" -> R.string.settings_text_0291
        "角色标识" -> R.string.settings_text_0292
        "解除命令执行成功，已重新读取系统状态。" -> R.string.settings_text_0293
        "触发指令（选填，如 /rust）" -> R.string.settings_text_0294
        "设为主系统" -> R.string.settings_text_0295
        "设备信息" -> R.string.settings_text_0296
        "访问链接" -> R.string.settings_text_0297
        "诊断模式已开启" -> R.string.settings_text_0298
        "该服务已连通，但未返回任何工具定义" -> R.string.settings_text_0299
        "该角色标识已存在" -> R.string.settings_text_0300
        "详情配置" -> R.string.settings_text_0301
        "语言环境" -> R.string.settings_text_0302
        "请先输入 GGUF 的 HTTPS 下载地址" -> R.string.settings_text_0303
        "请勾选待装配组件" -> R.string.settings_text_0304
        "请输入 GGUF 文件路径" -> R.string.settings_text_0305
        "请输入 JSON 内容" -> R.string.settings_text_0306
        "超出时自动压缩旧消息（滑动窗口+摘要记忆）" -> R.string.settings_text_0307
        "轻量代码补全与简单解释" -> R.string.settings_text_0308
        "运行中" -> R.string.settings_text_0309
        "进程保活与唤醒" -> R.string.settings_text_0310
        "远程 HTTP" -> R.string.settings_text_0311
        "连接失败" -> R.string.settings_text_0312
        "连续多轮工具调用全部失败时主动终止，避免陷入死循环空转" -> R.string.settings_text_0313
        "连续失败熔断阈值" -> R.string.settings_text_0314
        "适合本机" -> R.string.settings_text_0315
        "选择下方模型启动服务；启动成功后会自动设为当前对话模型" -> R.string.settings_text_0316
        "选择文件" -> R.string.settings_text_0317
        "选择要下载并安装的 Linux 系统：" -> R.string.settings_text_0318
        "配置中必须包含 command 或 url 字段" -> R.string.settings_text_0319
        "配置详情" -> R.string.settings_text_0320
        "重新生成 Token" -> R.string.settings_text_0321
        "重置模板" -> R.string.settings_text_0322
        "重试" -> R.string.settings_text_0323
        "链接已复制" -> R.string.settings_text_0324
        "错误" -> R.string.settings_text_0325
        "防止复杂任务中模型陷入死循环；达到轮次后将输出总结并请用户分步进行" -> R.string.settings_text_0326
        "防止模型一次性爆发大量工具调用耗尽上下文或失控循环" -> R.string.settings_text_0327
        "随 Android 设备系统深浅色自动切换" -> R.string.settings_text_0328
        "随应用自启动" -> R.string.settings_text_0329
        "需要先安装 llama.cpp 推理引擎" -> R.string.settings_text_0330
        "预置" -> R.string.settings_text_0331
        "验证命令" -> R.string.settings_text_0332
        "默认展开模型思考过程" -> R.string.settings_text_0333
        "📋 JSON 导入" -> R.string.settings_text_0334
        "📝 表单模式" -> R.string.settings_text_0335
        "🧠 AI 自愈" -> R.string.settings_text_0336
        ", target.sizeBytes.toDouble() / (1024 * 1024))} MB 空间）。/workspace 工作区中的代码文件不会受到任何影响。" -> R.string.settings_long_001
        "1. 分析上述 PRoot 沙箱内的失败报错（如 dpkg 依赖破损、锁残留、网络下载受阻、commandLinks 软链接缺失或环境缺失）；" -> R.string.settings_long_002
        "2. 直接调用 base 工具执行针对性的修复命令（如清理 /var/lib/dpkg 锁、dpkg --configure -a、apt-get --fix-broken install、手动从备用源拉取或补齐软链接）；" -> R.string.settings_long_003
        "Android 12+ 会监控应用派生的子进程，超过系统上限后可能终止 PRoot、编译器或 Agent 任务。这里解除的是子进程限制，不是 Java/Kotlin 线程数。" -> R.string.settings_long_004
        "MCP 是开放的标准模型上下文协议。开启后，万象将在 PRoot 沙箱内启动对应的 Stdio 服务或连接本地 SSE 端点，并动态向智枢 Agent 注入专业工具能力。" -> R.string.settings_long_005
        "https://github.com/peakSee/Wanxiang · 欢迎 Star 支持" -> R.string.settings_long_006
        "万象在 Agent 执行期间会启动前台服务并持有 CPU 进程锁，但系统电池优化仍可能在息屏后" -> R.string.settings_long_007
        "万象支持多套 Linux 系统并存。所有系统均自动挂载 /workspace 代码工程，/sdcard 外部存储按「存储挂载与共享」页的开关注入，各发行版软件生态与包管理器完全独立隔离。" -> R.string.settings_long_008
        "尚未配置任何模型档案，可前往【设置 → 模型档案管理】添加 Claude、OpenAI 或 DeepSeek 模型" -> R.string.settings_long_009
        "挂载仅作用于 Linux 沙箱内的进程（终端、智枢 Agent、构建任务与后台服务），不影响文件浏览器——文件浏览器始终直接访问宿主存储。挂载在会话启动时注入，修改后新建的终端 / 构建任务才会应用。完整读写还需在系统设置中授予「所有文件访问」权限。" -> R.string.settings_long_010
        "强约束模型思考过程全程使用中文（解决 DeepSeek/Claude 思考总跑英文的问题）" -> R.string.settings_long_011
        "提示：小米/华为/OPPO 等厂商系统还需在应用详情中手动允许「自启动」与「后台运行」，" -> R.string.settings_long_012
        "服务尚未启动，请先在【网关服务】卡片中点击启动，启动成功后将在此展示可访问链接。Token 可提前生成，启动时自动注入。" -> R.string.settings_long_013
        "架构: aarch64 · chroot-less user-space virtualization" -> R.string.settings_long_014
        "模型未单独配置 contextTokens 时生效；长会话历史超出预算将自动折叠早期内容" -> R.string.settings_long_015
        "粘贴以 .gguf 结尾的 HTTPS 直链。Hugging Face 文件页请复制 Download 链接。" -> R.string.settings_long_017
        "网关服务已绑定全网卡 (0.0.0.0)。同局域网设备打开【局域网 IP 链接】即可直接操作，无需 localhost 映射。" -> R.string.settings_long_018
        "输入应用可读取的绝对路径，例如 /storage/emulated/0/Download/model.gguf。受 Android 存储权限限制时请改用“选择文件”。" -> R.string.settings_long_019
        "通过 llama.cpp 在 Linux 沙箱内运行 GGUF 模型，推理数据不离开设备。支持 HTTPS / Hugging Face 直链断点下载，也可从手机文件选择器导入。" -> R.string.settings_long_020
        else -> null
    }

@Composable
private fun resolveLegacyString(source: String): String {
    legacyStringResource(source)?.let { return stringResource(it) }
    // NOTE: raw 字符串不做转义处理，此处必须写 \d；写成 \\d 会匹配字面 "\d" 导致永不命中。
    Regex("""(\d+) 套系统 · (.+)""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_system_mode, it.groupValues[1], it.groupValues[2])
    }
    Regex("""(\d+) 套系统""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_system_count, it.groupValues[1])
    }
    Regex("""(\d+) 个模型 · (\d+) 技能""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_model_skill_count, it.groupValues[1], it.groupValues[2])
    }
    Regex("""(\d+) 个模型""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_model_count, it.groupValues[1])
    }
    Regex("""(\d+) 个技能""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_skill_count, it.groupValues[1])
    }
    Regex("""(\d+) 个""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_item_count, it.groupValues[1])
    }
    Regex("""(\d+) 轮""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_round_count, it.groupValues[1])
    }
    Regex("""下载中：(.+) / (.+) MB""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_download_progress, it.groupValues[1], it.groupValues[2])
    }
    Regex("""已下载：(.+) MB""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_downloaded, it.groupValues[1])
    }
    if (source.startsWith("发现新版本 v")) {
        return stringResource(R.string.settings_dynamic_new_version, source.removePrefix("发现新版本 v"))
    }
    if (source.startsWith("确定删除 ") && source.endsWith("？")) {
        return stringResource(R.string.settings_dynamic_delete_confirm, source.removePrefix("确定删除 ").removeSuffix("？"))
    }
    Regex("""成功探测到 (\d+) 个工具""").matchEntire(source)?.also {
        return stringResource(R.string.settings_dynamic_tool_count, it.groupValues[1])
    }
    return source
}

/** Compatibility bridge while settings call sites move to direct R.string references. */
@Composable
fun LocalizedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = resolveLegacyString(text), modifier = modifier, color = color,
    fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
    fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
    textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
    softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    onTextLayout = onTextLayout, style = style,
)

@Composable
fun LocalizedText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize,
    fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
    letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
    maxLines = maxLines, minLines = minLines, inlineContent = inlineContent,
    onTextLayout = onTextLayout, style = style,
)
