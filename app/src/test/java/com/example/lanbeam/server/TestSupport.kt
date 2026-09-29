package com.example.lanbeam.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Plain-JVM host for the server under test. */
class TestEnv(root: File) : ServerEnv {
    val shared = File(root, "Shared").apply { mkdirs() }
    val uploads = File(root, "Uploads").apply { mkdirs() }
    override fun sharedDir() = shared
    override fun uploadsDir() = uploads
    override fun deviceName() = "Test Phone"
    override fun addresses() = listOf(LanAddresses.LanAddress("127.0.0.1", "lo", LanAddresses.Kind.WIFI))
    override fun frontendHtml() = "<html>ok</html>"
}

/**
 * Minimal HTTP/1.1 client on a raw socket, so tests see exactly what the server put on the wire
 * (duplicate headers, bodies on HEAD, connection reuse) — things HttpURLConnection hides.
 */
class RawHttp(port: Int, timeoutMs: Int = 5000) : AutoCloseable {
    private val socket = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2000); soTimeout = timeoutMs }
    private val input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
    private val output: OutputStream = socket.getOutputStream()

    class Resp(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
        fun headerCount(name: String) = headers.count { it.first.equals(name, true) }
    }

    fun send(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null) {
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (body != null) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray())
        if (body != null) output.write(body)
        output.flush()
    }

    /** Reads one response. Body length comes from the (first) Content-Length header, as clients do. */
    fun read(method: String = "GET", sink: OutputStream? = null): Resp {
        val statusLine = line()
        val status = statusLine.split(' ')[1].toInt()
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            headers += l.substring(0, i).trim() to l.substring(i + 1).trim()
        }
        val out = ByteArrayOutputStream()
        val target = sink ?: out
        val cl = headers.firstOrNull { it.first.equals("content-length", true) }?.second?.toLong()
        val chunked = headers.any { it.first.equals("transfer-encoding", true) && it.second.contains("chunked", true) }
        if (method != "HEAD") {
            if (cl != null) copyN(input, target, cl)
            else if (chunked) {
                while (true) {
                    val size = line().substringBefore(';').trim().toLong(16)
                    if (size == 0L) { line(); break }
                    copyN(input, target, size); line()
                }
            }
        }
        return Resp(status, headers, out.toByteArray())
    }

    fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Resp {
        send(method, path, headers, body)
        return read(method)
    }

    private fun line(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw java.io.EOFException("connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun copyN(inp: InputStream, out: OutputStream, n: Long) {
        val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw java.io.EOFException("body ended ${n - left}/$n")
            out.write(buf, 0, r); left -= r
        }
    }

    fun outputStream(): OutputStream = output

    override fun close() = socket.close()
}
