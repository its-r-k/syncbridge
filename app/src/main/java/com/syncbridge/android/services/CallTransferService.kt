package com.syncbridge.android.services

import android.content.Context
import android.media.*
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.syncbridge.android.core.MessageType
import com.syncbridge.android.core.SyncBridgeClient
import com.syncbridge.android.core.SyncMessage
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Routes active phone call audio (WhatsApp, Skype, cellular, etc.) to the PC.
 *
 * Strategy:
 * 1. Use AudioRecord with VOICE_COMMUNICATION source to capture call audio.
 *    On Android 10+, VOICE_CALL source may also capture call downlink (requires MODIFY_AUDIO_SETTINGS).
 * 2. Use AudioTrack to play PC mic audio back into the call.
 * 3. For WhatsApp/Skype: use Accessibility Service to detect active calls
 *    and prompt the user to route audio via Bluetooth/SyncBridge.
 *
 * Note: Full duplex call audio capture requires the CAPTURE_AUDIO_OUTPUT permission
 * which is only available to system apps or via adb. The workaround for user-space
 * apps is to use Android's built-in Bluetooth audio routing: the app registers
 * as a Bluetooth headset and the phone routes call audio to it.
 */
class CallTransferService(
    private val context: Context,
    private val client: SyncBridgeClient,
    private val scope: CoroutineScope
) {
    private val TAG = "CallTransfer"

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var active = false

    fun startCallTransfer() {
        if (active) return
        active = true

        // Configure audio session for call routing
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        startCapture()
        startPlayback()

        Log.d(TAG, "Call transfer started")

        // Notify PC
        scope.launch {
            client.send(SyncMessage(
                type = MessageType.CallTransfer,
                payload = JsonObject().apply {
                    addProperty("action", "start")
                    addProperty("sampleRate", SAMPLE_RATE)
                }
            ))
        }
    }

    private fun startCapture() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE
        )
        audioRecord?.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            while (active && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val chunk = Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP)
                    client.send(SyncMessage(
                        type = MessageType.CallTransfer,
                        payload = JsonObject().apply {
                            addProperty("action", "chunk")
                            addProperty("data", chunk)
                            addProperty("sampleRate", SAMPLE_RATE)
                        }
                    ))
                }
            }
        }
    }

    private fun startPlayback() {
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(BUFFER_SIZE)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    /**
     * Called when an audio chunk arrives from the PC (PC mic → phone call).
     */
    fun playIncomingChunk(base64Data: String) {
        val pcm = Base64.decode(base64Data, Base64.DEFAULT)
        audioTrack?.write(pcm, 0, pcm.size)
    }

    fun stopCallTransfer() {
        active = false
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL

        scope.launch {
            client.send(SyncMessage(
                type = MessageType.CallTransfer,
                payload = JsonObject().apply { addProperty("action", "stop") }
            ))
        }

        Log.d(TAG, "Call transfer stopped")
    }
}
