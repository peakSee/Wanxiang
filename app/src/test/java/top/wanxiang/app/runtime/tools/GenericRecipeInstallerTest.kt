package top.wanxiang.app.runtime.tools

import top.wanxiang.app.core.common.result.AppResult
import top.wanxiang.app.core.model.InstalledRuntime
import top.wanxiang.app.core.model.RuntimeName
import top.wanxiang.app.core.model.RuntimeRequirement
import top.wanxiang.app.core.model.ToolManifest
import top.wanxiang.app.core.tools.DependencyManager
import top.wanxiang.app.core.tools.ProviderManager
import top.wanxiang.app.runtime.FakeLinuxRuntime
import top.wanxiang.app.runtime.shell.CommandResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericRecipeInstallerTest {

    private class FakeDependencyManager : DependencyManager {
        val acquired = mutableListOf<RuntimeRequirement>()
        override fun requirements(manifest: ToolManifest): List<RuntimeRequirement> = emptyList()
        override suspend fun acquire(requirement: RuntimeRequirement, toolId: String): AppResult<InstalledRuntime> {
            acquired.add(requirement)
            return AppResult.Success(
                InstalledRuntime(
                    id = requirement.name.name.lowercase(),
                    name = requirement.name,
                    version = "20.0.0",
                    executablePath = "/usr/bin/${requirement.name.name.lowercase()}",
                    referenceCount = 1,
                ),
            )
        }
        override suspend fun release(runtimeId: String, toolId: String): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeProviderManager : ProviderManager() {
        override suspend fun environment(): Map<String, String> = mapOf("TEST_ENV" to "1")
    }

    @Test
    fun installsClaudeCodeRecipeSuccessfully() = runBlocking {
        val runtime = FakeLinuxRuntime()
        val depManager = FakeDependencyManager()
        val providerManager = FakeProviderManager()
        val linker = ToolCommandLinker(runtime)

        val manifest = ToolManifest(
            id = "claude-code",
            name = "Claude Code",
            description = "Anthropic CLI",
            dependencies = listOf("node>=20.0.0", "curl"),
            launchType = "pty",
            version = "1.0.0",
            installMethod = "NPM",
            installSteps = listOf("npm install -g @anthropic-ai/claude-code"),
            launchCommand = "claude",
            verifyCommand = "claude --version",
            commandLinks = listOf("claude"),
            environment = mapOf("CLAUDE_CONFIG_DIR" to "/opt/wanxiang/data/claude-code"),
        )

        // 配置命令返回值
        runtime.commandResults["mkdir -p /opt/wanxiang/tools/claude-code/bin /opt/wanxiang/data/claude-code"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["npm install -g @anthropic-ai/claude-code"] =
            CommandResult(0, "added 1 package in 2s", "", 1)
        runtime.commandResults["claude --version"] =
            CommandResult(0, "1.0.0\n", "", 1)

        val installer = GenericRecipeInstaller(
            manifest = manifest,
            linuxRuntime = runtime,
            dependencyManager = depManager,
            providerManager = providerManager,
            toolCommandLinker = linker,
        )

        val events = installer.install().toList()

        assertEquals(listOf(RuntimeName.NODE, RuntimeName.CURL), depManager.acquired.map { it.name })
        assertTrue(events.any { it is InstallEvent.Completed })
        assertTrue(
            runtime.executedCommands.contains(
                "mkdir -p /opt/wanxiang/tools/claude-code/bin /opt/wanxiang/data/claude-code",
            ),
        )
        val completed = events.filterIsInstance<InstallEvent.Completed>().single()
        assertEquals("1.0.0", completed.version)
    }

    @Test
    fun uninstallsRecipeAndRemovesLinkers() = runBlocking {
        val runtime = FakeLinuxRuntime()
        val depManager = FakeDependencyManager()
        val providerManager = FakeProviderManager()
        val linker = ToolCommandLinker(runtime)

        val manifest = ToolManifest(
            id = "claude-code",
            name = "Claude Code",
            description = "Anthropic CLI",
            commandLinks = listOf("claude"),
        )

        runtime.commandResults["rm -f '/opt/wanxiang/bin/claude'"] = CommandResult(0, "", "", 1)
        runtime.commandResults["rm -rf /opt/wanxiang/tools/claude-code"] = CommandResult(0, "", "", 1)

        val installer = GenericRecipeInstaller(
            manifest = manifest,
            linuxRuntime = runtime,
            dependencyManager = depManager,
            providerManager = providerManager,
            toolCommandLinker = linker,
        )

        val result = installer.uninstall(deleteData = false)
        assertTrue(result.success)
    }

    @Test
    fun convertsStructuredInstallerOutputIntoPercentageProgress() = runBlocking {
        val runtime = FakeLinuxRuntime()
        val manifest = ToolManifest(
            id = "progress-tool",
            name = "Progress Tool",
            description = "Progress protocol fixture",
            installSteps = listOf("install-progress-tool"),
            verifyCommand = "progress-tool --version",
        )
        runtime.commandResults["install-progress-tool"] = CommandResult(
            0,
            "[WANXIANG_PROGRESS:50] [EXTRACT] 正在解压测试资源\n",
            "",
            1,
        )
        runtime.commandResults["progress-tool --version"] = CommandResult(0, "1.0.0\n", "", 1)

        val events = GenericRecipeInstaller(
            manifest = manifest,
            linuxRuntime = runtime,
            dependencyManager = FakeDependencyManager(),
            providerManager = FakeProviderManager(),
            toolCommandLinker = ToolCommandLinker(runtime),
        ).install().toList()

        val scripted = events.filterIsInstance<InstallEvent.Progress>()
            .single { it.message == "[EXTRACT] 正在解压测试资源" }
        assertEquals(0.665f, scripted.progress ?: 0f, 0.0001f)
    }
}
