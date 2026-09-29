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

    @Test fun parallelChunksInAnyOrderAssemble() {
        val cs = 64 * 1024L
        val data = Random(5).nextBytes((cs * 5 + 1234).toInt())
        val s = store()
        val order = listOf(3, 0, 5, 1, 4, 2)
        var result: UploadStore.ChunkResult? = null
        val threads = order.map { i ->
            Thread {
                val start = (i * cs).toInt()
                val len = minOf(cs.toInt(), data.size - start)
                val r = s.writeIndexedChunk(id, "par.bin", data.size.toLong(), cs, i, len.toLong(), ByteArrayInputStream(data, start, len))
                if (r.done) synchronized(this) { result = r }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertArrayEquals(data, result!!.file!!.readBytes())
        assertFalse(s.indexFile(id).exists())
    }

    @Test fun parallelResumeKnowsWhichChunksAreStored() {
        val cs = 64 * 1024L
        val data = Random(6).nextBytes((cs * 3).toInt())
        store().writeIndexedChunk(id, "r.bin", data.size.toLong(), cs, 1, cs, ByteArrayInputStream(data, cs.toInt(), cs.toInt()))
        // New store instance = app restarted: the .idx file carries the state.
        val s2 = store()
        assertEquals(setOf(1), s2.doneChunks(id))
        s2.writeIndexedChunk(id, "r.bin", data.size.toLong(), cs, 0, cs, ByteArrayInputStream(data, 0, cs.toInt()))
        val r = s2.writeIndexedChunk(id, "r.bin", data.size.toLong(), cs, 2, cs, ByteArrayInputStream(data, (2 * cs).toInt(), cs.toInt()))
        assertTrue(r.done)
        assertArrayEquals(data, r.file!!.readBytes())
    }

    @Test fun parallelRejectsWrongChunkLength() {
        try {
            store().writeIndexedChunk(id, "x", 200_000, 65_536, 0, 10, ByteArrayInputStream(ByteArray(10)))
            fail()
        } catch (e: UploadStore.UploadException) { assertEquals(400, e.status) }
    }
}
