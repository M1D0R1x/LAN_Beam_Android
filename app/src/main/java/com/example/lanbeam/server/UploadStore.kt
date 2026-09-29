package com.example.lanbeam.server

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Resumable, chunked uploads written straight into the destination folder.
 *
 * Protocol (one request per chunk, any size, strictly sequential per upload id):
 *   POST /api/upload/chunk?id=ID&name=NAME&offset=N&total=T   body = raw bytes
 *     -> 200 {"offset":N2,"done":false}  or  {"offset":T,"done":true,"filename":"…"}
 *     -> 409 {"offset":CUR}  when N != bytes already stored (client resumes from CUR)
 *   GET  /api/upload/chunk?id=ID                               -> {"offset":CUR}
 *   DELETE /api/upload/chunk?id=ID                             -> cancel, part removed
 *
 * Bytes go to `<dir>/.lanbeam-<id>.part` (hidden from listings) and are renamed into place when
 * complete, so a file is written to storage exactly once and never appears half-written.
 * Whatever reached the disk before a dropped connection is kept, so a retry resumes mid-chunk.
 */
class UploadStore(private val dirProvider: () -> File) {

    class UploadException(val status: Int, message: String, val currentOffset: Long? = null) : Exception(message)

    data class ChunkResult(val offset: Long, val done: Boolean, val file: File?)

    private val locks = ConcurrentHashMap<String, Any>()
    private val totals = ConcurrentHashMap<String, Long>()

    fun partFile(id: String): File = File(dirProvider(), ".lanbeam-$id.part")

    fun currentOffset(id: String): Long {
        requireValidId(id)
        val f = partFile(id)
        return if (f.exists()) f.length() else 0L
    }

    /**
     * Appends [length] bytes from [body] at [offset]. [onBytes] is called as bytes land on disk
     * (used for live progress). Throws [UploadException] on protocol errors.
     */
    fun writeChunk(
        id: String,
        rawName: String?,
        offset: Long,
        total: Long,
        length: Long,
        body: InputStream,
        onBytes: (Int) -> Unit = {},
    ): ChunkResult {
        requireValidId(id)
        if (total < 0 || offset < 0 || length < 0) throw UploadException(400, "Bad offset/total/length")
        if (offset + length > total) throw UploadException(400, "Chunk exceeds declared total")
        val lock = locks.computeIfAbsent(id) { Any() }
        synchronized(lock) {
            val known = totals.putIfAbsent(id, total)
            if (known != null && known != total) throw UploadException(409, "Total changed", currentOffset(id))

            val part = partFile(id)
            part.parentFile?.mkdirs()
            val cur = if (part.exists()) part.length() else 0L
            if (cur != offset) throw UploadException(409, "Offset mismatch", cur)

            if (length > 0) {
                FileOutputStream(part, true).use { out ->
                    val buf = ByteArray(BUFFER)
                    var remaining = length
                    while (remaining > 0) {
                        val n = body.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("Client closed the connection mid-chunk")
                        out.write(buf, 0, n)
                        remaining -= n
                        onBytes(n)
                    }
                }
            } else if (!part.exists()) {
                part.createNewFile()
            }

            val size = part.length()
            if (size < total) return ChunkResult(size, false, null)

            val dest = FileNames.unique(dirProvider(), HttpUtil.sanitizeFileName(rawName))
            if (!part.renameTo(dest)) {
                // Different filesystem or a racing name: fall back to copy.
                part.copyTo(dest, overwrite = false)
                part.delete()
            }
            totals.remove(id)
            locks.remove(id)
            return ChunkResult(total, true, dest)
        }
    }

    fun cancel(id: String): Boolean {
        requireValidId(id)
        totals.remove(id)
        locks.remove(id)
        indexed.remove(id)
        indexFile(id).delete()
        return partFile(id).delete()
    }

    // ─── Parallel mode: fixed-size chunks written at their own offsets, in any order ───
    //
    // A single TCP stream on a busy or 2.4 GHz link rarely fills it; browsers allow ~6
    // connections per host, so the page sends 3-4 chunks of one file at once. Each chunk lands
    // at index * chunkSize; finished indexes are appended to a small .idx file so a reload or
    // app restart resumes with only the missing chunks.

