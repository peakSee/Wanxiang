package top.wanxiang.app.harness

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.McpToolInfo

class BuiltinToolContractTest {
    @Test
    fun `build script tool exposes lifecycle and binding actions`() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "build_script" }
        val action = tool.function.parameters["properties"]!!.jsonObject["action"]!!.jsonObject
        val values = action["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("list", "get", "create", "update", "delete", "bind", "unbind"), values)
    }

    @Test
    fun baseExposesBoundedTimeoutOverride() {
        val base = ProviderClient.TOOLS.single { it.function.name == "base" }
        val timeout = base.function.parameters["properties"]
            ?.jsonObject
            ?.get("timeout_seconds")
            ?.jsonObject

        assertNotNull(timeout)
        assertEquals("1", timeout?.get("minimum")?.toString())
        assertEquals("3600", timeout?.get("maximum")?.toString())
    }

    @Test
    fun processExposesManagedLifecycleActions() {
        val process = ProviderClient.TOOLS.single { it.function.name == "process" }
        val encoded = process.function.parameters.toString()

        listOf("start", "status", "logs", "list", "stop").forEach { action ->
            assertTrue(encoded.contains("\"$action\""))
        }
        assertTrue(process.function.description.contains("不要使用 nohup"))
    }

    @Test
    fun historyToolsExposeSearchAndReadContracts() {
        val search = ProviderClient.TOOLS.single { it.function.name == "history_search" }
        val read = ProviderClient.TOOLS.single { it.function.name == "history_read" }
        assertTrue(search.function.parameters.toString().contains("query"))
        assertTrue(read.function.parameters.toString().contains("message_id"))
        assertTrue(read.function.parameters.toString().contains("index"))
    }

    @Test
    fun subagentToolUsesDepartmentIndexRoutingWithoutRequiringExactRole() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "invoke_subagent" }
        val subagents = tool.function.parameters["properties"]!!.jsonObject["subagents"]!!.jsonObject
        val item = subagents["items"]!!.jsonObject
        val properties = item["properties"]!!.jsonObject
        val required = item["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val departments = properties["department"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        assertTrue("department" in required)
        assertTrue("agentQuery" in required)
        assertTrue("prompt" in required)
        assertTrue("writePaths" in required)
        assertTrue("writePaths" in properties)
        assertTrue("role" in properties)
        assertTrue("role" !in required)
        assertEquals(9, departments.size)
        assertEquals("6", subagents["maxItems"].toString())
        assertTrue(tool.function.description.contains("本地索引"))
        assertTrue(tool.function.description.contains("同一次调用"))
    }

    @Test
    fun dualAgentToolExposesIsolatedModelAndDagControls() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "invoke_dual_agent" }
        val properties = tool.function.parameters["properties"]!!.jsonObject
        val required = tool.function.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("prompt"), required)
        assertTrue("planner_model" in properties)
        assertTrue("executor_model" in properties)
        assertEquals("30", properties["max_steps"]!!.jsonObject["maximum"].toString())
        assertTrue(tool.function.description.contains("DAG"))
    }

    @Test
    fun hostExposesStatusAndPrivilegedExec() {
        val host = ProviderClient.TOOLS.single { it.function.name == "host" }
        val encoded = host.function.parameters.toString()
        assertTrue(encoded.contains("\"status\""))
        listOf("exec", "settings_get", "settings_put", "package_list", "package_disable", "package_enable", "package_uninstall_user", "app_list", "app_freeze", "app_unfreeze", "app_grant_permission", "logcat", "screen_observe", "screen_click", "screen_swipe", "screen_input_text", "screen_key", "app_launch", "screen_capture")
            .forEach { action -> assertTrue(encoded.contains("\"$action\"")) }
        assertTrue(host.function.description.contains("Android"))
    }

    @Test
    fun allProviderFunctionNamesUsePortableCharacters() {
        val unsafeMcp = McpToolInfo(
            serverId = "用户/server.with spaces",
            serverName = "测试 MCP",
            name = "files/read:all?",
            description = "test",
        )
        val names = ProviderClient.buildDynamicTools(listOf(unsafeMcp)).map { it.function.name }

        names.forEach { name ->
            assertTrue("invalid provider function name: $name", name.matches(Regex("^[a-zA-Z0-9_-]+$")))
            assertTrue("provider function name is too long: $name", name.length <= 64)
        }
    }
}
