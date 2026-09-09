package top.wanxiang.app.runtime.proot

import top.wanxiang.app.runtime.shell.ShellCommand
import top.wanxiang.app.runtime.shell.SessionConfig
import top.wanxiang.app.runtime.EnvironmentResolver
import top.wanxiang.app.core.model.StorageMountBinding
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ProotCommandBuilderTest {

    @Test
    fun buildAddsQemuOnlyWhenAnExecutableEmulatorIsProvided() {
        val emulator = Files.createTempFile("qemu-x86_64", ".bin").toFile().apply {
            setExecutable(true)
        }
        try {
            val args = ProotCommandBuilder(EnvironmentResolver()).build(
                prootBinary = File("/p"),
                rootfsDir = File("/compat/rootfs"),
                workspaceDir = File("/w"),
                command = ShellCommand(commandLine = "true"),
                emulatorBinary = emulator,
            )
            val index = args.indexOf("-q")
            assert(index >= 0)
            assertEquals(emulator.absolutePath, args[index + 1])
        } finally {
            emulator.delete()
        }
    }

    @Test
    fun buildProducesProotArgsAndShellCommand() {
        val builder = ProotCommandBuilder(EnvironmentResolver())
        val args = builder.build(
            prootBinary = File("/data/data/app/bin/proot"),
            rootfsDir = File("/data/data/app/rootfs"),
            workspaceDir = File("/data/data/app/workspace"),
            command = ShellCommand(commandLine = "cat /etc/os-release"),
        )

        assertEquals(File("/data/data/app/bin/proot").absolutePath, args[0])
        assert(args.contains("--kill-on-exit"))
        assert(args.contains("--link2symlink"))
        assert(args.contains("-L"))
        assert(args.contains("--sysvipc"))
        assert(args.contains("--kernel-release=6.17.0-WanXiang"))
        assert(args.contains("--change-id=0:0"))
        val l2sBackingStore = File(File("/data/data/app/rootfs"), ".l2s").absolutePath
        assertBinding(args, "$l2sBackingStore:$l2sBackingStore")
        assertBinding(args, "/dev")
        assertBinding(args, "/proc")
        assertBinding(args, "/sys")
        assertBinding(args, "${File("/data/data/app/tmp").absolutePath}:/tmp")
        assertBinding(args, "${File("/data/data/app/workspace").absolutePath}:/workspace")
        assertBinding(args, "${File("/data/data/app/home").absolutePath}:/root")
        assertBinding(args, "${File("/data/data/app/opt/wanxiang").absolutePath}:/opt/wanxiang")
        val workingDirectoryIndex = args.indexOf("-w")
        assertEquals("/root", args[workingDirectoryIndex + 1])
        assertEquals("/bin/sh", args[workingDirectoryIndex + 2])
        assertEquals("-lc", args[workingDirectoryIndex + 3])
        assert(args[workingDirectoryIndex + 4].contains("export HOME='/root'"))
        assert(args[workingDirectoryIndex + 4].contains("export TMPDIR='/tmp'"))
        assert(args[workingDirectoryIndex + 4].contains("cat /etc/os-release"))
    }

    @Test
    fun buildIncludesCustomEnvironmentBeforeCommand() {
        val builder = ProotCommandBuilder(EnvironmentResolver())
        val args = builder.build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(
                commandLine = "echo hi",
                environment = mapOf("FOO" to "bar"),
            ),
        )

        assert(args.last().contains("export FOO='bar'"))
    }

    @Test
    fun interactiveBuildKeepsQuotedCommandLineIntactOnNativePty() {
        val commandLine = "stty raw -echo; exec 'python3' -u '/opt/wanxiang/scripts/codegraph_mcp_server.py' --repository '/workspace'"
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(
                commandLine = commandLine,
                allowSttyResize = false,
            ),
            nativePty = true,
        )

        // 原生 PTY 路径下命令串直接交给 bash -lc，单层解析，禁止任何引号二次转义
        assert(args.last().contains("exec 'python3' -u '/opt/wanxiang/scripts/codegraph_mcp_server.py'"))
        assert(!args.last().contains("\\\""))
    }

    @Test
    fun interactiveBuildEscapesQuotesForScriptFallback() {
        val commandLine = "exec 'python3' -u '/opt/x.py'"
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(
                commandLine = commandLine,
                allowSttyResize = false,
            ),
            ptyMarker = "/opt/wanxiang/.pty-12345678",
        )

        // fallback 路径命令嵌入 script -qfec '...' 单引号内，必须用 '\'' 转义内层单引号
        assert(args.last().contains("exec 'python3' -u '/opt/x.py'"))
        assert(args.last().contains("'\\''"))
    }

    @Test
    fun interactiveBuildUsesAdapterCommandAndEnvironment() {
        val args = ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(
                commandLine = "export PATH=/root/.local/bin:\$PATH && exec codex",
                environment = mapOf("OPENAI_API_KEY" to "secret"),
                allowSttyResize = false,
            ),
            ptyMarker = "/opt/wanxiang/.pty-12345678",
        )

        assert(args.last().contains("exec codex"))
        assert(args.last().contains("export OPENAI_API_KEY='secret'"))
        assert(args.last().contains("/root/.local/bin:\$PATH"))
        assert(args.last().contains("tty > /opt/wanxiang/.pty-12345678"))
        assert(args.contains("-L"))
        val l2sBackingStore = File(File("/r"), ".l2s").absolutePath
        assertBinding(args, "$l2sBackingStore:$l2sBackingStore")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafePtyMarker() {
        ProotCommandBuilder(EnvironmentResolver()).buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            config = SessionConfig(),
            ptyMarker = "/tmp/unsafe",
        )
    }

    @Test
    fun doesNotImplicitlyMountSharedStorage() {
        val args = ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = emptyList(),
        )

        assert(args.none { it.contains(":/sdcard") })
    }

    @Test
    fun skipsMountOverCriticalGuestPathWithoutThrowing() {
        // 非法绑定只跳过不抛异常：require 抛出会沿 build() 冒到运行时初始化，
        // 让 App 启动即闪退且用户进不去设置删绑定（真实回归）。
        val args = ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding("bad", "bad", "/storage/emulated/0", "/root", enabled = true),
            ),
        )
        assert(args.none { it.endsWith(":/root") && it.startsWith("/storage") })
    }

    @Test
    fun nonexistentHostPathNeverThrowsAndNeverBinds() {
        // 用户填了尚未创建的宿主路径：要么自动补建后生效，要么安全跳过——绝不崩溃。
        val host = "/storage/emulated/0/wanxiang-mount-regression"
        val args = ProotCommandBuilder(EnvironmentResolver()).build(
            prootBinary = File("/p"),
            rootfsDir = File("/r"),
            workspaceDir = File("/w"),
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding("t", "t", host, "/mnt/regress", enabled = true),
                StorageMountBinding("evil", "evil", "/etc/sensitive-outside-root", "/mnt/evil", enabled = true),
                StorageMountBinding("colons", "colons", "/storage/emulated/0/a:b", "/mnt/colon", enabled = true),
            ),
        )
        // 越界/含冒号的绑定绝不出现
        assert(args.none { it.contains("/etc/sensitive-outside-root") })
        assert(args.none { it.contains("a:b") })
        // 越界路径绝不自动创建
        assert(!File("/etc/sensitive-outside-root").exists())
        // 共享存储内的缺失路径：自动补建成功则必须绑定（绑定值用 canonical 绝对路径，
        // 与 validateStorageMount 输出一致），失败则跳过——两种都合法，绝不许抛
        val canonicalHost = File(host).canonicalFile.absolutePath
        if (File(host).isDirectory) assertBinding(args, "$canonicalHost:/mnt/regress") else assert(args.none { it.endsWith(":/mnt/regress") })
        File(host).delete()
    }

    private fun assertBinding(args: List<String>, binding: String) {
        val index = args.indexOf(binding)
        assert(index > 0)
        assertEquals("-b", args[index - 1])
    }
}
