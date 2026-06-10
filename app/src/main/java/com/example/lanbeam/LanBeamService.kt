package com.example.lanbeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class LanBeamService : Service() {

    companion object {
        const val CHANNEL_ID = "lanbeam_server"
        const val NOTIFICATION_ID = 1
        const val HTTP_PORT = 8765
        const val WS_PORT = 8766
        const val ACTION_STOP = "com.example.lanbeam.STOP_SERVER"
    }

    inner class LocalBinder : Binder() {
        val service: LanBeamService get() = this@LanBeamService
    }

    private val binder = LocalBinder()
    var server: LanBeamServer? = null
        private set
    var wsServer: LanBeamWebSocket? = null
        private set
    var isRunning = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            startServer()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        try {
            server = LanBeamServer(this, HTTP_PORT)
            wsServer = LanBeamWebSocket(WS_PORT)

            // Wire up WebSocket broadcast to server events
            server?.onFileChanged = { wsServer?.broadcastFilesChanged() }
            server?.onUploadComplete = { fileName -> wsServer?.broadcastUploadComplete(fileName) }
            server?.onFileDeleted = { fileName -> wsServer?.broadcastFileDeleted(fileName) }

            server?.start()
            wsServer?.start()
            isRunning = true

            val notification = buildNotification()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            wsServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        server = null
        wsServer = null
        isRunning = false
    }

    fun updateNotification() {
        if (!isRunning) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val ip = server?.getLocalIpAddress() ?: "127.0.0.1"
        val url = "http://$ip:$HTTP_PORT"

        // Tap notification → open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, LanBeamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("📡 LAN Beam Active")
            .setContentText(url)
            .setSubText("Tap to open • File sharing server is running")
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
