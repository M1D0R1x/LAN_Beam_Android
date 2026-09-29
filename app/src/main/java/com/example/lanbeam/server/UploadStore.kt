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
        return partFile(id).delete()
    }

    /** Removes partial uploads nobody resumed within [maxAgeMs]. */
    fun cleanupStale(maxAgeMs: Long = 24L * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        dirProvider().listFiles { f -> f.name.startsWith(".lanbeam-") && f.name.endsWith(".part") }
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
