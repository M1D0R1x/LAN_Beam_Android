package com.example.lanbeam.legacy

import com.example.lanbeam.server.RawHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * (Uses .mkv because v2.1 resolves .mov through android.webkit.MimeTypeMap, a stub on the JVM.)
 * Reproduces, against the unmodified v2.1 server, the defects this rework fixes. If one of these
 * starts failing, the legacy copy no longer matches what shipped.
 */
class LegacyBugReproTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: LegacyLanBeamServer
    private var port = 0
    private val data = Random(3).nextBytes(2 * 1024 * 1024)

    @Before fun setUp() {
        val ctx = LegacyTestContext(tmp.root)
        java.io.File(tmp.root, "Shared").apply { mkdirs() }.resolve("clip.mkv").writeBytes(data)
        port = ServerSocket(0).use { it.localPort }
        server = LegacyLanBeamServer(ctx, port).also { it.start() }
    }

    @After fun tearDown() = server.stop()

    /** Root cause of "stalls at ~96%": a resumed (Range) download advertises the FULL size. */
    @Test fun rangeResponseAdvertisesFullLengthAndHangs() = RawHttp(port, timeoutMs = 2000).use { c ->
        val start = (data.size * 0.96).toInt()
        c.send("GET", "/api/download?path=clip.mkv", mapOf("Range" to "bytes=$start-"))
        var timedOut = false
        try {
            c.read()
        } catch (e: SocketTimeoutException) {
            timedOut = true
        }
        assertTrue("legacy server should leave the client waiting for bytes that never come", timedOut)
    }

    @Test fun fullDownloadSendsDuplicateContentLength() = RawHttp(port).use { c ->
        val r = c.request("GET", "/api/download?path=clip.mkv")
        assertEquals(2, r.headerCount("Content-Length"))
    }

    /** HEAD (used by download managers to probe) gets the whole body, desyncing keep-alive. */
    @Test fun headSendsBodyAndCorruptsNextResponse() = RawHttp(port, timeoutMs = 2000).use { c ->
        c.request("HEAD", "/api/download?path=clip.mkv")
        val next = runCatching { c.request("GET", "/api/folders") }
        // The next "response" starts inside the file bytes: parse fails or the status is garbage.
        assertTrue(next.isFailure || next.getOrNull()?.status != 200)
    }
}
