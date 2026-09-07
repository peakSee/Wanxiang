package top.wanxiang.app.runtime.browser.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * CDP 传输层：devtools 服务在 abstract unix socket 上同时提供
 * HTTP（GET /json 发现 target）与 WebSocket（CDP 会话）两种通道。
 *
 * `android.net.LocalSocket` 不是 `java.net.Socket` 子类，OkHttp 的
 * SocketFactory 路线不可用——因此手写最小 HTTP+WS over socket（零新运行时依赖）。
 * [CdpConnection] 抽象统一 LocalSocket 与 java.net.Socket（测试直连 MockWebServer）。
 */
interface CdpConnection {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/** 每次调用开一条新连接（HTTP 短连接与 WS 长连接都经它获取底层字节流）。 */
interface CdpTransport {
    fun open(timeoutMs: Long = 3_000): CdpConnection
}

/** 传输无关的 WebSocket 连线（测试可直接对接 TCP MockWebServer）。 */
interface WsConnection {
    fun sendText(text: String)
    fun setListener(listener: Listener)
    fun close(code: Int = 1000, reason: String = "client detach")

    interface Listener {
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable)
    }
}

/** abstract unix socket 版传输（主实现）。socketName 不带 `@` 前缀。 */
class LocalSocketCdpTransport(private val socketName: String) : CdpTransport {
    override fun open(timeoutMs: Long): CdpConnection {
        val socket = LocalSocket()
        runCatching {
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        }.onFailure { e ->
            runCatching { socket.close() }
            throw IOException("connect abstract socket '$socketName' failed: ${e.message}", e)
        }
        return object : CdpConnection {
            override val input get() = socket.inputStream
            override val output get() = socket.outputStream
            override fun close() { runCatching { socket.close() } }
        }
    }
}

/** TCP 版传输（仅测试用：直连 MockWebServer 走同一套握手/帧逻辑）。 */
class TcpCdpTransport(private val host: String, private val port: Int) : CdpTransport {
    override fun open(timeoutMs: Long): CdpConnection {
        val socket = Socket()
        runCatching {
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt())
        }.onFailure { e ->
            runCatching { socket.close() }
            throw IOException("connect tcp $host:$port failed: ${e.message}", e)
        }
        return object : CdpConnection {
            override val input get() = socket.inputStream
            override val output get() = socket.outputStream
            override fun close() { runCatching { socket.close() } }
        }
    }
}

/** devtools abstract socket 发现：pid 直连优先，/proc/net/unix 扫描兜底。 */
object DevToolsSocketResolver {
    private const val PREFIX = "webview_devtools_remote_"

    /** 候选列表（按优先级）；调用方逐个试连。 */
    fun candidates(): List<String> {
        val result = ArrayList<String>(2)
        result += PREFIX + android.os.Process.myPid()
        val proc = runCatching {
            java.io.File("/proc/net/unix").readLines()
        }.getOrDefault(emptyList())
        proc.mapNotNull { line ->
            // 列格式：Num RefCount Protocol Flags Type St Inode Path；abstract socket 路径以 @ 开头
            line.trim().split(Regex("\\s+")).lastOrNull()
        }.filter { it.startsWith("@$PREFIX") }
            .map { it.removePrefix("@") }
            .forEach { if (it !in result) result += it }
        return result
    }
}

/** GET over socket：devtools 发现端点（/json、/json/version）。返回响应体字符串。 */
object HttpOverSocket {
    /** 发送 GET 并读完整响应（支持 Content-Length 与读至关闭两种）。 */
    fun get(conn: CdpConnection, path: String, host: String = "127.0.0.1"): String {
        val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: WanXiang-Cdp/1.0\r\n\r\n"
        conn.output.write(req.toByteArray(Charsets.US_ASCII))
        conn.output.flush()

        val reader = BufferedReader(InputStreamReader(conn.input, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: throw IOException("empty response for GET $path")
        if (!statusLine.contains("200")) throw IOException("GET $path -> $statusLine")
        val headers = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers.append(line).append("\r\n")
        }
        val contentLength = Regex("(?i)^Content-Length:\\s*(\\d+)\\s*$", RegexOption.MULTILINE)
            .find(headers.toString())?.groupValues?.get(1)?.toIntOrNull()
        return if (contentLength != null) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(body, 0, read)
        } else {
            // 无 Content-Length：读至关闭（Connection: close 语义）
            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
            }
            sb.toString()
        }
    }
}

/** 在 [CdpConnection] 上完成 WS 握手并返回可用连线。 */
object WsHandshake {
    fun open(conn: CdpConnection, path: String, host: String = "127.0.0.1"): WsConnection =
        StreamWsConnection(conn, path, host)
}

/**
 * 流式 WS 连线：握手（校验 101 + Sec-WebSocket-Accept）→ 独立读线程（阻塞 read）
 * → 解码帧 → listener 回调；写侧 synchronized 串行化；收 ping 自动回 pong。
 */
