package top.wanxiang.app.runtime.browser.cdp

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * RFC 6455 WebSocket 帧编解码（纯 JVM，可单测）。
 *
 * 约定：本端恒为**客户端**——发送帧必须掩码；接收帧（服务器发）不掩码。
 * 不协商 permessage-deflate（握手头不列出，Chrome DevTools 即回纯文本帧）。
 */
object WsFrameCodec {
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** RFC 6455 握手 GUID。 */
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && other.fin == fin && other.opcode == opcode && other.payload.contentEquals(payload)
        override fun hashCode(): Int = fin.hashCode() * 31 + opcode * 31 + payload.contentHashCode()
    }

    /** 客户端发送帧：FIN=1 + 掩码（随机 4 字节 key，逐字节异或）。 */
    fun encodeClientFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        val len = payload.size
        when {
            len < 126 -> header.write(0x80 or len)
            len < 65536 -> {
                header.write(0x80 or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                repeat(8) { i -> header.write(((len.toLong() ushr (8 * (7 - i))) and 0xFF).toInt()) }
            }
        }
        header.write(mask)
        header.write(masked)
        return header.toByteArray()
    }

    fun encodeText(text: String): ByteArray = encodeClientFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun encodeClose(code: Int, reason: String): ByteArray =
        encodeClientFrame(OP_CLOSE, byteArrayOf(((code shr 8) and 0xFF).toByte(), (code and 0xFF).toByte()) + reason.toByteArray(Charsets.UTF_8))

    fun encodePong(pingPayload: ByteArray): ByteArray = encodeClientFrame(OP_PONG, pingPayload)

    /** 生成握手请求（含随机 Sec-WebSocket-Key）。 */
    fun handshakeRequest(path: String, host: String, key: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"

    fun newWebSocketKey(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Sec-WebSocket-Accept = Base64(SHA1(key + GUID))。 */
    fun expectedAccept(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha1)
    }

    /** 从握手响应头中提取指定 header 值（大小写不敏感）。 */
    fun headerValue(headers: String, name: String): String? {
        val pattern = Regex("(?im)^$name:\\s*(.*?)\\s*$")
        return pattern.find(headers)?.groupValues?.get(1)
    }

    /** 校验握手响应：101 + Upgrade: websocket + Sec-WebSocket-Accept 正确。 */
    fun validateHandshake(responseHeaders: String, key: String): Boolean {
        if (!responseHeaders.startsWith("HTTP/1.1 101") && !responseHeaders.startsWith("HTTP/1.0 101")) return false
        if (headerValue(responseHeaders, "Upgrade")?.lowercase() != "websocket") return false
        val accept = headerValue(responseHeaders, "Sec-WebSocket-Accept") ?: return false
        return accept == expectedAccept(key)
    }
}

/**
 * 有状态的服务器帧流解码器：feed 追加字节，吐出所有已完整到达的帧；
 * 不完整帧留在内部缓冲等待后续字节（跨 TCP/LocalSocket 分包）。
 * 线程安全由调用方保证（单读协程喂入）。
 */
class WsFrameDecoder {
    private var buffer = ByteArray(0)

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<WsFrameCodec.Frame> {
        if (length > 0) {
            buffer += bytes.copyOfRange(offset, offset + length)
        }
        val frames = ArrayList<WsFrameCodec.Frame>(2)
        while (true) {
            val frame = tryDecodeNext() ?: break
            frames += frame
        }
        return frames
    }

    private fun tryDecodeNext(): WsFrameCodec.Frame? {
        if (buffer.size < 2) return null
        val b0 = buffer[0].toInt() and 0xFF
        val b1 = buffer[1].toInt() and 0xFF
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        val len7 = b1 and 0x7F

        var pos = 2
        var payloadLen: Long = when (len7) {
            126 -> {
                if (buffer.size < pos + 2) return null
                val v = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                pos += 2
                v.toLong()
            }
            127 -> {
                if (buffer.size < pos + 8) return null
                var v = 0L
                repeat(8) { i -> v = (v shl 8) or (buffer[pos + i].toLong() and 0xFF) }
                pos += 8
                v
            }
            else -> len7.toLong()
        }
        if (payloadLen > Int.MAX_VALUE) {
            throw IllegalStateException("websocket frame too large: $payloadLen bytes")
        }

        // 掩码 key（客户端实现按协议不收掩码帧，但健壮性起见仍按规范解）
        var mask: ByteArray? = null
        if (masked) {
            if (buffer.size < pos + 4) return null
            mask = buffer.copyOfRange(pos, pos + 4)
            pos += 4
        }

        val total = pos + payloadLen.toInt()
        if (buffer.size < total) return null
        var payload = buffer.copyOfRange(pos, total)
        if (mask != null) {
            payload = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        }
        buffer = buffer.copyOfRange(total, buffer.size)
        return WsFrameCodec.Frame(fin, opcode, payload)
    }

    /** 测试用：当前残余缓冲大小。 */
    fun pendingBytes(): Int = buffer.size
}
