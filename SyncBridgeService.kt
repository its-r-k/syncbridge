package com.syncbridge.android.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.syncbridge.android.MainActivity
import com.syncbridge.android.R
import com.syncbridge.android.core.DeviceDiscovery
import com.syncbridge.android.core.MessageType
import com.syncbridge.android.core.SyncBridgeClient
import kotlinx.coroutines.*

/**
 * Foreground service that keeps all SyncBridge features running
 * even when the app is in the background. Shows a persistent notification.
 */
class SyncBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "syncbridge_channel"
        const val NOTIFICATION_ID = 1
        var instance: SyncBridgeService? = null
    }

    private val TAG = "SyncBridgeService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var client: SyncBridgeClient
    lateinit var discovery: DeviceDiscovery
    lateinit var clipboardSync: ClipboardSyncService
    lateinit var callTransfer: CallTransferService
    lateinit var cameraStream: CameraStreamService
    lateinit var screenCapture: ScreenCaptureService

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        client = SyncBridgeClient(scope)
        discovery = DeviceDiscovery(this)
        clipboardSync = ClipboardSyncService(this, client, scope)
        callTransfer = CallTransferService(this, client, scope)
        cameraStream = CameraStreamService(this, client, scope)
        screenCapture = ScreenCaptureService(this, client, scope)

        // Start auto-discovery and connect to first found host
        discovery.onDeviceFound = { host ->
            Log.d(TAG, "Auto-connecting to ${host.name} @ ${host.host}:${host.port}")
            scope.launch {
                val ok = client.connect(host.host, host.port)
                if (ok) {
                    clipboardSync.start()
                    updateNotification("Connected to ${host.name}")
                }
            }
        }
        discovery.startDiscovery()
        discovery.advertise(android.os.Build.MODEL)

        // Handle incoming messages
        scope.launch {
            client.messages.collect { msg ->
                handleMessage(msg)
            }
        }

        client.onDisconnected = {
            updateNotification("Disconnected — searching...")
            discovery.startDiscovery()
        }
    }

    private fun handleMessage(msg: com.syncbridge.android.core.SyncMessage) {
        when (msg.type) {
            MessageType.ClipboardSync -> {
                val payload = msg.payload?.asJsonObject ?: return
                clipboardSync.handleIncoming(payload)
            }
            MessageType.CallTransfer -> {
                val payload = msg.payload?.asJsonObject ?: return
                val action = payload["action"]?.asString
                if (action == "chunk")
                    callTransfer.playIncomingChunk(payload["data"]?.asString ?: "")
            }
            MessageType.ScreenRequest -> {
                // PC wants us to start screen sharing
                val payload = msg.payload?.asJsonObject ?: return
                if (payload["action"]?.asString == "startMirror") {
                    // Send intent to MainActivity to request MediaProjection permission
                    val intent = Intent("com.syncbridge.REQUEST_SCREEN_CAPTURE")
                    sendBroadcast(intent)
                }
            }
            MessageType.CameraStart -> {
                cameraStream.startStream()
            }
            MessageType.CameraStop -> {
                cameraStream.stopStream()
            }
            MessageType.Ping -> {
                scope.launch {
                    client.send(com.syncbridge.android.core.SyncMessage(
                        type = MessageType.Pong, payload = null
                    ))
                }
            }
            else -> {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Searching for PC..."))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        clipboardSync.stop()
        cameraStream.stopStream()
        discovery.stopDiscovery()
        client.disconnect()
        instance = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SyncBridge", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SyncBridge background connection"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncBridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
