package com.example.lanbeam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LanBeamServer(private val context: Context, val port: Int) : NanoHTTPD(port) {

    // Event hooks for WebSocket broadcasting
    var onFileChanged: (() -> Unit)? = null
    var onUploadComplete: ((String) -> Unit)? = null
    var onFileDeleted: ((String) -> Unit)? = null

    private val htmlContent: String by lazy {
        try {
            context.assets.open("frontend.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Error loading frontend</h1><p>${e.message}</p></body></html>"
        }
    }

    private data class CachedDirInfo(val size: Long, val itemCount: Int, val timestamp: Long)
    private val dirInfoCache = ConcurrentHashMap<String, CachedDirInfo>()
    private val DIR_CACHE_TTL_MS = 10_000L // 10 seconds

    // Thumbnail cache directory
    private val thumbCacheDir: File by lazy {
        File(context.cacheDir, "thumbnails").also { if (!it.exists()) it.mkdirs() }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            val r = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(r)
            return r
        }

        val r = try {
            handleRequest(session)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error: ${e.message}")
        }
        addCorsHeaders(r)
        return r
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "*")
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Disposition")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" -> {
                val resp = newFixedLengthResponse(Response.Status.OK, "text/html", htmlContent)
                resp.addHeader("Cache-Control", "no-cache")
                resp
            }
            uri == "/api/info" -> {
                val ip = getLocalIpAddress()
                val (count, size) = getSharedStats()
                val deviceName = getDeviceName()
                val json = """
                    {
                        "ip": "$ip",
                        "port": $port,
                        "wsPort": ${port + 1},
                        "hostname": "$deviceName",
                        "sharedPath": "${getSharedDir().absolutePath}",
                        "url": "http://$ip:$port",
                        "filesShared": $count,
                        "totalSize": $size,
                        "devicesConnected": 1,
                        "connectionType": "Wi-Fi",
                        "signalStrength": 4,
                        "passwordRequired": false
                    }
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            uri == "/api/files" -> {
                val pathParams = session.parameters["path"]
                val relativePath = pathParams?.firstOrNull() ?: "/"
                val targetDir = resolvePath(relativePath)

                if (!targetDir.exists()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Path not found")
                }
                if (!targetDir.isDirectory) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Not a directory")
                }

                val filesList = targetDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
                val jsonItems = filesList.map { file ->
                    val nameEscaped = file.name.replace("\\", "\\\\").replace("\"", "\\\"")
                    val modified = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified()))
                    if (file.isDirectory) {
                        val size = getDirectorySize(file)
                        val count = getDirectoryItemCount(file)
                        """{"name":"$nameEscaped","type":"folder","size":$size,"items":$count,"modified":"$modified"}"""
                    } else {
                        val type = getFileType(file.name)
                        val size = file.length()
                        """{"name":"$nameEscaped","type":"$type","size":$size,"modified":"$modified"}"""
                    }
                }
                val json = "[" + jsonItems.joinToString(",") + "]"
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            uri == "/api/download" -> {
                handleDownload(session, asAttachment = true)
            }
            uri == "/api/stream" -> {
                handleDownload(session, asAttachment = false)
            }
            uri == "/api/thumbnail" -> {
                handleThumbnail(session)
            }
            uri == "/api/upload" -> {
                val files = HashMap<String, String>()
                session.parseBody(files)

                val tempFilePath = files["file"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file uploaded")
                // Check if filename parameter is present in parameters or headers
                val originalName = session.parameters["file"]?.firstOrNull() ?: session.parms["file"] ?: "uploaded_file"
                val fileName = File(originalName).name // Sanitize

                val uploadDir = getUploadsDir()
                var destFile = File(uploadDir, fileName)
                if (destFile.exists()) {
                    val nameWithoutExtension = destFile.nameWithoutExtension
                    val extension = destFile.extension
                    var counter = 1
                    while (destFile.exists()) {
                        val suffix = if (extension.isNotEmpty()) ".$extension" else ""
                        destFile = File(uploadDir, "$nameWithoutExtension ($counter)$suffix")
                        counter++
                    }
                }

                val tempFile = File(tempFilePath)
                try {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                } catch (e: Exception) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to save file: ${e.message}")
                }

                // Broadcast WebSocket event
                onUploadComplete?.invoke(destFile.name)
                onFileChanged?.invoke()

                val json = """{"ok":true,"filename":"${destFile.name.replace("\"", "\\\"")}","size":${destFile.length()}}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            uri == "/api/qr" -> {
                val ip = getLocalIpAddress()
                val url = "http://$ip:$port"
                val qrBytes = generateQrCodePng(url)
                val resp = newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(qrBytes), qrBytes.size.toLong())
                resp.addHeader("Cache-Control", "public, max-age=60")
                resp
            }
            uri == "/api/file" && session.method == Method.DELETE -> {
                val pathParams = session.parameters["path"]
                val relativePath = pathParams?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
                val targetFile = resolvePath(relativePath)

                // Safety: only allow deleting files inside uploads/
                val uploadsCanonical = getUploadsDir().canonicalPath
                val fileCanonical = targetFile.canonicalPath
                if (!fileCanonical.startsWith(uploadsCanonical)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Can only delete files from uploads/")
                }

                if (!targetFile.exists()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                }

                val deletedName = targetFile.name
                val deleted = if (targetFile.isDirectory) {
                    targetFile.deleteRecursively()
                } else {
                    targetFile.delete()
                }

                if (deleted) {
                    // Broadcast WebSocket event
                    onFileDeleted?.invoke(deletedName)
                    onFileChanged?.invoke()

                    val json = """{"ok":true,"deleted":"${deletedName.replace("\"", "\\\"")}"}"""
                    newFixedLengthResponse(Response.Status.OK, "application/json", json)
                } else {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Delete failed")
                }
            }
            uri == "/api/uploads/size" -> {
                val uploadsDir = getUploadsDir()
                var count = 0
                var totalSize: Long = 0
                if (uploadsDir.exists()) {
                    uploadsDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            totalSize += file.length()
                            count++
                        }
                    }
                }
                val json = """{"count":$count,"totalSize":$totalSize}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            uri == "/api/download-zip" && session.method == Method.POST -> {
                handleZipDownload(session)
            }
            uri == "/api/devices" -> {
                newFixedLengthResponse(Response.Status.OK, "application/json", "[]")
            }
            uri == "/api/folders" -> {
                newFixedLengthResponse(Response.Status.OK, "application/json", "[\"/uploads\"]")
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    // ─── Download / Stream handler (shared logic) ───
    private fun handleDownload(session: IHTTPSession, asAttachment: Boolean): Response {
        val pathParams = session.parameters["path"]
        val relativePath = pathParams?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
        val targetFile = resolvePath(relativePath)

        if (!targetFile.exists() || !targetFile.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        val ext = targetFile.extension.lowercase()
        val mime = getProperMimeType(ext)
        val totalSize = targetFile.length()

        val rangeHeader = session.headers["range"] ?: session.headers["Range"]
        val response = if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                val rangeValue = rangeHeader.substring(6)
                val parts = rangeValue.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                var end = parts.getOrNull(1)?.toLongOrNull() ?: (totalSize - 1)

                if (start >= totalSize) {
                    val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    r.addHeader("Content-Range", "bytes */$totalSize")
                    r
                } else {
                    if (end >= totalSize) end = totalSize - 1
                    val contentLength = end - start + 1
                    val rangeStream = RangeInputStream(targetFile, start, contentLength)
                    val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, rangeStream, contentLength)
                    r.addHeader("Content-Range", "bytes $start-$end/$totalSize")
                    r.addHeader("Content-Length", contentLength.toString())
                    r
                }
            } catch (e: Exception) {
                val fis = java.io.BufferedInputStream(FileInputStream(targetFile), 8 * 1024 * 1024)
                newFixedLengthResponse(Response.Status.OK, mime, fis, totalSize)
            }
        } else {
            val fis = java.io.BufferedInputStream(FileInputStream(targetFile), 8 * 1024 * 1024)
            newFixedLengthResponse(Response.Status.OK, mime, fis, totalSize)
        }

        if (asAttachment) {
            response.addHeader("Content-Disposition", "attachment; filename=\"${targetFile.name}\"")
        } else {
            response.addHeader("Content-Disposition", "inline")
        }
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", totalSize.toString())
        return response
    }

    // Proper MIME type resolver — covers formats Android's MimeTypeMap misses
    private fun getProperMimeType(ext: String): String {
        // Manual overrides for commonly-missed types
        val overrides = mapOf(
            "mkv" to "video/x-matroska",
            "flv" to "video/x-flv",
            "wmv" to "video/x-ms-wmv",
            "avi" to "video/x-msvideo",
            "ts" to "video/mp2t",
            "m4v" to "video/mp4",
            "flac" to "audio/flac",
            "opus" to "audio/opus",
            "wma" to "audio/x-ms-wma",
            "m4a" to "audio/mp4",
            "ogg" to "audio/ogg",
            "aac" to "audio/aac",
            "webm" to "video/webm",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "avif" to "image/avif",
            "7z" to "application/x-7z-compressed",
            "apk" to "application/vnd.android.package-archive"
        )
        overrides[ext]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ─── Thumbnail handler ───
    private fun handleThumbnail(session: IHTTPSession): Response {
        val pathParams = session.parameters["path"]
        val relativePath = pathParams?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
        val targetFile = resolvePath(relativePath)

        if (!targetFile.exists() || !targetFile.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        // Only generate thumbnails for image types
        val type = getFileType(targetFile.name)
        if (type != "image") {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Not an image file")
        }

        val size = session.parameters["size"]?.firstOrNull()?.toIntOrNull() ?: 200

        // Check thumbnail cache
        val cacheKey = "${targetFile.absolutePath}_${targetFile.lastModified()}_$size"
        val cacheFile = File(thumbCacheDir, cacheKey.hashCode().toUInt().toString(16) + ".jpg")

        if (cacheFile.exists()) {
            val fis = FileInputStream(cacheFile)
            val resp = newFixedLengthResponse(Response.Status.OK, "image/jpeg", fis, cacheFile.length())
            resp.addHeader("Cache-Control", "public, max-age=300")
            return resp
        }

        // Generate thumbnail
        try {
            // First pass: get dimensions
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(targetFile.absolutePath, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot decode image")
            }

            // Calculate sample size for efficient decoding
            val maxDim = maxOf(options.outWidth, options.outHeight)
            var inSampleSize = 1
            while (maxDim / inSampleSize > size * 2) {
                inSampleSize *= 2
            }

            // Second pass: decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            val bitmap = BitmapFactory.decodeFile(targetFile.absolutePath, decodeOptions)
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot decode image")

            // Scale to exact thumbnail size
            val scale = size.toFloat() / maxOf(bitmap.width, bitmap.height)
            val thumbWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val thumbHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val thumbnail = Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)
            if (thumbnail != bitmap) bitmap.recycle()

            // Save to cache
            val baos = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            thumbnail.recycle()
            val thumbBytes = baos.toByteArray()

            try {
                cacheFile.writeBytes(thumbBytes)
            } catch (e: Exception) {
                // Cache write failure is non-fatal
            }

            val resp = newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(thumbBytes), thumbBytes.size.toLong()
            )
            resp.addHeader("Cache-Control", "public, max-age=300")
            return resp
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Thumbnail error: ${e.message}")
        }
    }

    // ─── Batch ZIP download handler ───
    private fun handleZipDownload(session: IHTTPSession): Response {
        try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)

            // Support two formats:
            // 1. Form POST: files_json param contains JSON string (from hidden form submission)
            // 2. Raw JSON POST: body is raw JSON with "files" key (from fetch API)
            val filesJson = session.parameters["files_json"]?.firstOrNull()
            val body = filesJson ?: bodyMap["postData"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Missing request body"
            )

            // Parse file paths — try as raw JSON array first, then as object with "files" key
            var paths = parseJsonStringArray(body, "files")
            if (paths.isEmpty()) {
                // Maybe it's a bare JSON array: ["path1","path2"]
                paths = parseJsonBareArray(body)
            }
            if (paths.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files specified")
            }

            // Resolve and validate all files
            val resolvedFiles = mutableListOf<Pair<String, File>>()
            for (path in paths) {
                try {
                    val file = resolvePath(path)
                    if (file.exists() && file.isFile) {
                        resolvedFiles.add(Pair(file.name, file))
                    }
                } catch (e: SecurityException) {
                    // Skip files with traversal issues
                }
            }

            if (resolvedFiles.isEmpty()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No valid files found")
            }

            // Stream ZIP using PipedOutputStream
            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, 1024 * 1024) // 1MB pipe buffer

            Thread {
                try {
                    ZipOutputStream(pipedOut).use { zos ->
                        zos.setLevel(1) // Fast compression (speed > ratio for LAN transfer)
                        val buffer = ByteArray(8 * 1024 * 1024) // 8MB buffer
                        val usedNames = mutableSetOf<String>()

                        for ((name, file) in resolvedFiles) {
                            // Handle duplicate names
                            var zipName = name
                            if (usedNames.contains(zipName)) {
                                val base = file.nameWithoutExtension
                                val ext = if (file.extension.isNotEmpty()) ".${file.extension}" else ""
                                var counter = 1
                                while (usedNames.contains(zipName)) {
                                    zipName = "$base ($counter)$ext"
                                    counter++
                                }
                            }
                            usedNames.add(zipName)

                            zos.putNextEntry(ZipEntry(zipName))
                            FileInputStream(file).use { fis ->
                                var len: Int
                                while (fis.read(buffer).also { len = it } > 0) {
                                    zos.write(buffer, 0, len)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { pipedOut.close() } catch (_: Exception) {}
                }
            }.start()

            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/zip",
                pipedIn,
                -1 // Unknown length — chunked transfer
            )
            response.addHeader("Content-Disposition", "attachment; filename=\"LAN_Beam_files.zip\"")
            return response
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "ZIP error: ${e.message}"
            )
        }
    }

    // Simple JSON string array parser (avoids adding a JSON library dependency)
    private fun parseJsonStringArray(json: String, key: String): List<String> {
        val result = mutableListOf<String>()
        val keyPattern = "\"$key\""
        val keyIdx = json.indexOf(keyPattern)
        if (keyIdx < 0) return result

        val bracketStart = json.indexOf('[', keyIdx)
        val bracketEnd = json.indexOf(']', bracketStart)
        if (bracketStart < 0 || bracketEnd < 0) return result

        val arrayContent = json.substring(bracketStart + 1, bracketEnd)
        val regex = Regex("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"")
        for (match in regex.findAll(arrayContent)) {
            result.add(match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"))
        }
        return result
    }

    // Parse a bare JSON array like ["path1","path2"]
    private fun parseJsonBareArray(json: String): List<String> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val content = trimmed.substring(1, trimmed.length - 1)
        val result = mutableListOf<String>()
        val regex = Regex("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"")
        for (match in regex.findAll(content)) {
            result.add(match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"))
        }
        return result
    }

    private fun resolvePath(relativePath: String): File {
        val clean = relativePath.trimStart('/')
        val target = if (clean.startsWith("uploads")) {
            val sub = clean.removePrefix("uploads").trimStart('/')
            if (sub.isEmpty()) getUploadsDir() else File(getUploadsDir(), sub)
        } else {
            if (clean.isEmpty()) getSharedDir() else File(getSharedDir(), clean)
        }

        // Path traversal protection
        val targetCanonical = target.canonicalPath
        val sharedCanonical = getSharedDir().canonicalPath
        val uploadsCanonical = getUploadsDir().canonicalPath

        val inShared = targetCanonical == sharedCanonical || targetCanonical.startsWith(sharedCanonical + File.separator)
        val inUploads = targetCanonical == uploadsCanonical || targetCanonical.startsWith(uploadsCanonical + File.separator)

        if (!inShared && !inUploads) {
            throw SecurityException("Access denied: path traversal detected")
        }
        return target
    }

    private fun getSharedStats(): Pair<Int, Long> {
        val sharedDir = getSharedDir()
        var count = 0
        var totalSize: Long = 0
        sharedDir.listFiles()?.forEach { file ->
            if (!file.name.startsWith(".")) {
                count++
                if (file.isFile) {
                    totalSize += file.length()
                } else if (file.isDirectory) {
                    totalSize += getDirectorySize(file)
                }
            }
        }
        return Pair(count, totalSize)
    }

    private fun getCachedDirInfo(dir: File): CachedDirInfo {
        val key = dir.absolutePath
        val now = System.currentTimeMillis()
        val cached = dirInfoCache[key]
        if (cached != null && (now - cached.timestamp) < DIR_CACHE_TTL_MS) {
            return cached
        }
        var size: Long = 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (!file.name.startsWith(".")) {
                count++
                if (file.isFile) {
                    size += file.length()
                }
            }
        }
        val info = CachedDirInfo(size, count, now)
        dirInfoCache[key] = info
        return info
    }

    private fun getDirectorySize(dir: File): Long {
        return getCachedDirInfo(dir).size
    }

    private fun getDirectoryItemCount(dir: File): Int {
        return getCachedDirInfo(dir).itemCount
    }

    private fun getFileType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "mov", "avi", "webm", "wmv", "flv", "m4v", "ts" -> "video"
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "opus" -> "audio"
            "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico", "heic", "heif", "tiff" -> "image"
            "pdf" -> "pdf"
            "zip", "tar", "gz", "rar", "7z", "bz2", "xz", "tgz" -> "archive"
            "apk" -> "apk"
            else -> "doc"
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.isNotEmpty() && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readPerm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            val writePerm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            context.checkSelfPermission(readPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(writePerm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun getSharedDir(): File {
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LANBeam/Shared")
        return if (hasStoragePermission()) {
            if (!publicDir.exists()) publicDir.mkdirs()
            publicDir
        } else {
            val privateDir = File(context.getExternalFilesDir(null), "Shared")
            if (!privateDir.exists()) privateDir.mkdirs()
            privateDir
        }
    }

    fun getUploadsDir(): File {
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LANBeam/Uploads")
        return if (hasStoragePermission()) {
            if (!publicDir.exists()) publicDir.mkdirs()
            publicDir
        } else {
            val privateDir = File(context.getExternalFilesDir(null), "Uploads")
            if (!privateDir.exists()) privateDir.mkdirs()
            privateDir
        }
    }

    private fun getDeviceName(): String {
        val prefs = context.getSharedPreferences("lanbeam_prefs", Context.MODE_PRIVATE)
        var name = prefs.getString("device_name", "") ?: ""
        if (name.isEmpty()) {
            name = "${Build.MANUFACTURER} ${Build.MODEL}"
            prefs.edit().putString("device_name", name).apply()
        }
        return name
    }

    private fun generateQrCodePng(url: String): ByteArray {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 256, 256)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fillCol = Color.WHITE
        val backCol = Color.parseColor("#1a1a24")
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) fillCol else backCol)
            }
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()
        bitmap.recycle()
        return byteArray
    }
}

class RangeInputStream(private val file: File, private val start: Long, private val length: Long) : java.io.InputStream() {
    private val raf = java.io.RandomAccessFile(file, "r")
    private var bytesRead = 0L

    init {
        raf.seek(start)
    }

    override fun read(): Int {
        if (bytesRead >= length) return -1
        val b = raf.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= length) return -1
        val maxToRead = minOf(len.toLong(), length - bytesRead).toInt()
        val read = raf.read(b, off, maxToRead)
        if (read != -1) bytesRead += read
        return read
    }

    override fun close() {
        raf.close()
    }
}
