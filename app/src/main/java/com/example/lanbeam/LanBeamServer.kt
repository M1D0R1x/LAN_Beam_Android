package com.example.lanbeam

import android.content.Context
import android.graphics.Bitmap
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale

class LanBeamServer(private val context: Context, val port: Int) : NanoHTTPD(port) {

    private val htmlContent: String by lazy {
        try {
            context.assets.open("frontend.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Error loading frontend</h1><p>${e.message}</p></body></html>"
        }
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
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" -> {
                newFixedLengthResponse(Response.Status.OK, "text/html", htmlContent)
            }
            uri == "/api/info" -> {
                val ip = getLocalIpAddress()
                val (count, size) = getSharedStats()
                val deviceName = getDeviceName()
                val json = """
                    {
                        "ip": "$ip",
                        "port": $port,
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
                val pathParams = session.parameters["path"]
                val relativePath = pathParams?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
                val targetFile = resolvePath(relativePath)

                if (!targetFile.exists() || !targetFile.isFile) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                }

                val ext = targetFile.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val totalSize = targetFile.length()

                val rangeHeader = session.headers["range"] ?: session.headers["Range"]
                val response = if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    try {
                        val rangeValue = rangeHeader.substring(6)
                        val parts = rangeValue.split("-")
                        var start = parts[0].toLongOrNull() ?: 0L
                        var end = parts.getOrNull(1)?.toLongOrNull() ?: (totalSize - 1)

                        if (start >= totalSize) {
                            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                            r.addHeader("Content-Range", "bytes */$totalSize")
                            r
                        } else {
                            if (end >= totalSize) {
                                end = totalSize - 1
                            }
                            val contentLength = end - start + 1
                            val rangeStream = RangeInputStream(targetFile, start, contentLength)
                            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, rangeStream, contentLength)
                            r.addHeader("Content-Range", "bytes $start-$end/$totalSize")
                            r.addHeader("Content-Length", contentLength.toString())
                            r
                        }
                    } catch (e: Exception) {
                        val fis = java.io.BufferedInputStream(FileInputStream(targetFile), 1024 * 1024)
                        newFixedLengthResponse(Response.Status.OK, mime, fis, totalSize)
                    }
                } else {
                    val fis = java.io.BufferedInputStream(FileInputStream(targetFile), 1024 * 1024)
                    newFixedLengthResponse(Response.Status.OK, mime, fis, totalSize)
                }

                response.addHeader("Content-Disposition", "attachment; filename=\"${targetFile.name}\"")
                response.addHeader("Accept-Ranges", "bytes")
                response
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

                val json = """{"ok":true,"filename":"${destFile.name.replace("\"", "\\\"")}","size":${destFile.length()}}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            uri == "/api/qr" -> {
                val ip = getLocalIpAddress()
                val url = "http://$ip:$port"
                val qrBytes = generateQrCodePng(url)
                newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(qrBytes), qrBytes.size.toLong())
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

                val deleted = if (targetFile.isDirectory) {
                    targetFile.deleteRecursively()
                } else {
                    targetFile.delete()
                }

                if (deleted) {
                    val json = """{"ok":true,"deleted":"${targetFile.name.replace("\"", "\\\"")}"}"""
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

    private fun getDirectorySize(dir: File): Long {
        var size: Long = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                size += file.length()
            }
        }
        return size
    }

    private fun getDirectoryItemCount(dir: File): Int {
        return dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
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
