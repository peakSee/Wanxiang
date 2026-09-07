package top.wanxiang.app.runtime.browser.cdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** RFC 6455 帧编解码纯函数测试：掩码、扩展长度、分片、ping/pong、握手。 */
class WsFrameCodecTest {

    @Test
    fun `client text frame roundtrip via decoder`() {
        val payload = """{"id":1,"method":"Debugger.enable"}"""
        val encoded = WsFrameCodec.encodeText(payload)
        // 客户端帧必须置掩码位
        assertTrue("mask bit must be set", (encoded[1].toInt() and 0x80) != 0)
        val frames = WsFrameDecoder().feed(encoded)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertTrue(frame.fin)
        assertEquals(WsFrameCodec.OP_TEXT, frame.opcode)
        assertEquals(payload, String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun `16-bit extended length`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_BINARY, payload)
        assertEquals(126, (encoded[1].toInt() and 0x7F))
        val frames = WsFrameDecoder().feed(encoded)
        assertArrayEquals(payload, frames.single().payload)
    }

    @Test
    fun `64-bit extended length`() {
        val payload = ByteArray(70_000) { (it % 253).toByte() }
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_BINARY, payload)
        assertEquals(127, (encoded[1].toInt() and 0x7F))
        val frames = WsFrameDecoder().feed(encoded)
        assertArrayEquals(payload, frames.single().payload)
    }

    @Test
    fun `byte-at-a-time feed reassembles frame`() {
        val payload = "split-across-reads"
        val encoded = WsFrameCodec.encodeText(payload)
        val decoder = WsFrameDecoder()
        val frames = ArrayList<WsFrameCodec.Frame>()
        // 逐字节喂（模拟最极端的分包）
        encoded.forEach { b -> frames += decoder.feed(byteArrayOf(b)) }
        // 全部喂完后应恰好有一个完整帧
        val complete = frames.filter { it.opcode == WsFrameCodec.OP_TEXT }
        // 逐字节喂时只有最后一个字节才让帧完整
        assertEquals(1, complete.size)
        assertEquals(payload, String(complete.single().payload, Charsets.UTF_8))
    }

    @Test
    fun `multiple frames in single feed`() {
        val a = WsFrameCodec.encodeText("one")
        val b = WsFrameCodec.encodeText("two")
        val c = WsFrameCodec.encodeClose(1000, "bye")
        val merged = ByteArrayOutputStream().apply { write(a); write(b); write(c) }.toByteArray()
        val frames = WsFrameDecoder().feed(merged)
        assertEquals(3, frames.size)
        assertEquals("one", String(frames[0].payload, Charsets.UTF_8))
        assertEquals("two", String(frames[1].payload, Charsets.UTF_8))
        assertEquals(WsFrameCodec.OP_CLOSE, frames[2].opcode)
    }

    @Test
    fun `unmasked server frame decodes`() {
        // 手工构造服务端帧（无掩码）：文本 "hi"
        val payload = "hi".toByteArray(Charsets.UTF_8)
        val frame = byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload
        val frames = WsFrameDecoder().feed(frame)
        assertEquals(1, frames.size)
        assertEquals("hi", String(frames.single().payload, Charsets.UTF_8))
    }

    @Test
    fun `ping pong roundtrip`() {
        val ping = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_PING, "hb".toByteArray())
        val pong = WsFrameCodec.encodePong("hb".toByteArray())
        val decodedPing = WsFrameDecoder().feed(ping)
        val decodedPong = WsFrameDecoder().feed(pong)
        assertEquals(WsFrameCodec.OP_PING, decodedPing.single().opcode)
        assertEquals(WsFrameCodec.OP_PONG, decodedPong.single().opcode)
        assertArrayEquals(decodedPing.single().payload, decodedPong.single().payload)
    }

    @Test
    fun `handshake request and accept validation`() {
        val key = WsFrameCodec.newWebSocketKey()
        val req = WsFrameCodec.handshakeRequest("/devtools/page/ABC", "127.0.0.1", key)
        assertTrue(req.contains("GET /devtools/page/ABC HTTP/1.1"))
        assertTrue(req.contains("Sec-WebSocket-Key: $key"))
        assertTrue(req.contains("Sec-WebSocket-Version: 13"))

        val accept = WsFrameCodec.expectedAccept(key)
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n" +
            "\r\n"
        assertTrue(WsFrameCodec.validateHandshake(resp, key))
        // 错误的 accept 必须拒绝
        assertFalse(WsFrameCodec.validateHandshake(resp, "wrong-key"))
        // 非 101 拒绝
        val not101 = "HTTP/1.1 400 Bad Request\r\n\r\n"
        assertFalse(WsFrameCodec.validateHandshake(not101, key))
    }

    @Test
    fun `header extraction case insensitive`() {
        val headers = "HTTP/1.1 101 Switching Protocols\r\nsec-websocket-accept: abc==\r\n\r\n"
        assertEquals("abc==", WsFrameCodec.headerValue(headers, "Sec-WebSocket-Accept"))
        assertNull(WsFrameCodec.headerValue(headers, "Missing"))
    }

    @Test
    fun `close frame encodes status code`() {
        val encoded = WsFrameCodec.encodeClose(1000, "detach")
        val frames = WsFrameDecoder().feed(encoded)
        val f = frames.single()
        assertEquals(WsFrameCodec.OP_CLOSE, f.opcode)
        val code = ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF)
        assertEquals(1000, code)
        assertEquals("detach", String(f.payload, 2, f.payload.size - 2, Charsets.UTF_8))
    }
}
