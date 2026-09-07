package top.wanxiang.app.runtime.terminal

/**
 * 终端登录横幅。由 [top.wanxiang.app.runtime.LinuxRuntimeImpl] 在终端会话启动前
 * 写入发行版 `/opt/wanxiang/motd`，登录 shell 通过 `cat` 打印，绕开命令串转义问题。
 */
internal fun terminalBanner(): String =
    "万象 · WanXiang Linux AI Runtime\n"
