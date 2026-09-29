package com.example.lanbeam.server

object FileTypes {
    fun typeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "mkv", "mov", "avi", "webm", "wmv", "flv", "m4v", "ts", "3gp" -> "video"
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "opus" -> "audio"
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "ico", "heic", "heif", "tiff", "avif" -> "image"
        "pdf" -> "pdf"
        "zip", "tar", "gz", "rar", "7z", "bz2", "xz", "tgz" -> "archive"
        "apk", "apks", "xapk", "aab" -> "apk"
        else -> "doc"
    }

    private val MIME = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo", "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
        "ts" to "video/mp2t", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/wav", "wma" to "audio/x-ms-wma",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "svg" to "image/svg+xml", "bmp" to "image/bmp",
        "pdf" to "application/pdf", "zip" to "application/zip", "7z" to "application/x-7z-compressed",
        "txt" to "text/plain", "html" to "text/html", "json" to "application/json",
        "apk" to "application/vnd.android.package-archive",
        // Split-APK bundles are opaque archives to a browser; octet-stream keeps them byte-exact.
        "apks" to "application/octet-stream", "xapk" to "application/octet-stream",
    )

    fun mimeOf(name: String, platform: (String) -> String? = { null }): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME[ext] ?: platform(ext) ?: "application/octet-stream"
    }
}
