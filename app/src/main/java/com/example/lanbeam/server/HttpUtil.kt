package com.example.lanbeam.server

import java.net.URLEncoder

/** Small, dependency-free helpers shared by the HTTP server. Pure JVM so they are unit-testable. */
object HttpUtil {

    /** Inclusive byte range [start, end] of a resource of [total] bytes. */
    data class ByteRange(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    sealed interface RangeResult {
        /** No (usable) Range header: send the whole entity with 200. */
        data object Full : RangeResult
        data class Partial(val range: ByteRange) : RangeResult
        /** Syntactically valid but outside the entity: 416. */
        data object Unsatisfiable : RangeResult
    }

    /**
     * Parses a single-range `Range` header (RFC 9110 §14.1.2). Supports `bytes=a-b`, `bytes=a-`
     * and suffix ranges `bytes=-n`. A multi-range request is answered with its first range, which
     * every real client (browsers, DownloadManager, curl, aria2) accepts.
     */
    fun parseRange(header: String?, total: Long): RangeResult {
        if (header == null) return RangeResult.Full
        val h = header.trim()
        if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.Full
        val spec = h.substring(6).split(',').first().trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Full
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (total <= 0) return RangeResult.Unsatisfiable
        return if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return RangeResult.Full
            if (suffix <= 0) return RangeResult.Unsatisfiable
            val start = (total - suffix).coerceAtLeast(0)
            RangeResult.Partial(ByteRange(start, total - 1))
        } else {
            val start = a.toLongOrNull() ?: return RangeResult.Full
            if (start >= total) return RangeResult.Unsatisfiable
            val end = if (b.isEmpty()) total - 1 else (b.toLongOrNull() ?: return RangeResult.Full)
            if (end < start) return RangeResult.Full
            RangeResult.Partial(ByteRange(start, minOf(end, total - 1)))
        }
    }

    /**
     * Content-Disposition with an ASCII fallback plus RFC 5987 `filename*`, so names with
     * quotes, emoji or non-Latin scripts survive in every browser.
     */
    fun contentDisposition(fileName: String, attachment: Boolean): String {
        val type = if (attachment) "attachment" else "inline"
        val ascii = buildString {
            for (c in fileName) append(if (c.code in 0x20..0x7e && c != '"' && c != '\\') c else '_')
        }
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    /** JSON string literal (with quotes), escaping everything RFC 8259 requires. */
    fun jsonStr(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c == '\u2028' || c == '\u2029' ->
                    sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * Turns a client-supplied name into a safe single path segment: strips directories, control
     * characters and leading dots (dot-files are hidden from listings and reserved for partial
     * uploads), and caps the length while keeping the extension.
     */
    fun sanitizeFileName(raw: String?): String {
        var name = (raw ?: "").replace('\\', '/').substringAfterLast('/')
        name = name.filter { it.code >= 0x20 && it != '\u007f' && it !in "<>:\"|?*" }.trim()
        name = name.trimStart('.').trim()
        if (name.isEmpty()) name = "file"
        if (name.length > 180) {
            val ext = name.substringAfterLast('.', "").take(16)
            val base = name.take(180 - ext.length - 1)
            name = if (ext.isNotEmpty()) "$base.$ext" else name.take(180)
        }
        return name
    }
}
