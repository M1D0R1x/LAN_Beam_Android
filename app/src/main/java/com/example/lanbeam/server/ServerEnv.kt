package com.example.lanbeam.server

import java.io.File

/**
 * Everything the HTTP server needs from its host. The Android implementation lives in
 * [com.example.lanbeam.AndroidServerEnv]; tests use a plain-JVM one, which is what lets the real
 * server run (and be benchmarked) off-device.
 */
interface ServerEnv {
    fun sharedDir(): File
    fun uploadsDir(): File
    fun deviceName(): String
    fun addresses(): List<LanAddresses.LanAddress>
    fun frontendHtml(): String

    /** Platform MIME lookup for extensions the built-in table does not know. */
    fun mimeFromPlatform(ext: String): String? = null

    /** JPEG thumbnail bytes for an image, or null if unsupported on this host. */
    fun thumbnail(file: File, size: Int): ByteArray? = null

    /** PNG QR code for [text], or null if unsupported. */
    fun qrPng(text: String): ByteArray? = null

    /** Called after files appear/disappear so UIs and WebSocket clients refresh. */
    fun onFilesChanged() {}
    fun onUploadComplete(name: String) {}
    fun onFileDeleted(name: String) {}
}