    private class Indexed(val total: Long, val chunkSize: Long, val done: MutableSet<Int>) {
        val count: Int get() = if (total == 0L) 1 else ((total + chunkSize - 1) / chunkSize).toInt()
    }

    private val indexed = ConcurrentHashMap<String, Indexed>()

    fun indexFile(id: String): File = File(dirProvider(), ".lanbeam-$id.idx")

    /** Chunk indexes already stored for [id] (parallel mode), or null if none recorded. */
    fun doneChunks(id: String): Set<Int>? {
        requireValidId(id)
        indexed[id]?.let { st -> synchronized(st) { return st.done.toSet() } }
        return loadIndex(id)?.done?.toSet()
    }

    private fun loadIndex(id: String): Indexed? {
        val f = indexFile(id)
        if (!f.exists()) return null
        val lines = f.readLines()
        val header = lines.firstOrNull()?.split(' ') ?: return null
        val total = header.getOrNull(0)?.toLongOrNull() ?: return null
        val size = header.getOrNull(1)?.toLongOrNull() ?: return null
        val done = lines.drop(1).mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
        return Indexed(total, size, done)
    }

    private fun state(id: String, total: Long, chunkSize: Long): Indexed {
        val st = indexed.computeIfAbsent(id) {
            loadIndex(id)?.takeIf { it.total == total && it.chunkSize == chunkSize }
                ?: Indexed(total, chunkSize, HashSet()).also {
                    partFile(id).delete()
                    indexFile(id).apply { parentFile?.mkdirs(); writeText("$total $chunkSize\n") }
                }
        }
        if (st.total != total || st.chunkSize != chunkSize) throw UploadException(409, "Upload parameters changed")
        return st
    }

    fun writeIndexedChunk(
        id: String,
        rawName: String?,
        total: Long,
        chunkSize: Long,
        index: Int,
        length: Long,
        body: InputStream,
        onBytes: (Int) -> Unit = {},
    ): ChunkResult {
        requireValidId(id)
        if (total < 0 || chunkSize < 64 * 1024 || chunkSize > 64L * 1024 * 1024) throw UploadException(400, "Bad chunk size")
        val st = state(id, total, chunkSize)
        if (index < 0 || index >= st.count) throw UploadException(400, "Bad chunk index")
        val start = index * chunkSize
        val expected = minOf(chunkSize, total - start).coerceAtLeast(0)
        if (length != expected) throw UploadException(400, "Chunk $index must be $expected bytes")

        val part = partFile(id)
        if (!part.exists()) part.createNewFile()
        if (expected > 0) {
            java.io.RandomAccessFile(part, "rw").use { raf ->
                val ch = raf.channel
                val buf = ByteArray(BUFFER)
                var pos = start
                var remaining = expected
                while (remaining > 0) {
                    val n = body.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Client closed the connection mid-chunk")
                    val bb = java.nio.ByteBuffer.wrap(buf, 0, n)
                    while (bb.hasRemaining()) pos += ch.write(bb, pos)
                    remaining -= n
                    onBytes(n)
                }
            }
        }

        val finished = synchronized(st) {
            if (st.done.add(index)) indexFile(id).appendText("$index\n")
            st.done.size >= st.count
        }
        if (!finished) return ChunkResult(synchronized(st) { st.done.size.toLong() } * chunkSize, false, null)

        val lock = locks.computeIfAbsent(id) { Any() }
        synchronized(lock) {
            if (!part.exists()) throw UploadException(409, "Already completed")
            val dest = FileNames.unique(dirProvider(), HttpUtil.sanitizeFileName(rawName))
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = false)
                part.delete()
            }
            indexed.remove(id)
            indexFile(id).delete()
            locks.remove(id)
            return ChunkResult(total, true, dest)
        }
    }

    /** Removes partial uploads nobody resumed within [maxAgeMs]. */
    fun cleanupStale(maxAgeMs: Long = 24L * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        dirProvider().listFiles { f -> f.name.startsWith(".lanbeam-") && (f.name.endsWith(".part") || f.name.endsWith(".idx")) }
            ?.forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
    }

    private fun requireValidId(id: String) {
        if (!ID_RE.matches(id)) throw UploadException(400, "Invalid upload id")
    }

    companion object {
        private val ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
        const val BUFFER = 256 * 1024
    }
}

object FileNames {
    /** `name`, or `name (1).ext`, `name (2).ext`… — never overwrites an existing file. */
    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }
}
