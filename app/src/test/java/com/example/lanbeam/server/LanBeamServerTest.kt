package com.example.lanbeam.server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.net.URLEncoder
import kotlin.random.Random

class LanBeamServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: TestEnv
    private lateinit var server: LanBeamServer
    private var port = 0
    private val data = Random(7).nextBytes(3 * 1024 * 1024 + 123)

    @Before fun setUp() {
        env = TestEnv(tmp.root)
        env.shared.resolve("clip.mov").writeBytes(data)
        port = ServerSocket(0).use { it.localPort }
        server = LanBeamServer(env, port, "127.0.0.1").also { it.startServer() }
    }

    @After fun tearDown() = server.stop()

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    @Test fun fullDownloadHasOneCorrectContentLength() = RawHttp(port).use { c ->
        val r = c.request("GET", "/api/download?path=clip.mov")
        assertEquals(200, r.status)
        assertEquals(1, r.headerCount("Content-Length"))
        assertEquals(data.size.toString(), r.header("Content-Length"))
        assertArrayEquals(data, r.body)
        assertEquals("bytes", r.header("Accept-Ranges"))
    }

    /** The ~96% stall: a resumed download must get exactly the remaining bytes, then complete. */
    @Test fun resumedRangeDownloadCompletes() = RawHttp(port, timeoutMs = 3000).use { c ->
        val start = (data.size * 0.96).toInt()
        val r = c.request("GET", "/api/download?path=clip.mov", mapOf("Range" to "bytes=$start-"))
        assertEquals(206, r.status)
        assertEquals(1, r.headerCount("Content-Length"))
        assertEquals((data.size - start).toString(), r.header("Content-Length"))
        assertEquals("bytes $start-${data.size - 1}/${data.size}", r.header("Content-Range"))
        assertArrayEquals(data.copyOfRange(start, data.size), r.body)
        // Keep-alive connection is still in sync for the next request.
        assertEquals(200, c.request("GET", "/api/ping").status)
    }

    @Test fun parallelSegmentsReassemble() {
        val parts = 4
        val seg = data.size / parts
        val out = ByteArray(data.size)
        (0 until parts).map { i ->
            Thread {
                RawHttp(port).use { c ->
                    val s = i * seg
                    val e = if (i == parts - 1) data.size - 1 else s + seg - 1
                    val r = c.request("GET", "/api/download?path=clip.mov", mapOf("Range" to "bytes=$s-$e"))
                    System.arraycopy(r.body, 0, out, s, r.body.size)
                }
            }.also { it.start() }
        }.forEach { it.join() }
        assertArrayEquals(data, out)
    }

    @Test fun suffixAndUnsatisfiableRanges() = RawHttp(port).use { c ->
        val r = c.request("GET", "/api/download?path=clip.mov", mapOf("Range" to "bytes=-100"))
        assertEquals(206, r.status); assertArrayEquals(data.copyOfRange(data.size - 100, data.size), r.body)
        val bad = c.request("GET", "/api/download?path=clip.mov", mapOf("Range" to "bytes=${data.size}-"))
        assertEquals(416, bad.status); assertEquals("bytes */${data.size}", bad.header("Content-Range"))
    }

    @Test fun staleIfRangeGetsWholeFile() = RawHttp(port).use { c ->
        val r = c.request("GET", "/api/download?path=clip.mov", mapOf("Range" to "bytes=10-", "If-Range" to "\"old\""))
        assertEquals(200, r.status); assertEquals(data.size, r.body.size)
    }

    @Test fun headSendsNoBodyAndKeepsConnectionUsable() = RawHttp(port).use { c ->
        val h = c.request("HEAD", "/api/download?path=clip.mov")
        assertEquals(200, h.status)
        assertEquals(data.size.toString(), h.header("Content-Length"))
        assertEquals(1, h.headerCount("Content-Length"))
        val p = c.request("GET", "/api/ping")
        assertEquals(200, p.status)
        assertTrue(String(p.body).contains("ok"))
    }

    @Test fun chunkedUploadWithInterruptionResumes() {
        val payload = Random(9).nextBytes(20 * 1024 * 1024 + 7)
        val id = "itest00000001"
        val chunk = 8 * 1024 * 1024
        val name = q("My \"trip\".mov")
        // First chunk dies half-way (client promised 8 MB, sent 3 MB, then the socket dropped).
        RawHttp(port).use { c ->
            val header = "POST /api/upload/chunk?id=$id&name=$name&offset=0&total=${payload.size} HTTP/1.1\r\n" +
                "Host: x\r\nContent-Length: $chunk\r\n\r\n"
            c.outputStream().write(header.toByteArray())
            c.outputStream().write(payload, 0, 3 * 1024 * 1024)
            c.outputStream().flush()
        }
        Thread.sleep(300)
        var offset = RawHttp(port).use { c -> Regex("\"offset\":(\\d+)").find(String(c.request("GET", "/api/upload/chunk?id=$id").body))!!.groupValues[1].toLong() }
        assertEquals(3L * 1024 * 1024, offset)

        var done = false
        RawHttp(port).use { c ->
            while (!done) {
                val end = minOf(offset + chunk, payload.size.toLong()).toInt()
                val r = c.request("POST", "/api/upload/chunk?id=$id&name=$name&offset=$offset&total=${payload.size}", body = payload.copyOfRange(offset.toInt(), end))
                assertEquals(200, r.status)
                val body = String(r.body)
                offset = Regex("\"offset\":(\\d+)").find(body)!!.groupValues[1].toLong()
                done = body.contains("\"done\":true")
            }
        }
        val saved = env.uploads.resolve("My trip.mov")
        assertTrue(saved.exists())
        assertArrayEquals(payload, saved.readBytes())
        assertTrue(env.uploads.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun wrongOffsetIs409WithCurrentOffset() = RawHttp(port).use { c ->
        val r = c.request("POST", "/api/upload/chunk?id=itest00000002&name=a&offset=5&total=10", body = ByteArray(5))
        assertEquals(409, r.status)
        assertTrue(String(r.body).contains("\"offset\":0"))
    }

    @Test fun speedTestStreamsAndSinks() = RawHttp(port).use { c ->
        val d = c.request("GET", "/api/speedtest?bytes=1000000")
        assertEquals(200, d.status); assertEquals(1_000_000, d.body.size)
        val u = c.request("POST", "/api/speedtest", body = ByteArray(500_000))
        assertTrue(String(u.body).contains("\"received\":500000"))
    }

    @Test fun rawPutUpload() = RawHttp(port).use { c ->
        val r = c.request("PUT", "/api/upload/raw?name=raw.bin", body = data)
        assertEquals(200, r.status)
        assertArrayEquals(data, env.uploads.resolve("raw.bin").readBytes())
    }

    @Test fun traversalIsForbidden() = RawHttp(port).use { c ->
        assertEquals(403, c.request("GET", "/api/download?path=${q("../../etc/passwd")}").status)
        assertEquals(403, c.request("GET", "/api/files?path=${q("uploads/../../")}").status)
    }

    @Test fun deleteOnlyInsideUploads() = RawHttp(port).use { c ->
        env.uploads.resolve("r.txt").writeText("x")
        assertEquals(403, c.request("DELETE", "/api/file?path=clip.mov").status)
        assertEquals(403, c.request("DELETE", "/api/file?path=uploads").status)
        assertEquals(200, c.request("DELETE", "/api/file?path=${q("uploads/r.txt")}").status)
    }

    @Test fun listingIsValidJsonForHostileNames() = RawHttp(port).use { c ->
        env.shared.resolve("quote\"back\\slash\tname.txt").writeText("x")
        val body = String(c.request("GET", "/api/files?path=%2F").body)
        assertTrue(body, body.contains("\"quote\\\"back\\\\slash\\tname.txt\""))
        assertTrue(!body.contains(".lanbeam-"))
    }

    @Test fun zipDownloadStreams() = RawHttp(port).use { c ->
        env.shared.resolve("b.txt").writeText("hello")
        val form = "files_json=" + q("[\"clip.mov\",\"b.txt\"]")
        val r = c.request("POST", "/api/download-zip", mapOf("Content-Type" to "application/x-www-form-urlencoded"), form.toByteArray())
        assertEquals(200, r.status)
        val names = java.util.zip.ZipInputStream(r.body.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("clip.mov", "b.txt"), names)
    }
}
