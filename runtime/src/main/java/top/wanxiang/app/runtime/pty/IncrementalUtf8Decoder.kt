package top.wanxiang.app.runtime.pty

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** UTF-8 流式解码器：保留一次 read 末尾尚未组成完整字符的字节。 */
internal class IncrementalUtf8Decoder(maxChunkBytes: Int) {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteBuffer.allocate(maxChunkBytes + MAX_UTF8_SEQUENCE_BYTES)

    fun decode(input: ByteArray, length: Int): String {
        require(length in 0..input.size)
        require(length <= bytes.remaining()) { "UTF-8 input chunk exceeds decoder capacity" }
        bytes.put(input, 0, length)
        bytes.flip()
        val chars = CharBuffer.allocate(length + MAX_UTF8_SEQUENCE_BYTES)
        decoder.decode(bytes, chars, false)
        bytes.compact()
        chars.flip()
        return chars.toString()
    }

    fun finish(): String {
        bytes.flip()
        val chars = CharBuffer.allocate(bytes.remaining() + MAX_UTF8_SEQUENCE_BYTES)
        decoder.decode(bytes, chars, true)
        decoder.flush(chars)
        bytes.clear()
        chars.flip()
        return chars.toString()
    }

    private companion object {
        const val MAX_UTF8_SEQUENCE_BYTES = 4
    }
}
