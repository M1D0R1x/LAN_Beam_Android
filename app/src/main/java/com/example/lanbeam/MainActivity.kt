package com.example.lanbeam

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lanbeam.server.FileTypes
import com.example.lanbeam.server.TransferTracker
import com.example.lanbeam.theme.LANBeamTheme
import com.example.lanbeam.ui.AppActions
import com.example.lanbeam.ui.AppViewModel
import com.example.lanbeam.ui.FileItem
import com.example.lanbeam.ui.LanBeamApp
import com.example.lanbeam.ui.PermissionInfo
import com.example.lanbeam.ui.WelcomeScreen
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), AppActions {

    private val vm: AppViewModel by viewModels()

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.addFiles(uris)
            toast(if (uris.size == 1) "Adding 1 file…" else "Adding ${uris.size} files…")
        }
    }

    /** All runtime permissions in one system prompt, asked once from the welcome screen. */
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onResume()
        vm.finishOnboarding()
        LanBeamService.start(this)
    }

    private fun runtimePermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun completeOnboarding() {
        val missing = runtimePermissions()
        if (missing.isEmpty()) {
            vm.finishOnboarding()
            LanBeamService.start(this)
        } else {
            permissionLauncher.launch(missing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // First launch shows the welcome screen, which explains and asks for permissions before
        // anything starts. Afterwards (or when files arrive from the share sheet) start right away.
        val isShare = intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
        if (vm.onboarded.value || isShare) LanBeamService.start(this)
        // Only on a fresh launch: after rotation / process restore the same share intent is
        // delivered again and would import the files twice.
        if (savedInstanceState == null) handleShareIntent(intent)

        setContent {
            LANBeamTheme {
                val status by vm.status.collectAsStateWithLifecycle()
                val transfers by vm.transfers.collectAsStateWithLifecycle()
                val shared by vm.shared.collectAsStateWithLifecycle()
                val received by vm.received.collectAsStateWithLifecycle()
                val deviceName by vm.deviceName.collectAsStateWithLifecycle()
                val needsLegacyStorage by vm.needsLegacyStorage.collectAsStateWithLifecycle()
                val receivedLocation by vm.receivedLocation.collectAsStateWithLifecycle()
                val onboarded by vm.onboarded.collectAsStateWithLifecycle()
                if (!onboarded) {
                    WelcomeScreen(
                        permissions = PermissionInfo.forThisDevice(),
                        onContinue = ::completeOnboarding,
                    )
                } else LanBeamApp(
                    status = status,
                    deviceName = deviceName,
                    transfers = transfers,
                    shared = shared,
                    received = received,
                    needsFileAccessPrompt = needsLegacyStorage,
                    receivedLocation = receivedLocation,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onRefreshNetwork = vm::refreshAddresses,
                    onDelete = vm::delete,
                    onClearShared = vm::clearShared,
                    onClearTransfers = TransferTracker::clearFinished,
                    onRename = vm::setDeviceName,
                    actions = this,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    /**
     * Share sheet → LAN Beam. The files are handed to the service (with their read grants) and
     * copied there in the background; the activity stays open and shows progress. Previously the
     * copy ran on the main thread, and large files froze the app until Android closed it.
     */
    private fun handleShareIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
            else -> return
        }.ifEmpty { intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty() }
        if (uris.isEmpty()) {
            toast("Nothing to add - only files can be shared to LAN Beam")
            return
        }
        vm.addFiles(uris)
        toast(if (uris.size == 1) "Adding 1 file to shared files…" else "Adding ${uris.size} files to shared files…")
    }

    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun Intent.streamExtras(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ─── AppActions ───

    override fun pickFiles() {
        try {
            pickFilesLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            toast("No file picker available")
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    override fun openFile(item: FileItem) {
        if (item.file.isDirectory) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(item.file), FileTypes.mimeOf(item.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No app can open this file")
        }
    }

    override fun shareFile(item: FileItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = FileTypes.mimeOf(item.name)
            putExtra(Intent.EXTRA_STREAM, uriFor(item.file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, item.name))
    }

    override fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LAN Beam link", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Link copied")
    }

    override fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share link"))
    }

    override fun shareApp() {
        thread(name = "lanbeam-apk") {
            try {
                val src = File(packageCodePath)
                val dest = File(cacheDir, "LAN_Beam.apk")
                if (!dest.exists() || dest.length() != src.length() || dest.lastModified() < src.lastModified()) src.copyTo(dest, overwrite = true)
                val uri = uriFor(dest)
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Send LAN Beam app"))
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not prepare the app file: ${e.message}") }
            }
        }
    }

    /** Android 10 and older only: storage permission to save received files in Download. */
    override fun requestFileAccess() {
        val missing = runtimePermissions()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing) else openAppSettings()
    }

    private fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
    }
}
