package com.syncbridge.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncbridge.android.services.SyncBridgeService

@Composable
fun SyncBridgeApp() {
    val service = SyncBridgeService.instance
    var isConnected by remember { mutableStateOf(false) }
    var connectedTo by remember { mutableStateOf("") }
    var cameraEnabled by remember { mutableStateOf(false) }
    var callEnabled by remember { mutableStateOf(false) }
    var screenMirrorEnabled by remember { mutableStateOf(false) }
    var clipboardEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(service) {
        service?.client?.onConnected = {
            isConnected = true
        }
        service?.client?.onDisconnected = {
            isConnected = false
            cameraEnabled = false
            callEnabled = false
            screenMirrorEnabled = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Header ─────────────────────────────────────────────────────────────
        Surface(tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SyncBridge", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isConnected) "Connected to $connectedTo" else "Searching for PC...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isConnected) Color(0xFF22C55E) else Color(0xFFF59E0B))
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Connection status card ──────────────────────────────────────────
            item {
                StatusCard(isConnected = isConnected, connectedTo = connectedTo)
            }

            // ── Feature toggles ────────────────────────────────────────────────
            item {
                Text(
                    "Features",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            item {
                FeatureCard(
                    icon = Icons.Default.Videocam,
                    title = "Camera as Webcam",
                    subtitle = "Stream this camera to PC",
                    enabled = cameraEnabled && isConnected,
                    checked = cameraEnabled,
                    onToggle = { on ->
                        cameraEnabled = on
                        if (on) service?.cameraStream?.startStream()
                        else service?.cameraStream?.stopStream()
                    }
                )
            }

            item {
                FeatureCard(
                    icon = Icons.Default.Call,
                    title = "Call Transfer",
                    subtitle = "Route calls to PC speakers/mic",
                    enabled = isConnected,
                    checked = callEnabled,
                    onToggle = { on ->
                        callEnabled = on
                        if (on) service?.callTransfer?.startCallTransfer()
                        else service?.callTransfer?.stopCallTransfer()
                    }
                )
            }

            item {
                FeatureCard(
                    icon = Icons.Default.ScreenShare,
                    title = "Screen Mirror",
                    subtitle = "Show phone screen on PC",
                    enabled = isConnected,
                    checked = screenMirrorEnabled,
                    onToggle = { on ->
                        screenMirrorEnabled = on
                        // Triggers MediaProjection permission request in MainActivity
                        // ScreenCaptureService.requestPermission(activity)
                    }
                )
            }

            item {
                FeatureCard(
                    icon = Icons.Default.ContentPaste,
                    title = "Universal Clipboard",
                    subtitle = "Sync copy/paste across devices",
                    enabled = isConnected,
                    checked = clipboardEnabled,
                    onToggle = { on ->
                        clipboardEnabled = on
                        if (on) service?.clipboardSync?.start()
                        else service?.clipboardSync?.stop()
                    }
                )
            }

            item {
                FeatureCard(
                    icon = Icons.Default.Keyboard,
                    title = "Phone as Keyboard",
                    subtitle = "Type here to input on PC",
                    enabled = isConnected,
                    checked = false,
                    onToggle = { }
                )
            }

            // ── Keyboard input area ────────────────────────────────────────────
            if (isConnected) {
                item {
                    KeyboardInputCard(service = service)
                }
            }
        }
    }
}

@Composable
fun StatusCard(isConnected: Boolean, connectedTo: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (isConnected) Icons.Default.CheckCircle else Icons.Default.Search,
                contentDescription = null,
                tint = if (isConnected) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    if (isConnected) "Connected" else "Searching...",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (isConnected) "All features available" else "Make sure PC app is running on the same network",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled
            )
        }
    }
}

@Composable
fun KeyboardInputCard(service: SyncBridgeService?) {
    var inputText by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Keyboard Input", fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = inputText,
                onValueChange = { newVal ->
                    // Send each new character to PC
                    val diff = newVal.drop(inputText.length)
                    if (diff.isNotEmpty()) {
                        diff.forEach { char ->
                            // Send unicode char as key event
                            service?.scope?.let { scope ->
                                kotlinx.coroutines.GlobalScope.launch {
                                    service.client.send(
                                        com.syncbridge.android.core.SyncMessage(
                                            type = com.syncbridge.android.core.MessageType.KeyInput,
                                            payload = com.google.gson.JsonObject().apply {
                                                addProperty("action", "down")
                                                addProperty("unicode", char.toString())
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                    inputText = newVal
                },
                placeholder = { Text("Type here to input on PC...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    service?.scope?.let {
                        kotlinx.coroutines.GlobalScope.launch {
                            service.client.send(com.syncbridge.android.core.SyncMessage(
                                type = com.syncbridge.android.core.MessageType.KeyInput,
                                payload = com.google.gson.JsonObject().apply {
                                    addProperty("action", "down")
                                    addProperty("keyCode", 13)  // Enter
                                }
                            ))
                        }
                    }
                }) { Text("↵ Enter") }

                OutlinedButton(onClick = {
                    service?.scope?.let {
                        kotlinx.coroutines.GlobalScope.launch {
                            service.client.send(com.syncbridge.android.core.SyncMessage(
                                type = com.syncbridge.android.core.MessageType.KeyInput,
                                payload = com.google.gson.JsonObject().apply {
                                    addProperty("action", "down")
                                    addProperty("keyCode", 8)  // Backspace
                                }
                            ))
                        }
                    }
                }) { Text("⌫ Back") }

                OutlinedButton(onClick = { inputText = "" }) { Text("Clear") }
            }
        }
    }
}
