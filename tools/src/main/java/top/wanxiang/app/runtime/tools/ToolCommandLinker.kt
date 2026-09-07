package top.wanxiang.app.runtime.tools

import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton

/** Creates stable `/opt/wanxiang/bin` shims without exposing arbitrary commands. */
@Singleton
class ToolCommandLinker @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun link(
        command: String,
        target: String,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        require(SAFE_NAME.matches(command)) { "工具命令名称无效" }
        require(SAFE_TARGET.matches(target)) { "工具命令目标路径无效" }
        val link = ToolLayout.commandPath(command)
        // 自指守卫：shim 的 exec 目标绝不能是 shim 自身（直接相等或经软链解析
        // 回自身）。脚本 exec 自己在 PRoot 下是零输出的无限 ptrace 循环。
        require(target != link) { "工具命令目标不能指向自身: $link" }
        val scriptLine = shellQuote("exec $target \"\$@\"")
        val quotedLink = shellQuote(link)
        val quotedTarget = shellQuote(target)
        val quotedBin = shellQuote(ToolLayout.BIN)
        return linuxRuntime.execute(
            ShellCommand(
                // ⚠️ 必须先 rm -f 再重定向：shell 的 `>` 会跟随符号链接。
                // 离线套件的安装脚本已把 /opt/wanxiang/bin/java 建成软链
                // （→ TOOL_DIR/bin/java → JDK 真 ELF）。若直接 `> $link`，
                // 重定向会穿透整条软链，把 exec 包装脚本写进 JDK 的真
                // java ELF —— 包装脚本与指回它的软链互相 exec，构成
                // PRoot 下零输出、CPU 满载的无限回环。rm -f 先摘掉链接
                // 本身，printf 写入的才是新建的普通文件。
                commandLine = "mkdir -p $quotedBin && " +
                    "if [ \"\$(readlink -f $quotedTarget 2>/dev/null || echo $quotedTarget)\" = $quotedLink ]; then " +
                    "echo 'refusing self-referential tool command link: $link -> $target' >&2; exit 1; fi && " +
                    "rm -f $quotedLink && " +
                    "printf '%s\\n' '#!/bin/sh' $scriptLine > $quotedLink && " +
                    "chmod 700 $quotedLink",
                environment = environment,
            ),
        )
    }

    suspend fun remove(command: String, environment: Map<String, String> = emptyMap()): CommandResult {
        require(SAFE_NAME.matches(command)) { "工具命令名称无效" }
        return linuxRuntime.execute(
            ShellCommand(
                commandLine = "rm -f ${shellQuote(ToolLayout.commandPath(command))}",
                environment = environment,
            ),
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private companion object {
        val SAFE_NAME = Regex("[a-z0-9][a-z0-9._+-]{0,63}")
        val SAFE_TARGET = Regex("/[A-Za-z0-9._/@+:-]+")
    }
}
