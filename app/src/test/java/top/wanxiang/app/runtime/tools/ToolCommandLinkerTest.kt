package top.wanxiang.app.runtime.tools

import top.wanxiang.app.runtime.FakeLinuxRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCommandLinkerTest {
    @Test
    fun createsStableBinShimForToolCommand() = runBlocking {
        val runtime = FakeLinuxRuntime()
        val linker = ToolCommandLinker(runtime)

        val result = linker.link(
            command = "openclaw",
            target = "/opt/wanxiang/tools/openclaw/bin/openclaw",
        )

        assertTrue(result.isSuccess)
        val command = runtime.executedCommands.single()
        assertTrue(command.contains("/opt/wanxiang/bin/openclaw"))
        assertTrue(command.contains("exec /opt/wanxiang/tools/openclaw/bin/openclaw \"\$@\""))
        // 防穿透写：shell 的 `>` 会跟随符号链接。若目标位置已有软链
        // （如离线套件安装脚本建好的 /opt/wanxiang/bin/java → ... → JDK 真
        // ELF），直接重定向会把 exec 包装脚本写进 JDK 本体，构成无限
        // exec 回环。必须先 rm -f 摘掉链接本身再写。
        assertTrue(command.contains("rm -f '/opt/wanxiang/bin/openclaw'"))
        assertTrue(command.indexOf("rm -f '/opt/wanxiang/bin/openclaw'") < command.indexOf("printf"))
    }

    @Test
    fun refusesToWriteShimWhoseTargetResolvesBackToTheShimItself() = runBlocking {
        val runtime = FakeLinuxRuntime()
        val linker = ToolCommandLinker(runtime)

        // 直接自指：shim exec 自己 —— PRoot 下的零输出无限 exec 循环。
        val direct = runCatching {
            linker.link(command = "java", target = "/opt/wanxiang/bin/java")
        }
        assertTrue(direct.isFailure)

        // 经软链解析回自身：目标是可能解析到 /opt/wanxiang/bin/<cmd> 的链接。
        // 宿主侧无法解析沙箱软链，运行期守卫负责拦截；这里验证生成的
        // 命令里带 readlink 自指守卫。
        val runtime2 = FakeLinuxRuntime()
        ToolCommandLinker(runtime2).link(
            command = "java",
            target = "/opt/wanxiang/tools/android-suite-offline/bin/java",
        )
        val command = runtime2.executedCommands.single()
        assertTrue(command.contains("readlink -f"))
        assertTrue(command.contains("refusing self-referential tool command link"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeCommandName() {
        runBlocking {
        ToolCommandLinker(FakeLinuxRuntime()).link("../openclaw", "/tmp/openclaw")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeTargetPath() {
        runBlocking {
            ToolCommandLinker(FakeLinuxRuntime()).link("openclaw", "/tmp/unsafe path")
        }
    }
}
