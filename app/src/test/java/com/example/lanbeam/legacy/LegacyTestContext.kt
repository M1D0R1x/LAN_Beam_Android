package com.example.lanbeam.legacy

import android.content.ContextWrapper
import java.io.File

/** Just enough Context for the v2.1 server: no storage permission, app-private dirs under [root]. */
class LegacyTestContext(private val root: File) : ContextWrapper(null) {
    override fun checkSelfPermission(permission: String): Int = -1
    override fun getExternalFilesDir(type: String?): File = root
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
}
