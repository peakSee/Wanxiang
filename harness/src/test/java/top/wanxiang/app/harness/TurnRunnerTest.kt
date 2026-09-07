package top.wanxiang.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRunnerTest {
    private val runner = TurnRunner(ProviderResponseNormalizer(Json { ignoreUnknownKeys = true }))

    @Test
    fun `provider failure stops before publication and effects`() = runBlocking {
        var persisted = false
        var executed = false

        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = { TurnProviderOutcome.Failed("offline") },
            persistAssistant = { persisted = true },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> executed = true; true },
        )

        assertEquals(TurnOutcome.Failed("offline"), outcome)
        assertFalse(persisted)
        assertFalse(executed)
    }

    @Test
    fun `malformed textual tool request is published then fails closed`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(
                    ChatResult(content = null, toolCalls = emptyList()),
                    "<gateway_tool_call>read<gateway_argkey>path",
                )
            },
            observeResponse = { events += "observed" },
            persistAssistant = { events += "persisted:${it.displayText}" },
            consumeFollowUps = { events += "follow-up"; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> events += "executed"; true },
        )

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(listOf("observed", "persisted:"), events)
    }

    @Test
    fun `text tool protocol normalizes before limit and execution`() = runBlocking {
        val events = mutableListOf<String>()
        var executedNames = emptyList<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(
                    ChatResult(content = null, toolCalls = emptyList(), reasoningContent = "why"),
                    "准备[[tool_call]]{\"name\":\"read\",\"arguments\":{\"path\":\"a.kt\"}}[[/tool_call]]完成",
                )
            },
            persistAssistant = { normalized ->
                events += "persisted:${normalized.displayText}:${normalized.toolCalls.size}"
            },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> events += "limited"; calls.take(1) },
            executeTools = { calls, result ->
                events += "executed:${result.reasoningContent}"
                executedNames = calls.map { it.name }
                true
            },
        )

        assertEquals(TurnOutcome.Continue(1, toolsHadSuccess = true), outcome)
        assertEquals(listOf("read"), executedNames)
        assertEquals(listOf("persisted:准备完成:1", "limited", "executed:why"), events)
    }

    @Test
    fun `plain answer completes only after durable publication`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult("done", emptyList()), "done")
            },
            persistAssistant = { events += "persisted" },
            consumeFollowUps = { events += "follow-ups"; 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Complete, outcome)
        assertEquals(listOf("persisted", "follow-ups"), events)
    }

    @Test
    fun `follow up advances to another turn without tool execution`() = runBlocking {
        val outcome = runner.run(
            toolsEnabled = true,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult("first", emptyList()), "first")
            },
            persistAssistant = {},
            consumeFollowUps = { 2 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Continue(0, toolsHadSuccess = true, followUpCount = 2), outcome)
    }

    @Test
    fun `pure chat does not interpret textual tool markers`() = runBlocking {
        val marker = "[[tool_call]]{\"name\":\"read\",\"arguments\":{}}[[/tool_call]]"
        var published = ""
        val outcome = runner.run(
            toolsEnabled = false,
            callProvider = {
                TurnProviderOutcome.Success(ChatResult(marker, emptyList()), marker)
            },
            persistAssistant = { published = it.displayText },
            consumeFollowUps = { 0 },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = { _, _ -> error("must not execute") },
        )

        assertEquals(TurnOutcome.Complete, outcome)
        assertEquals(marker, published)
    }
}
