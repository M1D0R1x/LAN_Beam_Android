package com.example.lanbeam

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import com.example.lanbeam.server.LanAddresses
import com.example.lanbeam.server.ServerEnv
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.File

/** Where shared and received files live. Public Download/LANBeam when allowed, app storage otherwise. */
object StorageDirs {
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun dir(context: Context, name: String): File {
        val f = if (hasAllFilesAccess(context)) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LANBeam/$name")
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, name)
        }
        if (!f.exists()) f.mkdirs()
        return f
    }

    fun shared(context: Context) = dir(context, "Shared")
    fun uploads(context: Context) = dir(context, "Uploads")

    /** Human-readable location, for the UI. */
    fun describe(context: Context): String =
        if (hasAllFilesAccess(context)) "Download/LANBeam" else "app storage (Android/data)"
}

object DeviceName {
    private const val PREFS = "lanbeam_prefs"
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("device_name", "").orEmpty()
        if (name.isNotBlank()) return name
        return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".also { set(context, it) }
    }

    fun set(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("device_name", name.trim()).apply()
    }
}

object Qr {
    /** Dark modules on white with a quiet zone: inverted codes fail in many camera apps. */
    fun bitmap(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun png(text: String): ByteArray {
        val bmp = bitmap(text, 320)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }
}

object Network {
    /** Interface name of the Wi-Fi network this phone is a CLIENT of, if any. */
    fun wifiClientInterface(context: Context): String? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.firstNotNullOfOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) cm.getLinkProperties(n)?.interfaceName else null
        }
    } catch (_: Exception) {
        null
    }

    fun addresses(context: Context): List<LanAddresses.LanAddress> =
        LanAddresses.rank(LanAddresses.enumerate(), wifiClientInterface(context))
}

class AndroidServerEnv(private val context: Context, private val events: Events) : ServerEnv {

    interface Events {
        fun filesChanged()
        fun uploadComplete(name: String)
        fun fileDeleted(name: String)
    }

    private val html by lazy {
        try {
            context.assets.open("frontend.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Error loading page</h1><p>${e.message}</p></body></html>"
        }
    }

    @Volatile private var cachedAddresses: List<LanAddresses.LanAddress> = emptyList()
    @Volatile private var cachedAt = 0L

    fun invalidateAddresses() { cachedAt = 0 }

    override fun addresses(): List<LanAddresses.LanAddress> {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 5_000) {
            cachedAddresses = Network.addresses(context)
            cachedAt = now
        }
        return cachedAddresses
    }

    override fun sharedDir() = StorageDirs.shared(context)
    override fun uploadsDir() = StorageDirs.uploads(context)
    override fun deviceName() = DeviceName.get(context)
    override fun frontendHtml() = html
    override fun mimeFromPlatform(ext: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    override fun qrPng(text: String): ByteArray = Qr.png(text)

    private val thumbDir by lazy { File(context.cacheDir, "thumbnails").also { it.mkdirs() } }

    override fun thumbnail(file: File, size: Int): ByteArray? {
        val key = "${file.absolutePath}_${file.lastModified()}_$size".hashCode().toUInt().toString(16)
        val cache = File(thumbDir, "$key.jpg")
        if (cache.exists()) return cache.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = size.toFloat() / maxOf(bmp.width, bmp.height)
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val bytes = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 78, it) }.toByteArray()
        if (thumb !== bmp) thumb.recycle()
        bmp.recycle()
        runCatching { cache.writeBytes(bytes) }
        return bytes
    }

    override fun onFilesChanged() = events.filesChanged()
    override fun onUploadComplete(name: String) = events.uploadComplete(name)
    override fun onFileDeleted(name: String) = events.fileDeleted(name)
}
