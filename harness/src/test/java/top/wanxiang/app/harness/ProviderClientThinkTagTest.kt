package top.wanxiang.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderClientThinkTagTest {

    @Test
    fun `extractThinkTags separates reasoning from content properly`() {
        val rawContent = "<think>让我先仔细分析一下任务需求</think>这是最终的回复内容。"
        val (content, reasoning) = ProviderClient.extractThinkTags(rawContent, null)
        assertEquals("这是最终的回复内容。", content)
        assertEquals("让我先仔细分析一下任务需求", reasoning)
    }

    @Test
    fun `extractThinkTags handles unclosed think tag gracefully`() {
        val rawContent = "<think>未完成的思考"
        val (content, reasoning) = ProviderClient.extractThinkTags(rawContent, null)
        assertNull(content)
        assertEquals("未完成的思考", reasoning)
    }

    @Test
    fun `stripThinkTags cleanly removes think tags and contents`() {
        val raw = "<think>思考过程 123</think>实际文本"
        val stripped = ProviderClient.stripThinkTags(raw)
        assertEquals("实际文本", stripped)
    }

    @Test
    fun `ThinkTagStreamDemuxer demuxes stream chunks seamlessly across tag boundaries`() {
        val deltas = mutableListOf<String>()
        val reasonings = mutableListOf<String>()
        val demuxer = ThinkTagStreamDemuxer(
            onReasoning = { reasonings.add(it) },
            onDelta = { deltas.add(it) },
        )

        // 模拟跨 chunk 的 <think> 标签分片
        demuxer.onContentChunk("<th")
        demuxer.onContentChunk("ink>深度")
        demuxer.onContentChunk("思考中...")
        demuxer.onContentChunk("</th")
        demuxer.onContentChunk("ink>正文")
        demuxer.onContentChunk("开始。")
        demuxer.flush()

        assertEquals("深度思考中...", demuxer.fullReasoning.toString())
        assertEquals("正文开始。", demuxer.fullText.toString())
        assertEquals("正文开始。", deltas.joinToString(""))
    }

    @Test
    fun `ThinkTagStreamDemuxer breaks reasoning repetition loop`() {
        val reasonings = mutableListOf<String>()
        val demuxer = ThinkTagStreamDemuxer(
            onReasoning = { reasonings.add(it) },
            onDelta = { },
        )

        // 产生 200 字符以上前缀
        demuxer.onExplicitReasoningChunk("开始思考...".repeat(30))
        val repeatedPhrase = "让我们重新审视一下这个问题。"
        // 重复发送同一个 15 字符的短语 10 次
        repeat(10) {
            demuxer.onExplicitReasoningChunk(repeatedPhrase)
        }

        assertTrue(demuxer.fullReasoning.contains("思维链重复自旋死循环"))
    }
}
