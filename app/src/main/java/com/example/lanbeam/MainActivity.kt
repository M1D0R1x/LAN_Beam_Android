package com.example.lanbeam

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.lanbeam.theme.LANBeamTheme
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Service binding
    private var lanBeamService: LanBeamService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as LanBeamService.LocalBinder
            lanBeamService = localBinder.service
            serviceBound = true
            serverRunningState.value = lanBeamService?.isRunning == true
            refreshUIState()
            // Load WebView once service is connected
            if (lanBeamService?.isRunning == true) {
                webView?.loadUrl("http://127.0.0.1:${LanBeamService.HTTP_PORT}/")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lanBeamService = null
            serviceBound = false
            serverRunningState.value = false
        }
    }

    // Launcher for files to upload / choose via WebView
    private val webViewFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intentData = result.data
        val uris = if (result.resultCode == RESULT_OK) {
            if (intentData?.clipData != null) {
                val count = intentData.clipData!!.itemCount
                val list = mutableListOf<Uri>()
                for (i in 0 until count) {
                    list.add(intentData.clipData!!.getItemAt(i).uri)
                }
                list.toTypedArray()
            } else if (intentData?.getData() != null) {
                arrayOf(intentData.getData()!!)
            } else {
                null
            }
        } else {
            null
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    // Launcher for picking files to share (adds to Shared directory)
    private val nativeFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intentData = result.data ?: return@registerForActivityResult
            val sharedDir = lanBeamService?.server?.getSharedDir() ?: return@registerForActivityResult
            var count = 0

            val clipData = intentData.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    val copiedFile = copyUriToFolder(this, uri, sharedDir)
                    if (copiedFile != null) count++
                }
            } else {
                val uri = intentData.getData()
                if (uri != null) {
                    val copiedFile = copyUriToFolder(this, uri, sharedDir)
                    if (copiedFile != null) count++
                }
            }

            if (count > 0) {
                Toast.makeText(this, "Shared $count file(s)", Toast.LENGTH_SHORT).show()
                // Notify WebSocket clients
                lanBeamService?.wsServer?.broadcastFilesChanged()
                refreshUIState()
            } else {
                Toast.makeText(this, "Failed to share files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Storage permission request launcher (Android 10 and below)
    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        if (granted) {
            Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
            lanBeamService?.server?.getSharedDir() // Ensure folder created
            refreshUIState()
        } else {
            Toast.makeText(this, "Using internal sandbox storage", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission denied — server will still work", Toast.LENGTH_LONG).show()
        }
    }

    // State holders for Compose UI
    private var serverRunningState = mutableStateOf(false)
    private var ipAddressState = mutableStateOf("127.0.0.1")
    private var deviceNameState = mutableStateOf("")
    private var filesCountState = mutableStateOf(0)
    private var filesSizeState = mutableStateOf(0L)
    private var storagePermissionGrantedState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init device name
        deviceNameState.value = getSavedDeviceName()

        // Request storage permissions on start
        checkAndRequestStoragePermissions()

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Start and bind to the foreground service
        startServer()

        // Handle share intents
        handleShareIntent(intent)

        setContent {
            LANBeamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0C0C10) // Dark Slate background
                ) {
                    MainScreenLayout()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle share intents when activity is already running
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUIState()
    }

    override fun onDestroy() {
        // Unbind from service but DON'T stop it — service keeps running in background
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    handleIncomingFiles(listOf(uri))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    handleIncomingFiles(uris)
                }
            }
        }
    }

    private fun handleIncomingFiles(uris: List<Uri>) {
        // Ensure service is running
        if (lanBeamService?.isRunning != true) {
            startServer()
        }

        val sharedDir = lanBeamService?.server?.getSharedDir()
            ?: run {
                // Service might not be bound yet — post to copy after binding
                android.os.Handler(mainLooper).postDelayed({
                    val dir = lanBeamService?.server?.getSharedDir() ?: return@postDelayed
                    var count = 0
                    uris.forEach { uri ->
                        if (copyUriToFolder(this, uri, dir) != null) count++
                    }
                    if (count > 0) {
                        Toast.makeText(this, "Added $count file(s) to LAN Beam", Toast.LENGTH_SHORT).show()
                        lanBeamService?.wsServer?.broadcastFilesChanged()
                        refreshUIState()
                    }
                }, 1500)
                return
            }

        var count = 0
        uris.forEach { uri ->
            if (copyUriToFolder(this, uri, sharedDir) != null) count++
        }
        if (count > 0) {
            Toast.makeText(this, "Added $count file(s) to LAN Beam", Toast.LENGTH_SHORT).show()
            lanBeamService?.wsServer?.broadcastFilesChanged()
            refreshUIState()
        }
    }

    private fun startServer() {
        try {
            // Start the foreground service
            val serviceIntent = Intent(this, LanBeamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // Bind to it to get references
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            serverRunningState.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        try {
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }
            val serviceIntent = Intent(this, LanBeamService::class.java)
            stopService(serviceIntent)
            lanBeamService = null
            serverRunningState.value = false
            webView?.loadUrl("about:blank")
            Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun refreshUIState() {
        ipAddressState.value = lanBeamService?.server?.getLocalIpAddress() ?: "127.0.0.1"
        storagePermissionGrantedState.value = hasStoragePermission()

        val sharedDir = lanBeamService?.server?.getSharedDir()
        if (sharedDir != null && sharedDir.exists()) {
            val list = sharedDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            filesCountState.value = list.size
            var size = 0L
            list.forEach { file ->
                size += if (file.isFile) file.length() else getDirectorySize(file)
            }
            filesSizeState.value = size
        } else {
            filesCountState.value = 0
            filesSizeState.value = 0L
        }
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

    private fun getSavedDeviceName(): String {
        val prefs = getSharedPreferences("lanbeam_prefs", Context.MODE_PRIVATE)
        var name = prefs.getString("device_name", "") ?: ""
        if (name.isEmpty()) {
            name = "${Build.MANUFACTURER} ${Build.MODEL}"
            prefs.edit().putString("device_name", name).apply()
        }
        return name
    }

    private fun saveDeviceName(name: String) {
        val prefs = getSharedPreferences("lanbeam_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("device_name", name).apply()
        deviceNameState.value = name
    }

    private fun checkAndRequestStoragePermissions() {
        if (hasStoragePermission()) {
            storagePermissionGrantedState.value = true
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - we request in UI via button or dialog
        } else {
            // Android 10 and below
            legacyStoragePermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readPerm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            val writePerm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            checkSelfPermission(readPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(writePerm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun launchNativeFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        nativeFilePickerLauncher.launch(Intent.createChooser(intent, "Choose Files to Share"))
    }

    private fun clearSharedFiles() {
        val sharedDir = lanBeamService?.server?.getSharedDir()
        if (sharedDir != null && sharedDir.exists()) {
            val deleted = sharedDir.deleteRecursively()
            sharedDir.mkdirs() // Recreate root
            if (deleted) {
                Toast.makeText(this, "Cleared shared folder", Toast.LENGTH_SHORT).show()
                lanBeamService?.wsServer?.broadcastFilesChanged()
                refreshUIState()
            }
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun shareAppApk() {
        try {
            val srcApk = File(packageCodePath)
            val destApk = File(cacheDir, "LAN_Beam.apk")
            if (!destApk.exists() || destApk.length() != srcApk.length()) {
                srcApk.inputStream().use { input ->
                    destApk.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val uri = FileProvider.getUriForFile(this, "com.example.lanbeam.fileprovider", destApk)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share LAN Beam App via"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to share APK: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyUriToFolder(context: Context, uri: Uri, destFolder: File): File? {
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            var name = "picked_file"
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    }
                }
            }
            val destFile = File(destFolder, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return destFile
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreenLayout() {
        val serverRunning by serverRunningState
        val ipAddress by ipAddressState
        val deviceName by deviceNameState
        val filesCount by filesCountState
        val filesSize by filesSizeState
        val storagePermissionGranted by storagePermissionGrantedState

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LAN Beam",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF262630))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v" + getAppVersionName(),
                                color = Color(0xFF818CF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "Local Wi-Fi File Sharing",
                        color = Color(0xFF8888A0),
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = { shareAppApk() },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF16161E),
                        contentColor = Color(0xFF818CF8)
                    ),
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "Share APK")
                }
            }

            // Controls Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161E)),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(width = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    // Device Name Field
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { saveDeviceName(it) },
                            label = { Text("Device Name") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF818CF8),
                                unfocusedBorderColor = Color(0xFF262630),
                                focusedLabelColor = Color(0xFF818CF8),
                                unfocusedLabelColor = Color(0xFF8888A0)
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Start / Stop switch
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (serverRunning) Color(0x1A4ADE80)
                                        else Color(0x1AF87171)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (serverRunning) "● Live" else "○ Offline",
                                    color = if (serverRunning) Color(0xFF4ADE80) else Color(0xFFF87171),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Switch(
                                checked = serverRunning,
                                onCheckedChange = { if (it) startServer() else stopServer() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF818CF8),
                                    checkedTrackColor = Color(0xFF1E1E28),
                                    uncheckedThumbColor = Color(0xFF8888A0),
                                    uncheckedTrackColor = Color(0xFF1E1E28)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Connection Information
                    if (serverRunning) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A26)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("LAN Beam URL", "http://$ipAddress:${LanBeamService.HTTP_PORT}")
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(this@MainActivity, "URL Copied!", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Connect at",
                                        color = Color(0xFF8888A0),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "http://$ipAddress:${LanBeamService.HTTP_PORT}",
                                        color = Color(0xFF818CF8),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "📋",
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Shared Stats & Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Sharing: $filesCount file(s)",
                                color = Color(0xFFEEEEF2),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Total Size: ${formatSize(filesSize)}",
                                color = Color(0xFF8888A0),
                                fontSize = 11.sp
                            )
                        }

                        Row {
                            Button(
                                onClick = { launchNativeFilePicker() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF818CF8),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Add Files", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = { clearSharedFiles() },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = Color(0xFF262630),
                                    contentColor = Color(0xFFF87171)
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear Shared",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Permission warning for Android 11+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !storagePermissionGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0x26F87171)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Grant Full Storage permission to share any folder.",
                                    color = Color(0xFFF87171),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { requestAllFilesAccess() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("Grant", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // WebView Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0C0C10))
            ) {
                if (serverRunning) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                @Suppress("DEPRECATION")
                                settings.databaseEnabled = true

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        // Refresh the stats in compose when web activities happen
                                        refreshUIState()
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        fileCallback?.onReceiveValue(null)
                                        fileCallback = filePathCallback

                                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        }

                                        try {
                                            webViewFilePickerLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            fileCallback?.onReceiveValue(null)
                                            fileCallback = null
                                            return false
                                        }
                                        return true
                                    }
                                }

                                // DownloadListener — handles all file downloads from WebView
                                setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                                    try {
                                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                        val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                                            setMimeType(mimeType)
                                            addRequestHeader("User-Agent", userAgent)
                                            setTitle(fileName)
                                            setDescription("LAN Beam download")
                                            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LAN Beam/$fileName")
                                        }
                                        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                        dm.enqueue(request)
                                        Toast.makeText(ctx, "⬇ Downloading: $fileName", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                loadUrl("http://127.0.0.1:${LanBeamService.HTTP_PORT}/")
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            // URL is loaded on startup, stays in sync
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Server Offline",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Toggle the Live switch above to start the server and view your files.",
                            color = Color(0xFF8888A0),
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
