package com.example.lanbeam.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.lanbeam.R

/** The launcher icon, drawn from its two vector layers (adaptive mipmaps can't be painted directly). */
@Composable
fun AppLogo(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
        // Foreground art lives in the middle 66% of its canvas; scale it up to fill the tile.
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = "LAN Beam",
            modifier = Modifier.fillMaxSize().scale(1.45f),
        )
    }
}

data class PermissionInfo(val title: String, val why: String, val prompted: Boolean) {
    companion object {
        /** Exactly what this device will be asked, and what is granted automatically. */
        fun forThisDevice(): List<PermissionInfo> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionInfo("Notifications", "Shows transfer progress and a Stop button while sharing runs in the background.", true))
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                add(PermissionInfo("Storage", "Saves files other devices send you into Download/LANBeam.", true))
            }
            add(PermissionInfo("Your files", "Only the photos, videos and files you pick in Android's own pickers or share to LAN Beam. It never asks to read all your media or storage.", false))
            add(PermissionInfo("Local network", "Lets devices on your Wi-Fi or hotspot open this phone in their browser. Granted automatically; nothing is sent to the internet.", false))
            add(PermissionInfo("Stay awake while transferring", "Keeps Wi-Fi at full speed while files are moving, then lets the phone sleep. Granted automatically.", false))
        }
    }
}

/**
 * First launch. Content scrolls if it must, but the action buttons are pinned to the bottom so
 * they are always on screen; Surface supplies onBackground as the content colour (plain Text on a
 * bare background defaulted to black, invisible in dark mode).
 */
@Composable
fun WelcomeScreen(permissions: List<PermissionInfo>, onContinue: () -> Unit, onSkip: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppLogo(64.dp)
                Spacer(Modifier.height(14.dp))
                Text("LAN Beam", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Move files between this phone and any device on your Wi-Fi or hotspot. The other device only needs a browser.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("What LAN Beam asks for", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        permissions.forEach { p ->
                            Column {
                                Text(
                                    p.title + if (p.prompted) "" else "  ·  no prompt",
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                )
                                Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (permissions.any { it.prompted }) "Allow and start" else "Start")
                }
                if (permissions.any { it.prompted }) {
                    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                }
            }
        }
    }
}
