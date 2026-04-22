package com.syncbridge.android.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.syncbridge.android.core.MessageType
import com.syncbridge.android.core.SyncBridgeClient
import com.syncbridge.android.core.SyncMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Watches Android clipboard and syncs to PC.
 * Also receives clipboard updates from PC and sets them on Android.
 */
class ClipboardSyncService(
    private val context: Context,
    private val client: SyncBridgeClient,
    private val scope: CoroutineScope
) {
    private val TAG = "ClipboardSync"
    private var lastHash = ""

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    fun start() {
        clipboardManager.addPrimaryClipChangedListener(listener)
        Log.d(TAG, "Clipboard sync started")
    }

    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(listener)
    }

    private fun onClipboardChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val item = clip.getItemAt(0)
        val text = item.text?.toString()

        if (!text.isNullOrEmpty()) {
            val hash = sha256(text).take(16)
            if (hash == lastHash) return
            lastHash = hash

            scope.launch {
                client.send(SyncMessage(
                    type = MessageType.ClipboardSync,
                    payload = JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("content", text)
                    }
                ))
            }
            Log.d(TAG, "Clipboard text synced (${text.length} chars)")
        }
    }

    /**
     * Handles incoming clipboard update from PC.
     */
    fun handleIncoming(payload: JsonObject) {
        val type = payload["type"]?.asString ?: return
        val content = payload["content"]?.asString ?: return

        when (type) {
            "text" -> {
                val hash = sha256(content).take(16)
                lastHash = hash
                val clip = ClipData.newPlainText("SyncBridge", content)
                clipboardManager.setPrimaryClip(clip)
                Log.d(TAG, "Clipboard received from PC: ${content.take(50)}")
            }
            "image" -> {
                // Android clipboard images require content provider — store as file and paste URI
                try {
                    val bytes = Base64.decode(content, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    // Save to cache and create content URI
                    val file = java.io.File(context.cacheDir, "syncbridge_clip.png")
                    file.outputStream().use {
                        bmp?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.provider", file
                    )
                    val clip = ClipData.newUri(context.contentResolver, "Image", uri)
                    clipboardManager.setPrimaryClip(clip)
                } catch (e: Exception) {
                    Log.e(TAG, "Image clipboard error: ${e.message}")
                }
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
