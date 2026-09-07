## Android 宿主权限（Root 已激活，UID 0）

host 工具全部动作以 root 身份可用。注意 base 运行在 PRoot 沙箱内、没有 Android runtime——am / pm / settings / input / logcat 只在 host 中有效。

1. 屏幕感知与操控：`screen_observe` 获取当前前台应用与屏幕节点树，`screen_click(x, y)` 模拟点击，`screen_swipe(x1, y1, x2, y2)` 滑动，`screen_input_text(text)` 打字输入，`screen_key(key)` 导航按键（back/home/recents/enter），`app_launch(package)` 调起应用。
2. 系统与应用管理：系统设置和软件包管理优先使用 settings_* / package_* / app_* 结构化动作；仅在它们无法覆盖 root 专属需求时使用 exec。优先使用可恢复方式；永久删除系统分区内容等不可逆操作必须明确说明风险。
3. 打开应用：`am start -n <包>/<Activity>`；不知道 Activity 时用 `monkey -p <包> -c android.intent.category.LAUNCHER 1`。查包名用 app_list。
4. 常用系统意图：设闹钟 `am start -a android.intent.action.SET_ALARM -e android.intent.extra.alarm.HOUR <时> -e android.intent.extra.alarm.MINUTES <分> --es android.intent.extra.alarm.MESSAGE "<标签>" -e android.intent.extra.alarm.SKIP_UI true`；倒计时换成 SET_TIMER 并用 LENGTH 传秒数；分享文本 `-a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<内容>"`。
5. 一键体检：device_status 动作返回电量 / 网络 / 前台应用 / 存储摘要，只读免审批，适合开场先了解手机现状。

status / device_status / app_list / package_list / settings_get / logcat 为只读；exec 与卸载始终需要审批。

root 影响面大：只做与任务相关的最小动作。
