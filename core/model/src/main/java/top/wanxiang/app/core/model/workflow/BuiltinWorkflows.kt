package top.wanxiang.app.core.model.workflow

object BuiltinWorkflows {
    val all: List<WorkflowDefinition>
        get() = listOf(
            systemHealth,
            conditionBranchDemo,
            networkDiagnostic,
            releaseApk,
            installGeneratedApk,
            buildDoctor,
            buildRepair,
            reverseAudit,
            atomicCommit,
            hostAutomationLab,
            hostBroadcastDemo,
            hostAgentGuiPilot,
        ).map(WorkflowLayout::arrange)

    fun find(id: String): WorkflowDefinition? = all.firstOrNull { it.id == id }

    private fun chain(vararg ids: String): List<WorkflowEdge> = ids.toList().zipWithNext().mapIndexed { index, pair ->
        WorkflowEdge("edge_$index", pair.first, "success", pair.second)
    }

    val systemHealth = WorkflowDefinition(
        id = "linux_system_health",
        name = "Linux 沙箱环境与硬件体检",
        description = "零配置一键体检：全面检查沙箱 Linux 发行版内核、架构、CPU、内存、磁盘及常用工具链状态。",
        category = "体检",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf linux_system_health"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始体检", canvasX = 40f, canvasY = 120f),
            WorkflowNode(
                "os_kernel",
                WorkflowNodeType.BASH_COMMAND,
                "系统与内核检测",
                config = mapOf("command" to "echo '=== 1. 内核与架构 ===' && uname -a && echo '' && echo '=== 2. 发行版信息 ===' && (cat /etc/os-release | head -n 8 || cat /etc/issue || echo '未知发行版')"),
                canvasX = 280f,
                canvasY = 120f,
            ),
            WorkflowNode(
                "hardware",
                WorkflowNodeType.BASH_COMMAND,
                "内存与存储状态",
                config = mapOf("command" to "echo '=== 1. 内存使用情况 ===' && (free -h || cat /proc/meminfo | head -n 4) && echo '' && echo '=== 2. 根目录磁盘空间 ===' && df -h /"),
                canvasX = 520f,
                canvasY = 120f,
            ),
            WorkflowNode(
                "toolchain",
                WorkflowNodeType.BASH_COMMAND,
                "核心工具链排查",
                config = mapOf("command" to "echo '=== 核心命令行工具排查 ===' && for cmd in bash sh git python3 python curl wget make gcc tar find grep awk sed; do which \$cmd 2>/dev/null && echo \"  ✔ \$cmd: 可用 (\$(which \$cmd))\" || echo \"  ✘ \$cmd: 未安装\"; done"),
                canvasX = 760f,
                canvasY = 120f,
            ),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "体检报告生成", canvasX = 1000f, canvasY = 120f),
        ),
        edges = chain("start", "os_kernel", "hardware", "toolchain", "done"),
    )

    val conditionBranchDemo = WorkflowDefinition(
        id = "condition_branch_demo",
        name = "条件分支逻辑测试 (Demo)",
        description = "检测沙箱基础环境，通过条件分支分别走向【分支 A：环境正常】或【分支 B：环境异常】。",
        category = "示例",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf condition_branch_demo"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "启动测试", canvasX = 40f, canvasY = 160f),
            WorkflowNode(
                "check_env",
                WorkflowNodeType.BASH_COMMAND,
                "检测沙箱基础环境",
                config = mapOf("command" to "echo '=== 正在检测 Linux 沙箱根目录 ===' && test -d /etc && echo '✔ /etc 目录存在'"),
                canvasX = 280f,
                canvasY = 160f,
            ),
            WorkflowNode(
                "branch",
                WorkflowNodeType.CONDITION_BRANCH,
                "条件分支分流",
                description = "根据上游检测结果将流程分流至不同处理分支",
                config = mapOf("expression" to "exitCode == 0"),
                failurePolicy = FailurePolicy.CONTINUE,
                canvasX = 520f,
                canvasY = 160f,
            ),
            WorkflowNode(
                "branch_success",
                WorkflowNodeType.BASH_COMMAND,
                "【分支 A】环境完好",
                config = mapOf("command" to "echo '==============================' && echo '🎉【分支 A 触发成功】' && echo '沙箱基础配置完全正常，继续执行业务操作。' && echo '=============================='"),
                canvasX = 780f,
                canvasY = 80f,
            ),
            WorkflowNode(
                "branch_failure",
                WorkflowNodeType.BASH_COMMAND,
                "【分支 B】环境异常告警",
                config = mapOf("command" to "echo '==============================' && echo '⚠️【分支 B 触发成功】' && echo '检测到沙箱缺少必要目录，进入降级与告警流程。' && echo '=============================='"),
                canvasX = 780f,
                canvasY = 240f,
            ),
            WorkflowNode(
                "done",
                WorkflowNodeType.TERMINAL_OUTPUT,
                "测试汇总完成",
                canvasX = 1040f,
                canvasY = 160f,
            ),
        ),
        edges = listOf(
            WorkflowEdge("edge_demo_1", "start", "output", "check_env"),
            WorkflowEdge("edge_demo_2", "check_env", "output", "branch"),
            WorkflowEdge("edge_demo_3", "branch", "output", "branch_success", conditionExpression = "exitCode == 0"),
            WorkflowEdge("edge_demo_4", "branch", "output", "branch_failure", conditionExpression = "exitCode != 0"),
            WorkflowEdge("edge_demo_5", "branch_success", "output", "done"),
            WorkflowEdge("edge_demo_6", "branch_failure", "output", "done"),
        ),
    )

    val networkDiagnostic = WorkflowDefinition(
        id = "network_dns_diagnostic",
        name = "网络连通与 DNS 诊断",
        description = "零配置网络体检：测试容器内 DNS 配置文件、域名解析能力以及公网 HTTP 服务连通性。",
        category = "网络",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf network_dns_diagnostic"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "启动诊断", canvasX = 40f, canvasY = 120f),
            WorkflowNode(
                "dns_conf",
                WorkflowNodeType.BASH_COMMAND,
                "查看 DNS 配置",
                config = mapOf("command" to "echo '=== /etc/resolv.conf ===' && (cat /etc/resolv.conf || echo '无 resolv.conf') && echo '' && echo '=== /etc/hosts ===' && head -n 10 /etc/hosts"),
                canvasX = 280f,
                canvasY = 120f,
            ),
            WorkflowNode(
                "http_ping",
                WorkflowNodeType.BASH_COMMAND,
                "公网 HTTP 请求测试",
                config = mapOf("command" to "echo '=== 测试公网连通性 (HTTP HEAD) ===' && (curl -I -s -m 5 https://www.baidu.com | head -n 4 || curl -I -s -m 5 https://www.qq.com | head -n 4 || curl -I -s -m 5 https://www.bilibili.com | head -n 4 || echo 'curl 测试完成')"),
                canvasX = 520f,
                canvasY = 120f,
            ),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "网络诊断完成", canvasX = 760f, canvasY = 120f),
        ),
        edges = chain("start", "dns_conf", "http_ping", "done"),
    )

    val releaseApk = WorkflowDefinition(
        id = "release_apk_direct_install",
        name = "打包并安装 APK",
        description = "检查仓库、构建 ARM64 Release APK，并在确认后交给宿主安装。",
        category = "Android",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf release_apk_direct_install"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始", canvasX = 40f, canvasY = 80f),
            WorkflowNode("git", WorkflowNodeType.BASH_COMMAND, "检查 Git 状态", config = mapOf("command" to "git status --short"), canvasX = 260f, canvasY = 80f),
            WorkflowNode("build", WorkflowNodeType.TAIXU_BUILD, "构建 Release APK", config = mapOf("projectType" to "android", "task" to "assembleRelease"), timeoutSeconds = 3600, canvasX = 500f, canvasY = 80f),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认安装", description = "构建完成后确认是否安装到宿主设备。", canvasX = 740f, canvasY = 80f),
            WorkflowNode("install", WorkflowNodeType.HOST_ACTION, "安装 APK", config = mapOf("action" to "install-apk", "artifactFrom" to "build"), timeoutSeconds = 600, canvasX = 980f, canvasY = 80f),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "完成", canvasX = 1220f, canvasY = 80f),
        ),
        edges = chain("start", "git", "build", "approve", "install", "done"),
    )

    val buildDoctor = WorkflowDefinition(
        id = "mobile_build_doctor",
        name = "移动构建环境诊断",
        description = "运行 taixu-build doctor 与 analyze，输出可操作的兼容性报告。",
        category = "诊断",
        isBuiltin = true,
        trigger = WorkflowTrigger.Proactive("BUILD_FAILED", suggestionLabel = "诊断构建失败"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始"),
            WorkflowNode("doctor", WorkflowNodeType.TAIXU_BUILD, "环境诊断", config = mapOf("mode" to "doctor"), timeoutSeconds = 900),
            WorkflowNode("analyze", WorkflowNodeType.TAIXU_BUILD, "分析工程", config = mapOf("mode" to "analyze"), timeoutSeconds = 900),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "查看诊断结果"),
        ),
        edges = chain("start", "doctor", "analyze", "done"),
    )

    val installGeneratedApk = WorkflowDefinition(
        id = "install_generated_apk",
        name = "安装最新生成的 APK",
        description = "使用构建产物路径，在人工确认后交给宿主安装。",
        category = "Android",
        isBuiltin = true,
        trigger = WorkflowTrigger.Proactive("APK_GENERATED", suggestionLabel = "安装刚生成的 APK"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "读取 APK", config = mapOf("requiredVariables" to "APK_PATH")),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认安装", description = "确认将刚生成的 APK 安装到宿主设备：\${APK_PATH}", canvasX = 260f),
            WorkflowNode("install", WorkflowNodeType.HOST_ACTION, "安装 APK", config = mapOf("action" to "install-apk"), timeoutSeconds = 600, canvasX = 520f),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "安装请求已提交", canvasX = 780f),
        ),
        edges = chain("start", "approve", "install", "done"),
    )

    val reverseAudit = WorkflowDefinition(
        id = "android_reverse_audit",
        name = "APK 逆向审计",
        description = "解包、反编译后由智能体只读审计，输出带证据的风险报告；已有输出目录不会被覆盖。",
        category = "逆向",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf android_reverse_audit"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "选择 APK", config = mapOf("requiredVariables" to "APK_PATH")),
            WorkflowNode("apktool", WorkflowNodeType.BASH_COMMAND, "资源解包", config = mapOf("command" to "apktool d ${'$'}{APK_PATH} -o ./workflow-output/apktool"), timeoutSeconds = 1200),
            WorkflowNode("jadx", WorkflowNodeType.BASH_COMMAND, "反编译源码", config = mapOf("command" to "jadx -d ./workflow-output/jadx ${'$'}{APK_PATH}"), timeoutSeconds = 1800),
            WorkflowNode("audit", WorkflowNodeType.AGENT_INFERENCE, "只读审计与报告", config = mapOf("prompt" to "只读审计工作区 workflow-output/apktool 和 workflow-output/jadx。先索引并搜索清单、导出组件、网络配置及敏感数据处理，再阅读相关源码。不得修改文件或运行被审计程序。输出 Markdown 报告，列出文件与行号、证据、影响、建议及无法验证的事项；不要把推测写成已验证漏洞。"), timeoutSeconds = 1800),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "审计报告"),
        ),
        edges = chain("start", "apktool", "jadx", "audit", "done"),
    )

    val atomicCommit = WorkflowDefinition(
        id = "git_atomic_smart_commit",
        name = "审阅并提交变更",
        description = "展示变更，等待人工确认后使用传入的提交说明创建本地提交；默认不推送。",
        category = "Git",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf git_atomic_smart_commit"),
        defaultVariables = mapOf("COMMIT_MESSAGE" to "chore: update workspace"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始"),
            WorkflowNode("diff", WorkflowNodeType.BASH_COMMAND, "审阅变更", config = mapOf("command" to "git status --short && git diff --stat && git diff && git diff --cached")),
            WorkflowNode("suggest", WorkflowNodeType.AGENT_INFERENCE, "生成提交建议", config = mapOf("prompt" to "只读审阅以下 Git 变更，输出一条 Conventional Commit 建议和简短风险摘要。不得修改、暂存、提交或推送任何文件。变更：\n${'$'}{diff.output}")),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认本地提交", config = mapOf("requestedVariables" to "COMMIT_MESSAGE")),
            WorkflowNode("commit", WorkflowNodeType.BASH_COMMAND, "创建提交", config = mapOf("command" to "git add -A && git commit -m ${'$'}{COMMIT_MESSAGE}")),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "提交完成"),
        ),
        edges = chain("start", "diff", "suggest", "approve", "commit", "done"),
    )

    val buildRepair = WorkflowDefinition(
        id = "assisted_build_repair",
        name = "确认后修复构建",
        description = "诊断失败原因，确认后委派智能体最小修复，再展示 Diff 并确认重新构建；不会自动提交或推送。",
        category = "诊断",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf assisted_build_repair"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "输入构建错误", config = mapOf("requiredVariables" to "BUILD_ERROR")),
            WorkflowNode("diagnose", WorkflowNodeType.AGENT_INFERENCE, "只读诊断", config = mapOf("prompt" to "只读检查当前工作区，根据以下错误说明根因和最小修复计划。不得修改文件。\n${'$'}{BUILD_ERROR}"), timeoutSeconds = 900),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "允许修复文件", description = "确认允许智能体按诊断结果修改当前工作区。请先确保重要修改已备份。"),
            WorkflowNode("repair", WorkflowNodeType.SUBAGENT_DELEGATE, "实施最小修复", config = mapOf("writePaths" to ".", "prompt" to "按以下诊断实施最小构建修复，保留用户已有修改；禁止删除项目、重置 Git、提交或推送。不能确定时停止并说明。诊断：\n${'$'}{diagnose.output}\n原错误：\n${'$'}{BUILD_ERROR}"), timeoutSeconds = 1800),
            WorkflowNode("diff", WorkflowNodeType.BASH_COMMAND, "检查修复 Diff", config = mapOf("command" to "git status --short && git diff && git diff --cached")),
            WorkflowNode("rebuildApproval", WorkflowNodeType.HUMAN_APPROVAL, "确认重新构建", description = "审阅修复后启动构建验证。拒绝不会回滚已完成的修改。"),
            WorkflowNode("build", WorkflowNodeType.TAIXU_BUILD, "验证构建", config = mapOf("task" to "assembleDebug"), timeoutSeconds = 3600),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "修复验证结果"),
        ),
        edges = chain("start", "diagnose", "approve", "repair", "diff", "rebuildApproval", "build", "done"),
    )

    val hostAutomationLab = WorkflowDefinition(
        id = "host_automation_lab",
        name = "宿主自动化实验室",
        description = "探测权限 → 打开设置 → 等待前台 → 感知屏幕 → 条件分流 → 通知收尾。需 Shizuku/Root 才能跑完整链路。",
        category = "宿主",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf host_automation_lab"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始", canvasX = 40f, canvasY = 160f),
            WorkflowNode(
                "status",
                WorkflowNodeType.HOST_ACTION,
                "检查权限模式",
                config = mapOf("action" to "status"),
                canvasX = 240f,
                canvasY = 160f,
            ),
            WorkflowNode(
                "gate",
                WorkflowNodeType.CONDITION_BRANCH,
                "是否已特权？",
                config = mapOf("expression" to "\${HOST_PRIVILEGED} == true"),
                failurePolicy = FailurePolicy.CONTINUE,
                canvasX = 460f,
                canvasY = 160f,
            ),
            WorkflowNode(
                "launch",
                WorkflowNodeType.HOST_ACTION,
                "打开系统设置",
                config = mapOf("action" to "app_launch", "package" to "com.android.settings"),
                canvasX = 700f,
                canvasY = 60f,
            ),
            WorkflowNode(
                "wait",
                WorkflowNodeType.HOST_ACTION,
                "等待设置进入前台",
                config = mapOf("action" to "wait_foreground", "package" to "com.android.settings", "timeoutSeconds" to "12"),
                canvasX = 920f,
                canvasY = 60f,
            ),
            WorkflowNode(
                "observe",
                WorkflowNodeType.HOST_ACTION,
                "感知屏幕控件",
                config = mapOf("action" to "screen_observe", "onlyInteractive" to "true"),
                canvasX = 1140f,
                canvasY = 60f,
            ),
            WorkflowNode(
                "notify_ok",
                WorkflowNodeType.HOST_ACTION,
                "通知：自动化成功",
                config = mapOf(
                    "action" to "notification",
                    "title" to "万象工作流",
                    "text" to "宿主自动化实验室已完成屏幕感知",
                ),
                canvasX = 1360f,
                canvasY = 60f,
            ),
            WorkflowNode(
                "degraded",
                WorkflowNodeType.HOST_ACTION,
                "降级：仅 Toast 提示",
                config = mapOf(
                    "action" to "toast",
                    "text" to "未检测到 Shizuku/Root，已跳过特权自动化步骤",
                ),
                canvasX = 700f,
                canvasY = 260f,
            ),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "实验室结束", canvasX = 1580f, canvasY = 160f),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", "output", "status"),
            WorkflowEdge("e2", "status", "output", "gate"),
            WorkflowEdge("e3", "gate", "output", "launch", conditionExpression = "exitCode == 0"),
            WorkflowEdge("e4", "gate", "output", "degraded", conditionExpression = "exitCode != 0"),
            WorkflowEdge("e5", "launch", "output", "wait"),
            WorkflowEdge("e6", "wait", "output", "observe"),
            WorkflowEdge("e7", "observe", "output", "notify_ok"),
            WorkflowEdge("e8", "notify_ok", "output", "done"),
            WorkflowEdge("e9", "degraded", "output", "done"),
        ),
    )

    val hostBroadcastDemo = WorkflowDefinition(
        id = "host_broadcast_and_settings",
        name = "广播 + 系统设置演示",
        description = "写入变量 → 发送自定义广播 → 读取/写回亮度相关设置（需特权）。破坏性操作前带人工确认。",
        category = "宿主",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf host_broadcast_and_settings"),
        defaultVariables = mapOf("DEMO_FLAG" to "taixu-workflow"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始"),
            WorkflowNode(
                "vars",
                WorkflowNodeType.SET_VARIABLE,
                "准备演示变量",
                config = mapOf(
                    "variables" to "BROADCAST_ACTION=top.wanxiang.app.action.WORKFLOW_DEMO\nPAYLOAD=\${DEMO_FLAG}",
                ),
            ),
            WorkflowNode(
                "broadcast",
                WorkflowNodeType.HOST_ACTION,
                "发送演示广播",
                config = mapOf(
                    "action" to "send_broadcast",
                    "intentAction" to "\${BROADCAST_ACTION}",
                    "extras" to "source=workflow\npayload=\${PAYLOAD}\nflag:bool=true",
                ),
            ),
            WorkflowNode(
                "read_brightness",
                WorkflowNodeType.HOST_ACTION,
                "读取当前亮度",
                config = mapOf(
                    "action" to "settings_get",
                    "namespace" to "system",
                    "key" to "screen_brightness",
                    "outputVariable" to "BRIGHTNESS",
                ),
            ),
            WorkflowNode(
                "approve",
                WorkflowNodeType.HUMAN_APPROVAL,
                "确认是否微调亮度",
                description = "将把亮度设置为 120（可在拒绝后跳过）。当前值见上游输出。",
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode(
                "gate",
                WorkflowNodeType.CONDITION_BRANCH,
                "用户是否批准？",
                config = mapOf("expression" to "exitCode == 0"),
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode(
                "write_brightness",
                WorkflowNodeType.HOST_ACTION,
                "写入亮度 120",
                config = mapOf(
                    "action" to "settings_put",
                    "namespace" to "system",
                    "key" to "screen_brightness",
                    "value" to "120",
                ),
            ),
            WorkflowNode(
                "delay",
                WorkflowNodeType.DELAY,
                "稍等片刻",
                config = mapOf("seconds" to "1"),
            ),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "演示完成"),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", "output", "vars"),
            WorkflowEdge("e2", "vars", "output", "broadcast"),
            WorkflowEdge("e3", "broadcast", "output", "read_brightness"),
            WorkflowEdge("e4", "read_brightness", "output", "approve"),
            WorkflowEdge("e5", "approve", "output", "gate"),
            WorkflowEdge("e6", "gate", "output", "write_brightness", conditionExpression = "exitCode == 0"),
            WorkflowEdge("e7", "gate", "output", "done", conditionExpression = "exitCode != 0"),
            WorkflowEdge("e8", "write_brightness", "output", "delay"),
            WorkflowEdge("e9", "delay", "output", "done"),
        ),
    )

    /**
     * Agent-in-the-loop GUI pilot: privilege check → approve → optional launch →
     * AGENT_INFERENCE that drives host(screen_*) tools to chase [GUI_GOAL].
     */
    val hostAgentGuiPilot = WorkflowDefinition(
        id = "host_agent_gui_pilot",
        name = "智能体 GUI 试飞",
        description = "Shizuku/Root 下本地循环「感知屏幕 → 模型决策一步 → 点击/输入」完成 GUI 目标。默认：打开 QQ → 万象群(905971993) → 发送试飞文案。请盯屏审批后再跑。",
        category = "宿主",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf host_agent_gui_pilot"),
        defaultVariables = mapOf(
            "GUI_GOAL" to "打开 QQ，优先在搜索框输入群号 905971993 进入「万象」群；搜不到再找群名含「万象」的群。在输入框发送一句：「万象牛逼（来自工作流）」。不要发红包、不要转账、不要改群设置。若找不到群，停止并说明当前看到的会话列表。",
            "TARGET_PACKAGE" to "com.tencent.mobileqq",
        ),
        nodes = listOf(
            WorkflowNode(
                "start",
                WorkflowNodeType.TRIGGER,
                "填写目标",
                config = mapOf("requiredVariables" to "GUI_GOAL"),
            ),
            WorkflowNode(
                "status",
                WorkflowNodeType.HOST_ACTION,
                "检查 Shizuku/Root",
                config = mapOf("action" to "status"),
            ),
            WorkflowNode(
                "priv_gate",
                WorkflowNodeType.CONDITION_BRANCH,
                "特权是否就绪？",
                config = mapOf("expression" to "\${HOST_PRIVILEGED} == true"),
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode(
                "no_priv",
                WorkflowNodeType.HOST_ACTION,
                "提示缺少特权",
                config = mapOf(
                    "action" to "toast",
                    "text" to "请先在设置中授权并切换到 Shizuku 或 Root，再重试本工作流",
                ),
            ),
            WorkflowNode(
                "approve",
                WorkflowNodeType.HUMAN_APPROVAL,
                "确认让智能体操控屏幕",
                description = "智能体将使用 host(screen_observe/click/swipe/input_text/key/app_launch) 自动操作手机界面以完成 GUI_GOAL。\n\n" +
                    "默认目标会打开 QQ、进入万象群(905971993)并发送「万象牛逼（来自工作流）」。请本人盯屏；若界面跳到支付/红包，应拒绝或立刻打断。\n\n" +
                    "拒绝则结束，不会启动操控。",
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode(
                "approve_gate",
                WorkflowNodeType.CONDITION_BRANCH,
                "用户是否批准？",
                config = mapOf("expression" to "exitCode == 0"),
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode(
                "launch",
                WorkflowNodeType.HOST_ACTION,
                "打开目标应用（可选）",
                config = mapOf(
                    "action" to "app_launch",
                    "package" to "\${TARGET_PACKAGE}",
                ),
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode(
                "wait_fg",
                WorkflowNodeType.DELAY,
                "等待界面稳定",
                config = mapOf("seconds" to "2"),
            ),
            WorkflowNode(
                "pilot",
                WorkflowNodeType.HOST_ACTION,
                "GUI 试飞循环",
                config = mapOf(
                    "action" to "gui_pilot",
                    "goal" to "\${GUI_GOAL}",
                    "package" to "\${TARGET_PACKAGE}",
                    "maxSteps" to "18",
                ),
                timeoutSeconds = 900,
            ),
            WorkflowNode(
                "notify",
                WorkflowNodeType.HOST_ACTION,
                "通知试飞结束",
                config = mapOf(
                    "action" to "notification",
                    "title" to "GUI 试飞结束",
                    "text" to "智能体 GUI 试飞已结束，请查看工作流输出",
                ),
                failurePolicy = FailurePolicy.CONTINUE,
            ),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "试飞报告"),
            WorkflowNode("aborted", WorkflowNodeType.TERMINAL_OUTPUT, "已取消或缺少特权"),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", "output", "status"),
            WorkflowEdge("e2", "status", "output", "priv_gate"),
            WorkflowEdge("e3", "priv_gate", "output", "approve", conditionExpression = "exitCode == 0"),
            WorkflowEdge("e4", "priv_gate", "output", "no_priv", conditionExpression = "exitCode != 0"),
            WorkflowEdge("e5", "no_priv", "output", "aborted"),
            WorkflowEdge("e6", "approve", "output", "approve_gate"),
            WorkflowEdge("e7", "approve_gate", "output", "launch", conditionExpression = "exitCode == 0"),
            WorkflowEdge("e8", "approve_gate", "output", "aborted", conditionExpression = "exitCode != 0"),
            WorkflowEdge("e9", "launch", "output", "wait_fg"),
            WorkflowEdge("e10", "wait_fg", "output", "pilot"),
            WorkflowEdge("e11", "pilot", "output", "notify"),
            WorkflowEdge("e12", "notify", "output", "done"),
        ),
    )
}
