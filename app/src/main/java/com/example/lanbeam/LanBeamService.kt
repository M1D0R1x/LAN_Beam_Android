package com.example.lanbeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.lanbeam.server.LanBeamServer
import com.example.lanbeam.server.TransferTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.BindException
import java.util.concurrent.Executors

/**
 * Foreground service that owns the HTTP + WebSocket servers.
 *
 * Crash-proofing notes (the share-sheet "app closes" bug):
 *  - startForeground() runs FIRST on every onStartCommand. Each startForegroundService() call must
 *    be answered with startForeground() within seconds, even when the server is already running,
 *    or Android kills the process with ForegroundServiceDidNotStartInTimeException.
 *  - Files arriving from the share sheet are copied here on a worker thread (they were copied on
 *    the main thread in the activity, which froze the UI on large videos until Android killed it).
 *  - Android 15 caps dataSync services at 6 h/day and calls onTimeout(); we stop cleanly instead
 *    of being crashed.
 */
class LanBeamService : Service() {

    companion object {
        const val CHANNEL_ID = "lanbeam_server"
        const val NOTIFICATION_ID = 1
        const val HTTP_PORT = 8765
        const val WS_PORT = 8766
        const val ACTION_START = "com.example.lanbeam.START_SERVER"
        const val ACTION_STOP = "com.example.lanbeam.STOP_SERVER"
        const val ACTION_IMPORT = "com.example.lanbeam.IMPORT"
        private const val LOCK_LINGER_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LanBeamService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LanBeamService::class.java).setAction(ACTION_STOP))
        }

        /** Hands URIs to the service together with read grants, so the copy survives the activity. */
        fun import(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("files", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val intent = Intent(context, LanBeamService::class.java).setAction(ACTION_IMPORT).apply {
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private var server: LanBeamServer? = null
    private var wsServer: LanBeamWebSocket? = null
    private lateinit var env: AndroidServerEnv
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lanbeam-import") }
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()
    private val releaseLocks = Runnable { setLocksHeld(false) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val ticker = object : Runnable {
        override fun run() {
            TransferTracker.reapIdle()
            TransferTracker.prune()
            updateNotification()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        env = AndroidServerEnv(this, object : AndroidServerEnv.Events {
            override fun filesChanged() = LanBeamState.filesChanged()
            override fun uploadComplete(name: String) { wsServer?.broadcastUploadComplete(name) }
            override fun fileDeleted(name: String) { wsServer?.broadcastFileDeleted(name) }
        })
        TransferTracker.onActiveCountChanged = { count -> main.post { onActiveTransfers(count) } }
        // One place turns "files changed" (upload, import, delete in the app) into a live refresh
        // for every connected browser.
        scope.launch(Dispatchers.IO) { LanBeamState.filesVersion.drop(1).collect { wsServer?.broadcastFilesChanged() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Sent with plain startService(), so there is no foreground promise to keep.
            shutdown()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        promoteToForeground()
        ensureServer()
        if (intent?.action == ACTION_IMPORT) importFrom(intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        LanBeamState.update { it.copy(error = "Android paused sharing after its daily 6-hour limit. Tap Start to resume.") }
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        io.shutdown()
        scope.cancel()
        TransferTracker.onActiveCountChanged = null
        super.onDestroy()
    }

    private fun promoteToForeground() {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            e.printStackTrace()
        }
    }

    private fun ensureServer() {
        if (server?.isAlive == true) return
        shutdown()
        try {
            val s = LanBeamServer(env, HTTP_PORT)
            s.startServer()
            server = s
            wsServer = runCatching { LanBeamWebSocket(WS_PORT).also { it.start(0) } }.getOrNull()
            LanBeamState.update { it.copy(running = true, error = null, addresses = env.addresses()) }
            registerNetworkCallback()
            main.removeCallbacks(ticker)
            main.post(ticker)
        } catch (e: BindException) {
            LanBeamState.update { it.copy(running = false, error = "Port $HTTP_PORT is already in use by another app.") }
        } catch (e: Exception) {
            LanBeamState.update { it.copy(running = false, error = "Could not start: ${e.message}") }
        }
        updateNotification()
    }

    private fun shutdown() {
        main.removeCallbacks(ticker)
        runCatching { server?.stop() }
        runCatching { wsServer?.stop() }
        server = null
        wsServer = null
        unregisterNetworkCallback()
        main.removeCallbacks(releaseLocks)
        setLocksHeld(false)
        LanBeamState.update { it.copy(running = false) }
    }

    private fun importFrom(intent: Intent) {
        val clip = intent.clipData ?: return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) return
        LanBeamState.update { it.copy(importing = it.importing + uris.size) }
        onActiveTransfers(TransferTracker.activeCount() + 1)
        io.execute {
            for (uri in uris) {
                try {
                    Importer.copy(this, uri, env.sharedDir())
                } finally {
                    LanBeamState.update { it.copy(importing = (it.importing - 1).coerceAtLeast(0)) }
                }
            }
            main.post {
                LanBeamState.filesChanged()
                onActiveTransfers(TransferTracker.activeCount())
            }
        }
    }

    // ─── Keeping the radio and CPU awake while bytes are moving ───

    /**
     * Without these, Wi-Fi power-save and CPU sleep throttle transfers the moment the screen turns
     * off (a big part of "sometimes very slow"). Held only while a transfer is active, plus a short
     * linger, so an idle server does not drain the battery.
     */
    private fun onActiveTransfers(count: Int) {
        if (count > 0) {
            main.removeCallbacks(releaseLocks)
            setLocksHeld(true)
        } else {
            main.removeCallbacks(releaseLocks)
            main.postDelayed(releaseLocks, LOCK_LINGER_MS)
        }
        updateNotification()
    }

    @Suppress("DEPRECATION")
    private fun setLocksHeld(held: Boolean) {
        if (held) {
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanBeam:transfer").apply {
                        setReferenceCounted(false)
                        acquire(6 * 60 * 60 * 1000L)
                    }
            }
            if (wifiLocks.none { it.isHeld }) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val modes = buildList {
                    add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
                }
                wifiLocks = modes.mapNotNull { mode ->
                    runCatching { wm.createWifiLock(mode, "LanBeam:transfer:$mode").apply { setReferenceCounted(false); acquire() } }.getOrNull()
                }
            }
        } else {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            wakeLock = null
            wifiLocks.forEach { runCatching { if (it.isHeld) it.release() } }
            wifiLocks = emptyList()
        }
    }

    // ─── Network changes (Wi-Fi switch, hotspot on/off) change the address to show ───

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) = refreshAddresses()
            override fun onLost(network: AndroidNetwork) = refreshAddresses()
            override fun onLinkPropertiesChanged(network: AndroidNetwork, lp: android.net.LinkProperties) = refreshAddresses()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    /** Also used by the UI's refresh button; hotspot changes do not always fire a callback. */
    private fun refreshAddresses() {
        main.postDelayed({
            env.invalidateAddresses()
            LanBeamState.update { it.copy(addresses = env.addresses()) }
            updateNotification()
        }, 500)
    }

    // ─── Notification ───

    private var lastNotificationKey: String? = null

    private fun updateNotification() {
        if (server == null) return
        val active = TransferTracker.transfers.value.filter { it.finishedAt == null }
        // Idle: only re-post when the URL changes. Active: at most every tick (2 s).
        val key = if (active.isEmpty()) "idle:${server?.primaryUrl()}" else "active:${System.currentTimeMillis() / 2000}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val url = server?.primaryUrl()
        val active = TransferTracker.transfers.value.filter { it.finishedAt == null }
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this, 1, Intent(this, LanBeamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
        if (active.isNotEmpty()) {
            val total = active.sumOf { it.total.coerceAtLeast(0) }
            val done = active.sumOf { it.bytes }
            val title = if (active.size == 1) active[0].name else "${active.size} transfers"
            b.setContentTitle(title)
                .setContentText("${Format.size(done)}${if (total > 0) " of ${Format.size(total)}" else ""} · ${Format.size(active.sumOf { it.bytesPerSec }.toLong())}/s")
            if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false) else b.setProgress(0, 0, true)
        } else {
            b.setContentTitle(if (url != null) "Sharing on your network" else "LAN Beam")
                .setContentText(url ?: "Starting…")
        }
        return b.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
