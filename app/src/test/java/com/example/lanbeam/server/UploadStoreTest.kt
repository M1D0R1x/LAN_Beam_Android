package com.example.lanbeam.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

class UploadStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = UploadStore { tmp.root }
    private val id = "testupload01"

    @Test fun chunksAssembleIntoOneFile() {
        val data = Random(1).nextBytes(10_000)
        val s = store()
        var off = 0L
        while (off < data.size) {
            val len = minOf(3000, data.size - off.toInt())
            val r = s.writeChunk(id, "clip.mov", off, data.size.toLong(), len.toLong(), ByteArrayInputStream(data, off.toInt(), len))
            off = r.offset
            if (r.done) {
                assertArrayEquals(data, r.file!!.readBytes())
                assertEquals("clip.mov", r.file!!.name)
            }
        }
        assertFalse(s.partFile(id).exists())
    }

    @Test fun offsetMismatchReportsServerOffset() {
        val s = store()
        s.writeChunk(id, "a.bin", 0, 100, 40, ByteArrayInputStream(ByteArray(40)))
        try {
            s.writeChunk(id, "a.bin", 80, 100, 20, ByteArrayInputStream(ByteArray(20)))
            fail()
        } catch (e: UploadStore.UploadException) {
            assertEquals(409, e.status); assertEquals(40L, e.currentOffset)
        }
    }

    @Test fun droppedConnectionKeepsBytesAndResumes() {
        val data = Random(2).nextBytes(1000)
        val s = store()
        // Client claims 1000 bytes but the connection dies after 600.
        val dying = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < 600) data[i++].toInt() and 0xff else throw IOException("reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= 600) throw IOException("reset")
                val n = minOf(len, 600 - i); System.arraycopy(data, i, b, off, n); i += n; return n
            }
        }
        try { s.writeChunk(id, "x.bin", 0, 1000, 1000, dying); fail() } catch (_: IOException) {}
        assertEquals(600L, s.currentOffset(id))
        val r = s.writeChunk(id, "x.bin", 600, 1000, 400, ByteArrayInputStream(data, 600, 400))
        assertTrue(r.done)
        assertArrayEquals(data, r.file!!.readBytes())
    }

    @Test fun neverOverwritesExistingFile() {
        tmp.newFile("photo.jpg").writeText("original")
        val r = store().writeChunk(id, "photo.jpg", 0, 3, 3, ByteArrayInputStream("new".toByteArray()))
        assertEquals("photo (1).jpg", r.file!!.name)
        assertEquals("original", tmp.root.resolve("photo.jpg").readText())
    }

    @Test fun zeroByteFile() {
        val r = store().writeChunk(id, "empty.txt", 0, 0, 0, ByteArrayInputStream(ByteArray(0)))
        assertTrue(r.done); assertEquals(0L, r.file!!.length())
    }

    @Test fun rejectsBadIdsAndOverflow() {
        val s = store()
        try { s.currentOffset("../../x"); fail() } catch (e: UploadStore.UploadException) { assertEquals(400, e.status) }
        try { s.writeChunk(id, "a", 90, 100, 20, ByteArrayInputStream(ByteArray(20))); fail() } catch (e: UploadStore.UploadException) { assertEquals(400, e.status) }
    }

    @Test fun cancelRemovesPart() {
        val s = store()
        s.writeChunk(id, "a", 0, 100, 10, ByteArrayInputStream(ByteArray(10)))
        assertTrue(s.cancel(id))
        assertEquals(0L, s.currentOffset(id))
    }
}
