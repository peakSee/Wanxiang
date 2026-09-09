package top.wanxiang.app.core.model.workflow

/** Privilege floor required by a host workflow action. */
enum class HostWorkflowPrivilege {
    /** App Context / HostBridge only — works without Shizuku/Root. */
    NONE,
    /** Needs Shizuku or Root effective mode. */
    PRIVILEGED,
}

data class HostWorkflowField(
    val key: String,
    val label: String,
    val hint: String = "",
    val required: Boolean = false,
    val multiline: Boolean = false,
)

data class HostWorkflowActionDef(
    val id: String,
    val label: String,
    val category: String,
    val privilege: HostWorkflowPrivilege,
    val description: String = "",
    val fields: List<HostWorkflowField> = emptyList(),
)

/**
 * Catalog of first-class host actions exposed by workflow [HOST_ACTION] nodes.
 * Keep IDs stable — they are persisted in node config.
 */
object HostWorkflowActions {
    val all: List<HostWorkflowActionDef> = listOf(
        HostWorkflowActionDef(
            id = "status",
            label = "权限状态",
            category = "诊断",
            privilege = HostWorkflowPrivilege.NONE,
            description = "报告当前生效的 PRoot / Shizuku / Root 模式与授权状态",
        ),
        HostWorkflowActionDef(
            id = "health",
            label = "宿主桥健康检查",
            category = "诊断",
            privilege = HostWorkflowPrivilege.NONE,
            description = "探测 taixu-host 桥接与无线 ADB 健康状态",
        ),
        HostWorkflowActionDef(
            id = "device_status",
            label = "设备状态快照",
            category = "诊断",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "电池 / 网络 / 前台 Activity / 存储摘要",
        ),
        HostWorkflowActionDef(
            id = "logcat",
            label = "抓取 Logcat",
            category = "诊断",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "抓取近期系统日志（可按 tag 过滤）",
            fields = listOf(
                HostWorkflowField("tag", "Tag（可选）", "例如 TaiXu"),
                HostWorkflowField("tail_lines", "行数", "默认 200"),
            ),
        ),
        HostWorkflowActionDef(
            id = "install-apk",
            label = "安装 APK",
            category = "应用",
            privilege = HostWorkflowPrivilege.NONE,
            description = "通过宿主桥安装 APK（无线 ADB 或系统安装器）",
            fields = listOf(
                HostWorkflowField("artifactFrom", "产物来源节点 ID", "留空则用变量 APK_PATH"),
            ),
        ),
        HostWorkflowActionDef(
            id = "app_launch",
            label = "打开应用",
            category = "应用",
            privilege = HostWorkflowPrivilege.NONE,
            description = "按包名启动应用；无桌面入口时回退 monkey",
            fields = listOf(HostWorkflowField("package", "包名", "例如 com.android.settings", required = true)),
        ),
        HostWorkflowActionDef(
            id = "app_force_stop",
            label = "强制停止应用",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "am force-stop 指定包名",
            fields = listOf(HostWorkflowField("package", "包名", required = true)),
        ),
        HostWorkflowActionDef(
            id = "app_clear_data",
            label = "清除应用数据",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "pm clear（破坏性操作，建议配合人工审批）",
            fields = listOf(HostWorkflowField("package", "包名", required = true)),
        ),
        HostWorkflowActionDef(
            id = "app_freeze",
            label = "冻结 / 禁用应用",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "pm disable-user",
            fields = listOf(
                HostWorkflowField("package", "包名", required = true),
                HostWorkflowField("user", "用户 ID", "默认 0"),
            ),
        ),
        HostWorkflowActionDef(
            id = "app_unfreeze",
            label = "解冻 / 启用应用",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "pm enable",
            fields = listOf(
                HostWorkflowField("package", "包名", required = true),
                HostWorkflowField("user", "用户 ID", "默认 0"),
            ),
        ),
        HostWorkflowActionDef(
            id = "app_grant_permission",
            label = "授予运行时权限",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "pm grant 指定权限",
            fields = listOf(
                HostWorkflowField("package", "包名", required = true),
                HostWorkflowField("permission", "权限名", "例如 android.permission.CAMERA", required = true),
            ),
        ),
        HostWorkflowActionDef(
            id = "app_revoke_permission",
            label = "撤销运行时权限",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "pm revoke 指定权限",
            fields = listOf(
                HostWorkflowField("package", "包名", required = true),
                HostWorkflowField("permission", "权限名", required = true),
            ),
        ),
        HostWorkflowActionDef(
            id = "wait_foreground",
            label = "等待前台应用",
            category = "应用",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "轮询直到指定包名进入前台或超时",
            fields = listOf(
                HostWorkflowField("package", "包名", required = true),
                HostWorkflowField("timeoutSeconds", "超时秒数", "默认 15"),
            ),
        ),
        HostWorkflowActionDef(
            id = "send_broadcast",
            label = "发送广播",
            category = "Intent",
            privilege = HostWorkflowPrivilege.NONE,
            description = "发送显式/隐式广播；受保护广播会自动走特权 am broadcast",
            fields = listOf(
                HostWorkflowField("intentAction", "Action", "例如 android.intent.action.BOOT_COMPLETED", required = true),
                HostWorkflowField("package", "目标包名（可选）"),
                HostWorkflowField("component", "组件（可选）", "pkg/.Receiver"),
                HostWorkflowField("extras", "Extras（多行 key=value）", "flag:bool=true\ncount:int=1", multiline = true),
                HostWorkflowField("preferShell", "强制特权 shell", "true/false，默认自动"),
            ),
        ),
        HostWorkflowActionDef(
            id = "start_activity",
            label = "启动 Activity",
            category = "Intent",
            privilege = HostWorkflowPrivilege.NONE,
            description = "am start / startActivity：组件、Action、Data URI、Extras",
            fields = listOf(
                HostWorkflowField("component", "组件", "pkg/.Activity"),
                HostWorkflowField("intentAction", "Action", "例如 android.intent.action.VIEW"),
                HostWorkflowField("dataUri", "Data URI", "https://… 或 content://…"),
                HostWorkflowField("mimeType", "MIME（可选）"),
                HostWorkflowField("extras", "Extras（多行）", multiline = true),
                HostWorkflowField("preferShell", "强制特权 shell", "true/false"),
            ),
        ),
        HostWorkflowActionDef(
            id = "start_service",
            label = "启动 Service",
            category = "Intent",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "am startservice / start-foreground-service",
            fields = listOf(
                HostWorkflowField("component", "组件", "pkg/.Service", required = true),
                HostWorkflowField("intentAction", "Action（可选）"),
                HostWorkflowField("extras", "Extras（多行）", multiline = true),
                HostWorkflowField("foreground", "前台服务", "true/false，默认 false"),
            ),
        ),
        HostWorkflowActionDef(
            id = "settings_get",
            label = "读取系统设置",
            category = "系统",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "settings get system/secure/global",
            fields = listOf(
                HostWorkflowField("namespace", "命名空间", "system / secure / global", required = true),
                HostWorkflowField("key", "键名", required = true),
                HostWorkflowField("outputVariable", "写入变量名", "默认 SETTINGS_VALUE"),
            ),
        ),
        HostWorkflowActionDef(
            id = "settings_put",
            label = "写入系统设置",
            category = "系统",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "settings put；system 命名空间优先 ContentResolver",
            fields = listOf(
                HostWorkflowField("namespace", "命名空间", "system / secure / global", required = true),
                HostWorkflowField("key", "键名", required = true),
                HostWorkflowField("value", "值", required = true),
            ),
        ),
        HostWorkflowActionDef(
            id = "airplane_mode",
            label = "飞行模式",
            category = "系统",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "开关飞行模式并广播状态变更",
            fields = listOf(HostWorkflowField("enabled", "开启", "true / false", required = true)),
        ),
        HostWorkflowActionDef(
            id = "wifi_set",
            label = "Wi‑Fi 开关",
            category = "系统",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "cmd wifi set-wifi-enabled",
            fields = listOf(HostWorkflowField("enabled", "开启", "true / false", required = true)),
        ),
        HostWorkflowActionDef(
            id = "volume_set",
            label = "调节媒体音量",
            category = "系统",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "设置 STREAM_MUSIC 音量（0–15 常见）",
            fields = listOf(HostWorkflowField("value", "音量", "例如 8", required = true)),
        ),
        HostWorkflowActionDef(
            id = "screen_observe",
            label = "感知屏幕",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "uiautomator dump：前台应用 + 控件树摘要",
            fields = listOf(HostWorkflowField("onlyInteractive", "仅交互节点", "true/false，默认 true")),
        ),
        HostWorkflowActionDef(
            id = "screen_click",
            label = "点击坐标",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "降级：无障碍手势 → cmd input → bin input",
            fields = listOf(
                HostWorkflowField("x", "X", required = true),
                HostWorkflowField("y", "Y", required = true),
            ),
        ),
        HostWorkflowActionDef(
            id = "screen_double_click",
            label = "双击坐标",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            fields = listOf(
                HostWorkflowField("x", "X", required = true),
                HostWorkflowField("y", "Y", required = true),
            ),
        ),
        HostWorkflowActionDef(
            id = "screen_long_press",
            label = "长按坐标",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            fields = listOf(
                HostWorkflowField("x", "X", required = true),
                HostWorkflowField("y", "Y", required = true),
                HostWorkflowField("durationMs", "时长 ms", "默认 800"),
            ),
        ),
        HostWorkflowActionDef(
            id = "screen_swipe",
            label = "滑动屏幕",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            fields = listOf(
                HostWorkflowField("x1", "起点 X", required = true),
                HostWorkflowField("y1", "起点 Y", required = true),
                HostWorkflowField("x2", "终点 X", required = true),
                HostWorkflowField("y2", "终点 Y", required = true),
                HostWorkflowField("durationMs", "时长 ms", "默认 300"),
            ),
        ),
        HostWorkflowActionDef(
            id = "screen_scroll",
            label = "滚动屏幕",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "按方向滚动（up/down/left/right），内部映射为滑动",
            fields = listOf(
                HostWorkflowField("direction", "方向", "up / down / left / right", required = true),
                HostWorkflowField("distanceRatio", "幅度", "0.15–0.8，默认 0.45"),
                HostWorkflowField("durationMs", "时长 ms", "默认 350"),
            ),
        ),
        HostWorkflowActionDef(
            id = "screen_input_text",
            label = "粘贴/输入文本",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "CJK 走剪贴板+粘贴；自动降级 Ctrl+V / ASCII input text",
            fields = listOf(HostWorkflowField("text", "文本", required = true)),
        ),
        HostWorkflowActionDef(
            id = "screen_key",
            label = "发送按键",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "back/home/recents 优先无障碍全局动作，再降级 keyevent",
            fields = listOf(HostWorkflowField("key", "按键", "back/home/recents/enter/delete/paste/power", required = true)),
        ),
        HostWorkflowActionDef(
            id = "screen_capture",
            label = "截取屏幕",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            fields = listOf(HostWorkflowField("path", "保存路径", "/sdcard/Download/taixu-screen.png", required = true)),
        ),
        HostWorkflowActionDef(
            id = "gui_pilot",
            label = "智能体 GUI 试飞循环",
            category = "GUI",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "本地循环：感知屏幕 → 模型输出一步 JSON → 点击/输入。不走聊天子智能体 Lane。",
            fields = listOf(
                HostWorkflowField("goal", "目标", "可空，默认读变量 GUI_GOAL"),
                HostWorkflowField("package", "目标包名", "可空，默认读 TARGET_PACKAGE"),
                HostWorkflowField("maxSteps", "最大步数", "默认 18"),
            ),
        ),
        HostWorkflowActionDef(
            id = "toast",
            label = "弹出 Toast",
            category = "交互",
            privilege = HostWorkflowPrivilege.NONE,
            fields = listOf(HostWorkflowField("text", "内容", required = true)),
        ),
        HostWorkflowActionDef(
            id = "vibrate",
            label = "震动",
            category = "交互",
            privilege = HostWorkflowPrivilege.NONE,
            fields = listOf(HostWorkflowField("durationMs", "时长 ms", "默认 200")),
        ),
        HostWorkflowActionDef(
            id = "clipboard_set",
            label = "写入剪贴板",
            category = "交互",
            privilege = HostWorkflowPrivilege.NONE,
            fields = listOf(HostWorkflowField("text", "文本", required = true)),
        ),
        HostWorkflowActionDef(
            id = "clipboard_get",
            label = "读取剪贴板",
            category = "交互",
            privilege = HostWorkflowPrivilege.NONE,
            description = "读取结果写入 CLIPBOARD 变量",
        ),
        HostWorkflowActionDef(
            id = "notification",
            label = "发送通知",
            category = "交互",
            privilege = HostWorkflowPrivilege.NONE,
            description = "在通知栏发布一条万象工作流通知",
            fields = listOf(
                HostWorkflowField("title", "标题", required = true),
                HostWorkflowField("text", "正文", required = true),
            ),
        ),
        HostWorkflowActionDef(
            id = "shell",
            label = "特权 Shell",
            category = "高级",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "在 Shizuku/Root 下执行任意宿主 shell（高风险）",
            fields = listOf(HostWorkflowField("command", "命令", "例如 dumpsys window | head", required = true, multiline = true)),
        ),
        HostWorkflowActionDef(
            id = "cmd",
            label = "cmd 服务调用",
            category = "高级",
            privilege = HostWorkflowPrivilege.PRIVILEGED,
            description = "执行 /system/bin/cmd <service> …",
            fields = listOf(
                HostWorkflowField("service", "服务名", "例如 wifi / activity / package", required = true),
                HostWorkflowField("args", "参数", "set-wifi-enabled enabled", multiline = true),
            ),
        ),
    )

    fun find(id: String): HostWorkflowActionDef? = all.firstOrNull { it.id == id }

    fun grouped(): Map<String, List<HostWorkflowActionDef>> = all.groupBy { it.category }
}
