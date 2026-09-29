package com.example.lanbeam.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The embedded HTTP server other devices talk to. Host-specific pieces (folders, device name,
 * bitmaps) come from [ServerEnv], so the whole request path runs in plain-JVM tests too.
 */
class LanBeamServer(
    private val env: ServerEnv,
    val port: Int,
    /** null = all interfaces (the app). Tests pass 127.0.0.1. */
    hostname: String? = null,
) : NanoHTTPD(hostname, port) {

    val uploads = UploadStore { env.uploadsDir() }

    private data class CachedDirInfo(val size: Long, val itemCount: Int, val timestamp: Long)
    private val dirInfoCache = ConcurrentHashMap<String, CachedDirInfo>()

    /** Starts listening. A 30 s read timeout (NanoHTTPD's default is 5 s) rides out Wi-Fi hiccups. */
    fun startServer() {
        try { uploads.cleanupStale() } catch (_: Exception) {}
        start(SOCKET_TIMEOUT_MS, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val r = if (session.method == Method.OPTIONS) {
            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } else {
            try {
                route(session)
            } catch (e: SecurityException) {
                error(session, Response.Status.FORBIDDEN, "Access denied")
            } catch (e: UploadStore.UploadException) {
                val body = if (e.currentOffset != null) """{"error":${HttpUtil.jsonStr(e.message)},"offset":${e.currentOffset}}"""
                else """{"error":${HttpUtil.jsonStr(e.message)}}"""
                error(session, statusOf(e.status), body, "application/json")
            } catch (e: IOException) {
                error(session, Response.Status.INTERNAL_ERROR, "I/O error: ${e.message}")
            } catch (e: Exception) {
                error(session, Response.Status.INTERNAL_ERROR, "Server error: ${e.message}")
            }
        }
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "*")
        r.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Disposition, Accept-Ranges, ETag")
        r.addHeader("X-Content-Type-Options", "nosniff")
        return r
    }

    /** Error for a request whose body may be unread: close the connection so it cannot desync. */
    private fun error(session: IHTTPSession, status: Response.Status, text: String, mime: String = "text/plain"): Response {
        val r = newFixedLengthResponse(status, mime, text)
        if (session.method == Method.POST || session.method == Method.PUT) r.closeConnection(true)
        return r
    }

    private fun statusOf(code: Int): Response.Status =
        Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.BAD_REQUEST

    /** Only our own page and JSON are gzipped; file bodies are sent as-is with a real length. */
    override fun useGzipWhenAccepted(r: Response): Boolean =
        r.getHeader("accept-ranges") == null && super.useGzipWhenAccepted(r)

    private fun param(session: IHTTPSession, name: String): String? = session.parameters[name]?.firstOrNull()

    private fun json(body: String, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", body).also { it.addHeader("Cache-Control", "no-store") }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val m = session.method
        return when {
            uri == "/" || uri == "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html", env.frontendHtml())
                .also { it.addHeader("Cache-Control", "no-cache") }
            uri == "/api/ping" -> json("""{"ok":true}""")
            uri == "/api/info" -> json(infoJson())
            uri == "/api/files" -> listFiles(param(session, "path") ?: "/")
            uri == "/api/download" -> handleDownload(session, attachment = true)
            uri == "/api/stream" -> handleDownload(session, attachment = false)
            uri == "/api/thumbnail" -> handleThumbnail(session)
            uri == "/api/upload/chunk" -> handleChunk(session)
            uri == "/api/upload/raw" && (m == Method.PUT || m == Method.POST) -> handleRawUpload(session)
            uri == "/api/upload" && m == Method.POST -> handleMultipartUpload(session)
            uri == "/api/qr" -> handleQr()
            uri == "/api/file" && m == Method.DELETE -> handleDelete(session)
            uri == "/api/uploads/size" -> uploadsSize()
            uri == "/api/download-zip" && m == Method.POST -> handleZipDownload(session)
            uri == "/api/devices" -> json("[]")
            uri == "/api/folders" -> json("[\"/uploads\"]")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    fun primaryUrl(): String = "http://${env.addresses().firstOrNull()?.ip ?: "127.0.0.1"}:$port"

    private fun infoJson(): String {
        val addrs = env.addresses()
        val ip = addrs.firstOrNull()?.ip ?: "127.0.0.1"
        val (count, size) = sharedStats()
        val addrJson = addrs.joinToString(",") { """{"ip":${HttpUtil.jsonStr(it.ip)},"kind":${HttpUtil.jsonStr(it.kind.label)}}""" }
        return """{"ip":${HttpUtil.jsonStr(ip)},"port":$port,"wsPort":${port + 1},""" +
            """"hostname":${HttpUtil.jsonStr(env.deviceName())},"sharedPath":${HttpUtil.jsonStr(env.sharedDir().absolutePath)},""" +
            """"url":${HttpUtil.jsonStr("http://$ip:$port")},"filesShared":$count,"totalSize":$size,""" +
            """"devicesConnected":1,"connectionType":${HttpUtil.jsonStr(addrs.firstOrNull()?.kind?.label ?: "Wi-Fi")},""" +
            """"signalStrength":4,"passwordRequired":false,"chunkUpload":true,"addresses":[$addrJson]}"""
    }

    // ─── Listing ───

    private fun listFiles(relativePath: String): Response {
        val dir = resolvePath(relativePath)
        if (!dir.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Path not found")
        if (!dir.isDirectory) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Not a directory")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val items = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty().map { f ->
            val name = HttpUtil.jsonStr(f.name)
            val modified = fmt.format(Date(f.lastModified()))
            if (f.isDirectory) {
                val info = dirInfo(f)
                """{"name":$name,"type":"folder","size":${info.size},"items":${info.itemCount},"modified":"$modified"}"""
            } else {
                """{"name":$name,"type":"${FileTypes.typeOf(f.name)}","size":${f.length()},"modified":"$modified","mtime":${f.lastModified()}}"""
            }
        }
        return json("[" + items.joinToString(",") + "]")
    }

    // ─── Downloads ───

    private fun handleDownload(session: IHTTPSession, attachment: Boolean): Response {
        val rel = param(session, "path") ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
        val file = resolvePath(rel)
        if (!file.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")

        val total = file.length()
        val mime = FileTypes.mimeOf(file.name, env::mimeFromPlatform)
        val etag = "\"${total.toString(16)}-${file.lastModified().toString(16)}\""

        // If-Range: only honour the range when the client's copy is still the same file.
        val ifRange = session.headers["if-range"]
        val rangeHeader = session.headers["range"].takeIf { ifRange == null || ifRange == etag }
        val range = HttpUtil.parseRange(rangeHeader, total)

        if (range is HttpUtil.RangeResult.Unsatisfiable) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                .also { it.addHeader("Content-Range", "bytes */$total") }
        }
        val (status, start, length) = when (range) {
            is HttpUtil.RangeResult.Partial -> Triple(Response.Status.PARTIAL_CONTENT, range.range.start, range.range.length)
            else -> Triple(Response.Status.OK, 0L, total)
        }

        val response: Response = if (session.method == Method.HEAD) {
            HeadResponse(status, mime, length)
        } else {
            var body: InputStream = FileRangeInputStream(file, start, length)
            if (attachment) {
                val key = "dl:${session.remoteIpAddress}:${file.absolutePath}"
                val handle = TransferTracker.acquire(key, file.name, TransferTracker.Direction.DOWNLOAD, session.remoteIpAddress ?: "", total, start)
                body = TrackedInputStream(body, handle) { TransferTracker.release(key) }
            }
            // Length is passed ONLY here. Adding a Content-Length header as well made NanoHTTPD
            // advertise the full file size on 206 responses, so every resumed or ranged download
            // (iOS Safari, DownloadManager retries, video players) waited forever near the end.
            newFixedLengthResponse(status, mime, body, length)
        }
        if (status == Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes $start-${start + length - 1}/$total")
        }
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("ETag", etag)
        response.addHeader("Last-Modified", httpDate(file.lastModified()))
        response.addHeader("Content-Disposition", HttpUtil.contentDisposition(file.name, attachment))
        return response
    }

    /** HEAD answer: advertises [advertised] bytes without sending any (NanoHTTPD would send the body). */
    private class HeadResponse(status: Response.Status, mime: String, private val advertised: Long) :
        Response(status, mime, ByteArrayInputStream(ByteArray(0)), 0) {
        override fun sendContentLengthHeaderIfNotAlreadyPresent(pw: PrintWriter, defaultSize: Long): Long {
            pw.print("Content-Length: $advertised\r\n")
            return 0
        }
    }

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    // ─── Uploads ───

    private fun contentLength(session: IHTTPSession): Long =
        session.headers["content-length"]?.trim()?.toLongOrNull()
            ?: throw UploadStore.UploadException(411, "Content-Length required")

    private fun handleChunk(session: IHTTPSession): Response {
        val id = param(session, "id") ?: throw UploadStore.UploadException(400, "Missing id")
        return when (session.method) {
            Method.GET, Method.HEAD -> json("""{"offset":${uploads.currentOffset(id)}}""")
            Method.DELETE -> {
                uploads.cancel(id)
                json("""{"ok":true}""")
            }
            Method.POST, Method.PUT -> {
                val name = HttpUtil.sanitizeFileName(param(session, "name"))
                val offset = param(session, "offset")?.toLongOrNull() ?: throw UploadStore.UploadException(400, "Missing offset")
                val total = param(session, "total")?.toLongOrNull() ?: throw UploadStore.UploadException(400, "Missing total")
                val len = contentLength(session)
                receive(session, id, name, offset, total, len)
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
        }
    }

    /** One-shot streaming upload for scripts: `curl -T big.mov "http://ip:8765/api/upload/raw?name=big.mov"`. */
    private fun handleRawUpload(session: IHTTPSession): Response {
        val len = contentLength(session)
        val name = HttpUtil.sanitizeFileName(param(session, "name") ?: param(session, "file"))
        val id = "raw" + System.nanoTime().toString(36) + (Math.random() * 1e9).toLong().toString(36)
        return receive(session, id, name, 0, len, len)
    }

    private fun receive(session: IHTTPSession, id: String, name: String, offset: Long, total: Long, len: Long): Response {
        val key = "up:$id"
        val handle = TransferTracker.acquire(key, name, TransferTracker.Direction.UPLOAD, session.remoteIpAddress ?: "", total, offset)
        val result = try {
            uploads.writeChunk(id, name, offset, total, len, session.inputStream) { handle.add(it.toLong()) }
        } finally {
            TransferTracker.release(key)
        }
        if (result.done && result.file != null) {
            dirInfoCache.clear()
            env.onUploadComplete(result.file.name)
            env.onFilesChanged()
            return json("""{"ok":true,"offset":${result.offset},"done":true,"filename":${HttpUtil.jsonStr(result.file.name)},"size":${result.file.length()}}""")
        }
        return json("""{"ok":true,"offset":${result.offset},"done":false}""")
    }

    /**
     * Legacy multipart endpoint (kept for older pages and scripts). NanoHTTPD buffers the whole
     * body to a temp file first, so the web UI no longer uses it.
     */
    private fun handleMultipartUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tmpPath = files["file"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
        val name = HttpUtil.sanitizeFileName(param(session, "file") ?: "uploaded_file")
        val dest = FileNames.unique(env.uploadsDir(), name)
        val tmp = File(tmpPath)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = false)
            tmp.delete()
        }
        dirInfoCache.clear()
        env.onUploadComplete(dest.name)
        env.onFilesChanged()
        return json("""{"ok":true,"filename":${HttpUtil.jsonStr(dest.name)},"size":${dest.length()}}""")
    }

    // ─── Misc endpoints ───

    private fun handleThumbnail(session: IHTTPSession): Response {
        val rel = param(session, "path") ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
        val file = resolvePath(rel)
        if (!file.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        if (FileTypes.typeOf(file.name) != "image") return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Not an image file")
        val size = (param(session, "size")?.toIntOrNull() ?: 200).coerceIn(32, 1024)
        val bytes = env.thumbnail(file, size)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot decode image")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
            .also { it.addHeader("Cache-Control", "public, max-age=300") }
    }

    private fun handleQr(): Response {
        val png = env.qrPng(primaryUrl()) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(png), png.size.toLong())
            .also { it.addHeader("Cache-Control", "no-cache") }
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val rel = param(session, "path") ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
        val target = resolvePath(rel)
        val uploadsRoot = env.uploadsDir().canonicalPath
        val canonical = target.canonicalPath
        if (canonical == uploadsRoot || !canonical.startsWith(uploadsRoot + File.separator)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Can only delete files from uploads/")
        }
        if (!target.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        val name = target.name
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!ok) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Delete failed")
        dirInfoCache.clear()
        env.onFileDeleted(name)
        env.onFilesChanged()
        return json("""{"ok":true,"deleted":${HttpUtil.jsonStr(name)}}""")
    }

    private fun uploadsSize(): Response {
        var count = 0
        var total = 0L
        env.uploadsDir().walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { count++; total += it.length() }
        return json("""{"count":$count,"totalSize":$total}""")
    }

    private fun handleZipDownload(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = param(session, "files_json") ?: body["postData"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing request body")
        val paths = JsonLite.stringArray(raw, "files").ifEmpty { JsonLite.bareStringArray(raw) }
        if (paths.isEmpty()) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files specified")

        val files = paths.mapNotNull { p -> runCatching { resolvePath(p) }.getOrNull()?.takeIf { it.isFile } }
        if (files.isEmpty()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No valid files found")

        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 1024 * 1024)
        val handle = TransferTracker.begin("${files.size} files (ZIP)", TransferTracker.Direction.DOWNLOAD, session.remoteIpAddress ?: "", -1, 0)
        Thread({
            try {
                ZipOutputStream(pipeOut).use { zos ->
                    // Store-speed deflate: media dominates and is already compressed; CPU is the
                    // bottleneck on a phone, not the LAN.
                    zos.setLevel(Deflater.NO_COMPRESSION)
                    val used = HashSet<String>()
                    val buf = ByteArray(256 * 1024)
                    for (f in files) {
                        var entry = f.name
                        var i = 1
                        while (!used.add(entry)) {
                            entry = f.nameWithoutExtension + " (${i++})" + (if (f.extension.isNotEmpty()) ".${f.extension}" else "")
                        }
                        zos.putNextEntry(ZipEntry(entry).apply { time = f.lastModified() })
                        FileInputStream(f).use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                zos.write(buf, 0, n)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            } catch (_: IOException) {
                // Client went away; the pipe reader is closed.
            } finally {
                try { pipeOut.close() } catch (_: IOException) {}
            }
        }, "lanbeam-zip").start()

        val tracked = TrackedInputStream(pipeIn, handle) { handle.end(ok = true) }
        return newChunkedResponse(Response.Status.OK, "application/zip", tracked).also {
            it.addHeader("Content-Disposition", HttpUtil.contentDisposition("LAN_Beam_files.zip", true))
        }
    }

    // ─── Paths & stats ───

    /** Maps `/…` to the Shared folder and `uploads/…` to Uploads, refusing anything outside them. */
    fun resolvePath(relativePath: String): File {
        val clean = relativePath.replace('\\', '/').trimStart('/')
        val shared = env.sharedDir()
        val uploadsDir = env.uploadsDir()
        val target = when {
            clean == "uploads" -> uploadsDir
            clean.startsWith("uploads/") -> File(uploadsDir, clean.removePrefix("uploads/"))
            clean.isEmpty() -> shared
            else -> File(shared, clean)
        }
        val t = target.canonicalPath
        fun inside(root: File): Boolean {
            val r = root.canonicalPath
            return t == r || t.startsWith(r + File.separator)
        }
        if (!inside(shared) && !inside(uploadsDir)) throw SecurityException("Path traversal")
        return target
    }

    private fun sharedStats(): Pair<Int, Long> {
        var count = 0
        var size = 0L
        env.sharedDir().listFiles()?.filter { !it.name.startsWith(".") }?.forEach {
            count++
            size += if (it.isFile) it.length() else dirInfo(it).size
        }
        return count to size
    }

    private fun dirInfo(dir: File): CachedDirInfo {
        val now = System.currentTimeMillis()
        dirInfoCache[dir.absolutePath]?.let { if (now - it.timestamp < 10_000) return it }
        var size = 0L
        var count = 0
        dir.listFiles()?.filter { !it.name.startsWith(".") }?.forEach { count++; if (it.isFile) size += it.length() }
        return CachedDirInfo(size, count, now).also { dirInfoCache[dir.absolutePath] = it }
    }

    companion object {
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}

/** Reads [length] bytes of [file] starting at [start]. */
class FileRangeInputStream(file: File, start: Long, private val length: Long) : InputStream() {
    private val input = FileInputStream(file).also { if (start > 0) it.channel.position(start) }
    private var remaining = length

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = input.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    override fun close() = input.close()
}

/** Minimal parsing of the ZIP request body, so the server needs no JSON library. */
object JsonLite {
    private val STRING = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")

    fun stringArray(json: String, key: String): List<String> {
        val k = json.indexOf("\"$key\"")
        if (k < 0) return emptyList()
        val open = json.indexOf('[', k)
        val close = json.indexOf(']', open)
        if (open < 0 || close < 0) return emptyList()
        return STRING.findAll(json.substring(open + 1, close)).map { unescape(it.groupValues[1]) }.toList()
    }

    fun bareStringArray(json: String): List<String> {
        val t = json.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) return emptyList()
        return STRING.findAll(t.substring(1, t.length - 1)).map { unescape(it.groupValues[1]) }.toList()
    }
}
