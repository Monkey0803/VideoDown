package com.videodown.douyin.data.download

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP/1.1 server used to verify resumable downloads.
 *
 * Written against plain `java.net` sockets (part of `java.base`) so it needs no extra test
 * dependency, and so the tests can do things a stock mock server cannot: emit a `Content-Length`
 * that is longer than the body that actually gets written, in order to simulate a dropped
 * connection mid-transfer.
 */
class RangeAwareTestServer(
    private val payload: ByteArray,
    /** When true the first response body is cut in half and the socket is closed. */
    private val truncateFirstResponse: Boolean = false,
    /** When false the server ignores `Range` and always replies `200` with the whole body. */
    private val supportRanges: Boolean = true,
    private val statusCode: Int = 200,
    private val chunkSize: Int = Int.MAX_VALUE,
    private val chunkDelayMs: Long = 0,
    private val sendContentLength: Boolean = true,
) : Closeable {

    private val serverSocket = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val truncateUsed = AtomicBoolean(false)

    /** Raw `Range` header of every request, in arrival order (`""` when absent). */
    val rangeHeadersSeen = CopyOnWriteArrayList<String>()

    var requestCount: Int = 0
        private set

    val port: Int get() = serverSocket.localPort

    private val acceptThread = Thread({ acceptLoop() }, "test-http-server").apply {
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
            // Policy changes deliberately close a socket while the server is still writing.
            runCatching { socket.use { handle(it) } }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val headers = readHeaders(input) ?: return
        requestCount++

        val rangeHeader = headers["range"].orEmpty()
        rangeHeadersSeen.add(rangeHeader)

        if (statusCode != 200) {
            val range = if (statusCode == 416) "Content-Range: bytes */${payload.size}\r\n" else ""
            writeAscii(socket, "HTTP/1.1 $statusCode Error\r\n${range}Content-Length: 0\r\nConnection: close\r\n\r\n")
            return
        }

        val start = if (supportRanges) {
            rangeHeader.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
        } else {
            0
        }
        val slice = payload.copyOfRange(start.coerceIn(0, payload.size), payload.size)

        val header = buildString {
            if (start > 0 && supportRanges) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes $start-${payload.size - 1}/${payload.size}\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("ETag: \"fixture-etag\"\r\n")
            append("Content-Type: video/mp4\r\n")
            if (sendContentLength) append("Content-Length: ${slice.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        writeAscii(socket, header)

        val out = socket.getOutputStream()
        if (truncateFirstResponse && truncateUsed.compareAndSet(false, true)) {
            out.write(slice, 0, slice.size / 2)
            out.flush()
            // Closing with fewer bytes than Content-Length promised mimics a dropped transfer.
            socket.close()
        } else {
            var offset = 0
            while (offset < slice.size && running.get()) {
                val length = minOf(chunkSize, slice.size - offset)
                out.write(slice, offset, length)
                out.flush()
                offset += length
                if (chunkDelayMs > 0 && offset < slice.size) Thread.sleep(chunkDelayMs)
            }
        }
    }

    /** Parses the request line and headers, returning null for an unreadable request. */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val builder = StringBuilder()
        var previous = -1
        while (true) {
            val current = try {
                input.read()
            } catch (e: Exception) {
                return null
            }
            if (current == -1) return if (builder.isEmpty()) null else parseHeaders(builder.toString())
            builder.append(current.toChar())
            if (previous == '\r'.code && current == '\n'.code && builder.endsWith("\r\n\r\n")) {
                return parseHeaders(builder.toString())
            }
            previous = current
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> =
        raw.split("\r\n")
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()

    private fun writeAscii(socket: Socket, text: String) {
        socket.getOutputStream().apply {
            write(text.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        acceptThread.interrupt()
    }
}
