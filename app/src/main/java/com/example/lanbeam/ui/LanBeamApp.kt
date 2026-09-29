package com.example.lanbeam.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.lanbeam.Format
import com.example.lanbeam.LanBeamState
import com.example.lanbeam.Qr
import com.example.lanbeam.server.LanAddresses
import com.example.lanbeam.server.TransferTracker

/** Everything the screen can ask the host activity to do (intents, pickers, settings). */
interface AppActions {
    fun pickFiles()
    fun openFile(item: FileItem)
    fun shareFile(item: FileItem)
    fun copyText(text: String)
    fun shareText(text: String)
    fun shareApp()
    fun requestFileAccess()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanBeamApp(
    status: LanBeamState.Status,
    deviceName: String,
    transfers: List<TransferTracker.Transfer>,
    shared: List<FileItem>,
    received: List<FileItem>,
    needsFileAccessPrompt: Boolean,
    receivedLocation: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onDelete: (FileItem) -> Unit,
    onClearShared: () -> Unit,
    onClearTransfers: () -> Unit,
    onRename: (String) -> Unit,
    actions: AppActions,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<FileItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLogo(36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("LAN Beam", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                deviceName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    Switch(
                        checked = status.running,
                        onCheckedChange = { if (it) onStart() else onStop() },
                        modifier = Modifier.semantics { contentDescription = if (status.running) "Stop sharing" else "Start sharing" },
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename this device") }, onClick = { menuOpen = false; showRename = true })
                            DropdownMenuItem(text = { Text("Trouble connecting?") }, onClick = { menuOpen = false; showHelp = true })
                            DropdownMenuItem(text = { Text("Send LAN Beam app") }, onClick = { menuOpen = false; actions.shareApp() })
                            if (needsFileAccessPrompt) {
                                DropdownMenuItem(text = { Text("Allow saving to Downloads") }, onClick = { menuOpen = false; actions.requestFileAccess() })
                            }
                            if (shared.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Stop sharing all files") }, onClick = { menuOpen = false; confirmClear = true })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = actions::pickFiles,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add files") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "connect") {
                if (status.running) {
                    ConnectCard(status, onRefreshNetwork, actions, onHelp = { showHelp = true })
                } else {
                    OfflineCard(status.error, onStart)
                }
            }
            val visibleTransfers = transfers.take(6)
            if (visibleTransfers.isNotEmpty()) {
                item(key = "transfers") { TransfersCard(visibleTransfers, onClearTransfers) }
            }
            item(key = "tabs") {
                SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sharing · ${shared.size}") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Received · ${received.size}") })
                }
            }
            if (tab == 1 && needsFileAccessPrompt) {
                item(key = "perm") { StorageNotice(actions::requestFileAccess) }
            }
            if (tab == 1 && received.isNotEmpty()) {
                item(key = "where") {
                    Text(
                        "Saved in $receivedLocation · tap a file to open it",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            val list = if (tab == 0) shared else received
            if (list.isEmpty()) {
                item(key = "empty$tab") {
                    EmptyState(
                        if (tab == 0) "Nothing shared yet" else "Nothing received yet",
                        if (tab == 0) "Add files here, or share them to LAN Beam from any app. Devices that open the link can download them."
                        else "Files other devices send from their browser are saved to $receivedLocation and appear here.",
                    )
                }
            } else {
                items(list, key = { "${tab}:${it.file.absolutePath}" }) { item ->
                    FileRow(
                        item = item,
                        onClick = { actions.openFile(item) },
                        trailing = {
                            if (tab == 0) {
                                IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Close, contentDescription = "Stop sharing ${item.name}") }
                            } else {
                                TextButton(onClick = { actions.openFile(item) }) { Text("Open") }
                                IconButton(onClick = { actions.shareFile(item) }) { Icon(Icons.Default.Share, contentDescription = "Share or save ${item.name}") }
                                IconButton(onClick = { confirmDelete = item }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${item.name}") }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showHelp) HelpSheet(onDismiss = { showHelp = false })
    if (showRename) RenameDialog(deviceName, onDismiss = { showRename = false }, onSave = { onRename(it); showRename = false })
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("${item.name} will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(item); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Stop sharing all files?") },
            text = { Text("The copies LAN Beam made are removed. Your original files are not touched.") },
            confirmButton = { TextButton(onClick = { onClearShared(); confirmClear = false }) { Text("Stop sharing") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectCard(status: LanBeamState.Status, onRefresh: () -> Unit, actions: AppActions, onHelp: () -> Unit) {
    var selectedIp by rememberSaveable { mutableStateOf<String?>(null) }
    val addr = status.addresses.firstOrNull { it.ip == selectedIp } ?: status.addresses.firstOrNull()
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (addr == null) {
                Text("Not on a network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mobile data can't be used for this. Connect to Wi-Fi or turn on your hotspot, then tap refresh.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                return@Column
            }
            val url = "http://${addr.ip}:${status.port}"
            val qr = remember(url) { Qr.bitmap(url, 480).asImageBitmap() }
            Text(
                "Scan with the other device's camera",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(208.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
            ) {
                Image(qr, contentDescription = "QR code for $url", modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Text("or open in a browser", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                url, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { actions.copyText(url) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.copyText(url) }) { Text("Copy link") }
                FilledTonalButton(onClick = { actions.shareText(url) }) { Text("Share link") }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    if (status.addresses.size > 1) {
                        // Labels only, so nothing is cut off; the full link is shown above.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            status.addresses.distinctBy { it.kind }.forEach { a ->
                                FilterChip(selected = a.kind == addr.kind, onClick = { selectedIp = a.ip }, label = { Text(a.kind.label) })
                            }
                        }
                    }
                    Text(
                        audienceOf(addr.kind), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh network address") }
            }
            TextButton(onClick = onHelp) { Text("Other device can't connect?") }
        }
    }
}

private fun audienceOf(kind: LanAddresses.Kind) = when (kind) {
    LanAddresses.Kind.WIFI -> "For devices on the same Wi-Fi as this phone"
    LanAddresses.Kind.HOTSPOT -> "For devices connected to this phone's hotspot"
    LanAddresses.Kind.ETHERNET -> "For devices on the same wired network"
    LanAddresses.Kind.OTHER -> "For devices on the same local network"
}

@Composable
private fun OfflineCard(error: String?, onStart: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sharing is off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                error ?: "Turn it on to let devices on your Wi-Fi open this phone in their browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStart) { Text("Start sharing") }
        }
    }
}

@Composable
private fun TransfersCard(transfers: List<TransferTracker.Transfer>, onClear: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transfers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (transfers.any { it.finishedAt != null }) TextButton(onClick = onClear) { Text("Clear") }
            }
            transfers.forEach { TransferRow(it) }
        }
    }
}

@Composable
private fun TransferRow(t: TransferTracker.Transfer) {
    val verb = when (t.direction) {
        TransferTracker.Direction.DOWNLOAD -> "Sending to ${t.peer}"
        TransferTracker.Direction.UPLOAD -> "Receiving from ${t.peer}"
        TransferTracker.Direction.IMPORT -> "Adding to shared files"
    }
    val detail = when {
        t.finishedAt != null && t.failed -> "Interrupted at ${Format.size(t.bytes)}"
        t.finishedAt != null -> "Done · ${Format.size(t.bytes)} at ${Format.size(t.bytesPerSec.toLong())}/s"
        else -> buildString {
            append(Format.size(t.bytes))
            if (t.total > 0) append(" of ${Format.size(t.total)}")
            if (t.bytesPerSec > 0) {
                append(" · ${Format.size(t.bytesPerSec.toLong())}/s")
                if (t.total > 0) append(" · ${Format.duration(((t.total - t.bytes) / t.bytesPerSec).toLong().coerceAtLeast(0))} left")
            }
        }
    }
    Column {
        Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(verb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        if (t.finishedAt == null && t.total <= 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { if (t.finishedAt != null && !t.failed) 1f else t.progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (t.failed) MaterialTheme.colorScheme.error else if (t.finishedAt != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageNotice(onAllow: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Received files are kept in app storage. Allow storage access to save them in Download/LANBeam.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileRow(item: FileItem, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(item)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val age = DateUtils.getRelativeTimeSpanString(item.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            Text(
                if (item.type == "folder") "Folder · $age" else "${Format.size(item.size)} · $age",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row { trailing() }
    }
}

@Composable
private fun TypeBadge(item: FileItem) {
    val label = if (item.type == "folder") "DIR" else item.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }
    val bg = when (item.type) {
        "video" -> 0xFFEF4444; "image" -> 0xFF10B981; "audio" -> 0xFFF59E0B; "pdf" -> 0xFFDC2626
        "archive" -> 0xFF8B5CF6; "apk" -> 0xFF22C55E; "folder" -> 0xFF3B82F6; else -> 0xFF64748B
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(bg).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(bg))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("If the other device can't open the link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HelpItem("Same network", "Both devices must be on the same Wi-Fi (or the other device on this phone's hotspot). Mobile data does not work.")
            HelpItem("Type it exactly", "Include http:// and :8765. Some browsers switch to https:// - change it back.")
            HelpItem("Try each address", "If the card shows more than one address, pick another chip and try again.")
            HelpItem(
                "Works on hotspot but not on your router?",
                "Your router is blocking devices from talking to each other. Look for \"AP isolation\", \"client isolation\" or \"guest network\" in its settings and turn it off, or put both devices on the main (not guest) network. Using this phone's hotspot always works.",
            )
            HelpItem("VPN", "A VPN on either device can block local traffic. Pause it while transferring.")
        }
    }
}

@Composable
private fun HelpItem(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device name") },
        text = {
            Column {
                Text("Shown to devices that connect, so they know they have the right phone.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
