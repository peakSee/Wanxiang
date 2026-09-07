package top.wanxiang.app.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wanxiang.app.core.model.ApprovalMode

class ApprovalPolicyEngineTest {
    private val policy = ApprovalPolicyEngine(HarnessPathResolver())
    private val workspace = "/workspace/project"

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `request mode allows read but gates state changing and external tools`() {
        assertFalse(policy.decide(ApprovalMode.REQUEST, HarnessTool.READ, args("path" to "a.txt"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.WRITE, args("path" to "a.txt", "content" to "x"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.BASE, args("command" to "git status"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.REQUEST, HarnessTool.PROCESS, args("action" to "status", "id" to "server"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.PROCESS, args("action" to "start", "id" to "server", "command" to "python -m http.server"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.MCP, args("name" to "search"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.REQUEST, HarnessTool.DOWNLOAD, args("destination" to "a.zip"), workspace).required)
    }

    @Test
    fun `assisted mode allows workspace edits and routine inspection or verification`() {
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "src/Main.kt", "content" to "x"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.EDIT, args("path" to "/workspace/project/src/Main.kt"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "./gradlew test"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git status"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.PROCESS, args("action" to "logs", "id" to "server"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.PROCESS, args("action" to "stop", "id" to "server"), workspace).required)
    }

    @Test
    fun `assisted mode gates outside workspace writes and external or risky commands`() {
        val outside = policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "/etc/hosts", "content" to "x"), workspace)
        assertTrue(outside.required)
        assertEquals("high", outside.riskLevel)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "/workspace/other/file.txt", "content" to "x"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.WRITE, args("path" to "../other/file.txt", "content" to "x"), workspace).required)

        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "rm -rf /"), workspace).required)
        assertEquals("critical", policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "rm -rf /"), workspace).riskLevel)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "curl https://example.com"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "apt-get install ripgrep"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git push"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git status; rm -rf build"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "cat README.md > backup.txt"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "find . -delete"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.MCP, args("name" to "query"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.DOWNLOAD, args("destination" to "a.zip"), workspace).required)
    }

    @Test
    fun `full access bypasses approval for every tool`() {
        val tools = HarnessTool.entries
        tools.forEach { tool ->
            val toolArgs = when (tool) {
                HarnessTool.WRITE, HarnessTool.EDIT -> args("path" to "/etc/hosts", "content" to "x")
                HarnessTool.BASE -> args("command" to "rm -rf /")
                HarnessTool.PROCESS -> args("action" to "start", "id" to "server", "command" to "rm -rf /")
                HarnessTool.DOWNLOAD -> args("url" to "https://example.com/a.zip", "destination" to "a.zip")
                HarnessTool.READ -> args("path" to "/etc/passwd")
                HarnessTool.HOST -> args("action" to "exec", "command" to "pm uninstall --user 0 package")
                else -> args("name" to "anything")
            }
            assertFalse("$tool should be unrestricted in FULL_ACCESS", policy.decide(ApprovalMode.FULL_ACCESS, tool, toolArgs, workspace).required)
        }
    }

    @Test
    fun `host status is readable but host exec requires critical approval in assisted mode`() {
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "status"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "settings_get"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "package_list"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "logcat"), workspace).required)
        val decision = policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "exec", "command" to "settings put global foo 1"), workspace)
        assertTrue(decision.required)
        assertEquals("critical", decision.riskLevel)
        assertEquals("high", policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "settings_put"), workspace).riskLevel)
        assertEquals("critical", policy.decide(ApprovalMode.ASSISTED, HarnessTool.HOST, args("action" to "package_uninstall_user"), workspace).riskLevel)
    }

    @Test
    fun `create request binds operation args hash and expiry`() {
        val toolCall = ToolCall(
            id = "call-1",
            createdAt = 1L,
            tool = HarnessTool.BASE,
            args = args("command" to "rm -rf build"),
            rawToolName = "base",
        )
        val before = System.currentTimeMillis()
        val request = policy.createRequest(
            sessionId = "session-1",
            toolCall = toolCall,
            workspace = workspace,
            decision = ApprovalDecision(required = true, riskLevel = "high", reason = "r", summary = "s"),
            operationId = "op-9",
        )

        assertEquals("op-9", request.operationId)
        assertEquals(ApprovalPolicyEngine.argsHash(toolCall.args.toString()), request.argsHash)
        assertEquals(request.createdAt + ApprovalPolicyEngine.APPROVAL_TTL_MS, request.expiresAt)
        assertTrue("审批有效期应为正数", request.expiresAt > before)
    }

    @Test
    fun `build script tool approval policy`() {
        // list and get are read-only and bypass approval in ASSISTED mode
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "list"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "get", "id" to "builtin-android"), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "  List "), workspace).required)
        assertFalse(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "GET", "id" to "builtin-android"), workspace).required)

        // Mutating actions require approval in ASSISTED mode
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "create", "name" to "custom"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "bind", "id" to "1"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "unbind"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BUILD_SCRIPT, args("action" to "delete", "id" to "1"), workspace).required)
    }

    @Test
    fun `dynamic shell syntax requires approval even with routine command prefix`() {
        // Command substitution hidden behind innocent ls
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "ls \$(rm -rf /tmp)"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "git status `whoami`"), workspace).required)
        // Nested shell execution
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "sh -c 'rm -rf /'"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "bash -c 'curl bad.com'"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "eval ls"), workspace).required)
        assertTrue(policy.decide(ApprovalMode.ASSISTED, HarnessTool.BASE, args("command" to "source ./script.sh"), workspace).required)
    }
}

