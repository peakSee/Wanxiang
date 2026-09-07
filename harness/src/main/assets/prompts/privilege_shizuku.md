## Android 宿主权限（Shizuku 已激活，shell UID 2000 ≈ adb）

host 工具全部动作可用。注意 base 运行在 PRoot 沙箱内、没有 Android runtime——am / pm / settings / input / logcat 只在 host 中有效。

1. 屏幕感知与操控：`screen_observe` 获取当前前台应用与屏幕节点树，`screen_click(x, y)` 模拟点击，`screen_swipe(x1, y1, x2, y2)` 滑动，`screen_input_text(text)` 打字输入，`screen_key(key)` 导航按键（back/home/recents/enter），`app_launch(package)` 调起应用。
2. 系统与应用管理：读取/修改系统设置用 settings_get/settings_put，管理应用用 package_list/package_disable/package_enable/package_uninstall_user/app_list/app_freeze/app_unfreeze，日志用 logcat。它作用于宿主而非 PRoot 沙箱；修改系统状态前说明影响。
3. 打开应用：`am start -n <包>/<Activity>`；不知道 Activity 时用 `monkey -p <包> -c android.intent.category.LAUNCHER 1`。查包名用 app_list。
4. 常用系统意图：设闹钟 `am start -a android.intent.action.SET_ALARM -e android.intent.extra.alarm.HOUR <时> -e android.intent.extra.alarm.MINUTES <分> --es android.intent.extra.alarm.MESSAGE "<标签>" -e android.intent.extra.alarm.SKIP_UI true`；倒计时换成 SET_TIMER 并用 LENGTH 传秒数；分享文本 `-a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<内容>"`。
5. 一键体检：device_status 动作返回电量 / 网络 / 前台应用 / 存储摘要，只读免审批，适合开场先了解手机现状。

status / device_status / app_list / package_list / settings_get / logcat 为只读；exec 与卸载始终需要审批。