private class StreamWsConnection(
    private val conn: CdpConnection,
    path: String,
    host: String,
) : WsConnection {
    private var listener: WsConnection.Listener? = null
    private val decoder = WsFrameDecoder()
    @Volatile private var closed = false
    private var closeCode = 1000
    private var closeReason = "client detach"
    // 文本分片重组（CDP 大 payload 可能分片）
    private val textBuffer = java.io.ByteArrayOutputStream()
    // setListener 之前到达的消息缓存（握手后服务器可能立即推送事件），setListener 时重放
    private val earlyTexts = ArrayDeque<String>()
    private val lock = Any()

    init {
        val key = WsFrameCodec.newWebSocketKey()
        conn.output.write(WsFrameCodec.handshakeRequest(path, host, key).toByteArray(Charsets.US_ASCII))
        conn.output.flush()
        val headerBytes = readUntilHeaderEnd()
        val headers = String(headerBytes, Charsets.UTF_8)
        if (!WsFrameCodec.validateHandshake(headers, key)) {
            conn.close()
            throw IOException("websocket handshake rejected for $path: ${headers.lineSequence().firstOrNull() ?: "empty"}")
        }
        // 头部若多读出了帧字节，喂给解码器
        val extra = headerBytes.size - (headers.indexOf("\r\n\r\n") + 4)
        if (extra > 0) {
            handleFrames(decoder.feed(headerBytes, headerBytes.size - extra, extra))
        }
        thread(name = "cdp-ws-read", isDaemon = true) { readLoop() }
    }

    /** 逐字节读至 \r\n\r\n（握手响应头结束；多读的帧字节由调用方处理）。 */
    private fun readUntilHeaderEnd(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var last3 = 0
        while (true) {
            val b = conn.input.read()
            if (b < 0) throw IOException("eof during websocket handshake")
            out.write(b)
            last3 = ((last3 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last3 == 0x0D0A0D0A) break
        }
        return out.toByteArray()
    }

    override fun sendText(text: String) {
        if (closed) throw IOException("ws closed")
        synchronized(conn) {
            conn.output.write(WsFrameCodec.encodeText(text))
            conn.output.flush()
        }
    }

    override fun setListener(listener: WsConnection.Listener) {
        // 重放必须在锁内：否则与读线程的直达分发（listener 已就位）交错会打乱消息顺序
        synchronized(lock) {
            this.listener = listener
            earlyTexts.forEach(listener::onText)
            earlyTexts.clear()
        }
    }

    override fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        closeCode = code
        closeReason = reason
        runCatching {
            synchronized(conn) {
                conn.output.write(WsFrameCodec.encodeClose(code, reason))
                conn.output.flush()
            }
        }
        // 不立即断 socket：给服务器完成关闭握手（回 close 帧）的时间，500ms 后兜底强制关闭
        thread(name = "cdp-ws-close", isDaemon = true) {
            Thread.sleep(500)
            conn.close()
        }
    }

    private fun readLoop() {
        try {
            val buf = ByteArray(16 * 1024)
            while (!closed) {
                val n = conn.input.read(buf)
                if (n < 0) {
                    finish(null)
                    return
                }
                if (n > 0) handleFrames(decoder.feed(buf, 0, n))
            }
        } catch (e: Exception) {
            finish(e)
        }
    }

    private fun handleFrames(frames: List<WsFrameCodec.Frame>) {
        for (f in frames) {
            when (f.opcode) {
                WsFrameCodec.OP_TEXT -> {
                    textBuffer.write(f.payload)
                    if (f.fin) {
                        val text = textBuffer.toString("UTF-8")
                        textBuffer.reset()
                        dispatchText(text)
                    }
                }
                WsFrameCodec.OP_BINARY -> {
                    textBuffer.write(f.payload)
                    if (f.fin) {
                        // CDP 不应出现二进制帧；防御性按文本处理
                        val text = textBuffer.toString("UTF-8")
                        textBuffer.reset()
                        dispatchText(text)
                    }
                }
                WsFrameCodec.OP_CONTINUATION -> {
                    textBuffer.write(f.payload)
                }
                WsFrameCodec.OP_PING -> synchronized(conn) {
                    conn.output.write(WsFrameCodec.encodePong(f.payload))
                    conn.output.flush()
                }
                WsFrameCodec.OP_PONG -> Unit // 无需处理
                WsFrameCodec.OP_CLOSE -> {
                    val code = if (f.payload.size >= 2)
                        ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF) else 1005
                    finish(null, code, String(f.payload.copyOfRange(2.coerceAtMost(f.payload.size), f.payload.size), Charsets.UTF_8))
                    return
                }
            }
        }
    }

    /** listener 未就绪时缓存消息，setListener 时重放（避免握手后立即推送的事件丢失）。 */
    private fun dispatchText(text: String) {
        val l: WsConnection.Listener?
        synchronized(lock) {
            l = listener
            if (l == null) {
                earlyTexts += text
                return
            }
        }
        l!!.onText(text)
    }

    /** 读循环终结：服务器关闭 / 出错。 */
    private fun finish(error: Exception?, serverCode: Int? = null, serverReason: String = "") {
        if (error == null && !closed) {
            // 服务器主动关（EOF 或 close 帧）
            closed = true
            listener?.onClosed(serverCode ?: 1006, serverReason.ifEmpty { "server closed" })
        } else if (error == null && closed) {
            // 我方 close 后 socket 关闭的收尾：不回调（close 已知情）
        } else {
            closed = true
            listener?.onFailure(error ?: IOException("ws closed"))
        }
        conn.close()
    }
}
