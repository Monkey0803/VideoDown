package com.videodown.douyin.data.remote

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** One canned HTTP response. */
data class StubResponse(
    val status: Int = 200,
    val body: String = "",
    val location: String? = null,
    val contentType: String = "text/html; charset=UTF-8",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Tiny path-routed HTTP stub used for integration tests.
 *
 * Built on raw `java.net` sockets so no extra test dependency is needed, and so a short-link
 * `302` redirect can be served exactly like Douyin's does.
 */
class StubHttpServer(
    private val routes: Map<String, StubResponse>,
    private val handler: ((String, Map<String, String>) -> StubResponse?)? = null,
) : Closeable {

    private val serverSocket = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val requestedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"
    val paths: List<String> get() = requestedPaths

    private val acceptThread = Thread({ acceptLoop() }, "stub-http-server").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                return
            }
            socket.use { handle(it) }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readRequestLine(input) ?: return
        val path = requestLine.split(' ').getOrNull(1) ?: return
        requestedPaths.add(path)
        val requestHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = readRequestLine(input) ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator > 0) requestHeaders[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        requestHeaders["content-length"]?.toIntOrNull()?.let { input.readNBytes(it) }

        // Match the exact path first, then the longest registered prefix.
        val response = handler?.invoke(path, requestHeaders) ?: routes[path]
            ?: routes.entries
                .filter { path.startsWith(it.key) }
                .maxByOrNull { it.key.length }
                ?.value
            ?: StubResponse(status = 404, body = "not found")

        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ${response.status} ${if (response.status == 200) "OK" else "Status"}\r\n")
            response.location?.let { append("Location: $it\r\n") }
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private fun readRequestLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val current = try {
                input.read()
            } catch (e: Exception) {
                return null
            }
            if (current == -1) return builder.toString().ifBlank { null }
            if (current == '\n'.code) return builder.toString().trim()
            if (current != '\r'.code) builder.append(current.toChar())
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        acceptThread.interrupt()
    }
}
