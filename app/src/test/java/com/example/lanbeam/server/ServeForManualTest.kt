package com.example.lanbeam.server

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: runs the real server with the real web page on 127.0.0.1:18765 for N seconds so the
 * browser client can be exercised end to end (scripts/fe-harness.mjs, or a desktop browser).
 *   ./gradlew testDebugUnitTest --tests '*ServeForManualTest*' -Dlanbeam.serve=120
 */
class ServeForManualTest {
    @Test fun serve() {
        val secs = System.getProperty("lanbeam.serve")?.toIntOrNull() ?: 0
        assumeTrue(secs > 0)
        val root = File(System.getProperty("lanbeam.serveRoot") ?: "build/serve").apply { deleteRecursively(); mkdirs() }
        val html = File("src/main/assets/frontend.html").readText()
        val env = object : ServerEnv by TestEnv(root) {
            override fun frontendHtml() = html
        }
        File(env.sharedDir(), "hello.txt").writeText("hello from the phone\n")
        val server = LanBeamServer(env, 18765, "127.0.0.1").also { it.startServer() }
        println("SERVING http://127.0.0.1:18765 root=${root.absolutePath}")
        Thread.sleep(secs * 1000L)
        server.stop()
    }
}
