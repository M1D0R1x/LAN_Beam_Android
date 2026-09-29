package com.example.lanbeam

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.lanbeam.server.FileNames
import com.example.lanbeam.server.HttpUtil
import com.example.lanbeam.server.TransferTracker
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Copies content:// URIs (share sheet, file picker) into a folder, off the main thread. */
object Importer {

    data class Meta(val name: String, val size: Long)

    fun meta(context: Context, uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return Meta(HttpUtil.sanitizeFileName(name), size)
    }

    /**
     * Copies [uri] into [dir] under a unique name (never overwrites). Writes to a hidden temp file
     * first so a half-copied file is never served. Returns the final file, or null on failure.
     */
    fun copy(context: Context, uri: Uri, dir: File): File? {
        val meta = meta(context, uri)
        val tmp = File(dir, ".import-${System.nanoTime()}.part")
        val handle = TransferTracker.begin(meta.name, TransferTracker.Direction.IMPORT, "this phone", meta.size, 0)
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            handle.add(n.toLong())
                        }
                    }
                }
            } ?: run {
                // Providers without file descriptors (some cloud apps) still offer a stream.
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output, 1 shl 20) }
                } ?: throw IllegalStateException("Cannot open $uri")
            }
            val dest = FileNames.unique(dir, meta.name)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest); tmp.delete()
            }
            handle.end(ok = true)
            dest
        } catch (e: Exception) {
            e.printStackTrace()
            tmp.delete()
            handle.end(ok = false)
            null
        }
    }
}
