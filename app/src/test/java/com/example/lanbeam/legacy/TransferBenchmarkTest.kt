package com.example.lanbeam.legacy

import com.example.lanbeam.server.LanBeamServer
import com.example.lanbeam.server.RawHttp
import com.example.lanbeam.server.TestEnv
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.random.Random

/**
 * (Legacy runs use .mkv: v2.1 asks android.webkit.MimeTypeMap for .mov, which is a stub on the JVM.)
 *
 * Before/after throughput of the v2.1 server vs the rework, same machine, loopback. This measures
 * the SERVER's own ceiling (parsing, disk copies, buffer sizes) with the network taken out; Wi-Fi
 * numbers on a phone are lower and must be measured on-device (see scripts/lanbeam-bench.sh).
 *
 * Opt-in: ./gradlew testDebugUnitTest --tests '*TransferBenchmarkTest*' -Dlanbeam.bench=512  (MB)
 */
class TransferBenchmarkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mb = System.getProperty("lanbeam.bench")?.toIntOrNull() ?: 0
    private fun freePort() = ServerSocket(0).use { it.localPort }
    private val sink = object : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun makeFile(f: File, bytes: Long) {
        val rnd = Random(42)
        val buf = ByteArray(1 shl 20)
        f.outputStream().use { out ->
            var left = bytes
            while (left > 0) { rnd.nextBytes(buf); val n = minOf(left, buf.size.toLong()).toInt(); out.write(buf, 0, n); left -= n }
        }
    }

    private inline fun timed(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e9
    }

    private fun rate(bytes: Long, secs: Double) = String.format(Locale.US, "%.0f MB/s", bytes / 1048576.0 / secs)

    private fun download(port: Int, path: String, range: String? = null, timeoutMs: Int = 30_000) =
        RawHttp(port, timeoutMs).use { c ->
            c.send("GET", "/api/download?path=$path", if (range != null) mapOf("Range" to range) else emptyMap())
            c.read(sink = sink)
        }

    private fun legacyMultipartUpload(port: Int, file: File) = RawHttp(port, 120_000).use { c ->
        val boundary = "----lanbeam${System.nanoTime()}"
        val pre = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
        val post = "\r\n--$boundary--\r\n".toByteArray()
        val len = pre.size + file.length() + post.size
        val out = c.outputStream()
        out.write("POST /api/upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=$boundary\r\nContent-Length: $len\r\n\r\n".toByteArray())
        out.write(pre)
        file.inputStream().use { it.copyTo(out, 1 shl 20) }
        out.write(post); out.flush()
        c.read()
    }

    private fun chunkedUpload(port: Int, file: File, chunk: Int = 8 shl 20) = RawHttp(port, 120_000).use { c ->
        val total = file.length()
        val id = "bench" + System.nanoTime()
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            var off = 0L
            while (true) {
                val n = minOf(chunk.toLong(), total - off).toInt()
                raf.seek(off); raf.readFully(buf, 0, n)
                val r = c.request("POST", "/api/upload/chunk?id=$id&name=${file.name}&offset=$off&total=$total", body = buf.copyOf(n))
                check(r.status == 200) { "chunk failed ${r.status} ${String(r.body)}" }
                off += n
                if (String(r.body).contains("\"done\":true")) break
            }
        }
    }

    private fun pingLatencyMs(port: Int): Double = RawHttp(port).use { c ->
        repeat(50) { c.request("GET", "/api/folders") }
        val secs = timed { repeat(500) { c.request("GET", "/api/folders") } }
        secs * 1000 / 500
    }

    @Test fun benchmark() {
        assumeTrue("set -Dlanbeam.bench=<MB> to run", mb > 0)
        val bytes = mb.toLong() * 1024 * 1024
        val lines = mutableListOf<String>()
        fun report(s: String) { println("BENCH $s"); lines += s }

        // ── legacy v2.1 ──
        val legacyRoot = tmp.newFolder("legacy")
        val legacyShared = File(legacyRoot, "Shared").apply { mkdirs() }
        val src = File(tmp.root, "src.bin").also { makeFile(it, bytes) }
        src.copyTo(File(legacyShared, "clip.mkv"))
        val lp = freePort()
        val legacy = LegacyLanBeamServer(LegacyTestContext(legacyRoot), lp).also { it.start() }
        try {
            val d = timed { download(lp, "clip.mkv") }
            report("legacy download ${mb}MB: ${rate(bytes, d)} (${"%.2f".format(d)} s)")
            val resumeAt = (bytes * 0.96).toLong()
            val resume = runCatching { timed { download(lp, "clip.mkv", "bytes=$resumeAt-", timeoutMs = 10_000) } }
            report("legacy resume from 96%: " + (resume.exceptionOrNull()?.let {
                when (it) {
                    is SocketTimeoutException -> "NEVER COMPLETES (client still waiting after 10 s)"
                    is java.io.EOFException -> "NEVER COMPLETES (${it.message}: advertised the full file, sent only the remainder, then closed)"
                    else -> "error ${it.javaClass.simpleName}: ${it.message}"
                }
            } ?: "completed in ${"%.2f".format(resume.getOrThrow())} s"))
            val u = runCatching { timed { legacyMultipartUpload(lp, src).also { check(it.status == 200) { "status ${it.status}" } } } }
            report("legacy upload ${mb}MB (multipart): " + (u.getOrNull()?.let { "${rate(bytes, it)} (${"%.2f".format(it)} s)" } ?: "FAILED ${u.exceptionOrNull()?.message}"))
            report("legacy request latency: ${"%.2f".format(pingLatencyMs(lp))} ms")
        } finally {
            legacy.stop()
        }

        // ── rework ──
        val env = TestEnv(tmp.newFolder("new"))
        src.copyTo(File(env.shared, "clip.mov"))
        val np = freePort()
        val server = LanBeamServer(env, np, "127.0.0.1").also { it.startServer() }
        try {
            val d = timed { download(np, "clip.mov") }
            report("new download ${mb}MB: ${rate(bytes, d)} (${"%.2f".format(d)} s)")
            val resumeAt = (bytes * 0.96).toLong()
            val r = timed { download(np, "clip.mov", "bytes=$resumeAt-") }
            report("new resume from 96%: completed in ${"%.3f".format(r)} s")
            val par = timed {
                val seg = bytes / 4
                (0 until 4).map { i ->
                    Thread { download(np, "clip.mov", "bytes=${i * seg}-${if (i == 3) bytes - 1 else (i + 1) * seg - 1}") }.also { it.start() }
                }.forEach { it.join() }
            }
            report("new download ${mb}MB, 4 parallel ranges: ${rate(bytes, par)}")
            val u = timed { chunkedUpload(np, src) }
            report("new upload ${mb}MB (8 MB chunks): ${rate(bytes, u)} (${"%.2f".format(u)} s)")
            val raw = timed { RawHttp(np, 120_000).use { c ->
                val out = c.outputStream()
                out.write("PUT /api/upload/raw?name=raw.bin HTTP/1.1\r\nHost: x\r\nContent-Length: $bytes\r\n\r\n".toByteArray())
                src.inputStream().use { it.copyTo(out, 1 shl 20) }; out.flush()
                check(c.read().status == 200)
            } }
            report("new upload ${mb}MB (single PUT): ${rate(bytes, raw)}")
            report("new request latency: ${"%.2f".format(pingLatencyMs(np))} ms")
        } finally {
            server.stop()
        }
        File("build/lanbeam-bench.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
