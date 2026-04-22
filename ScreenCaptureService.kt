package com.syncbridge.android.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.syncbridge.android.core.MessageType
import com.syncbridge.android.core.SyncBridgeClient
import com.syncbridge.android.core.SyncMessage
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Captures the Android screen using MediaProjection API and streams
 * JPEG frames to the connected PC over the SyncBridge tunnel.
 *
 * User must grant screen capture permission via system dialog (one-time per session).
 * Call requestScreenCapturePermission() from an Activity first.
 */
class ScreenCaptureService(
    private val context: Context,
    private val client: SyncBridgeClient,
    private val scope: CoroutineScope
) {
    private val TAG = "ScreenCaptureService"
    private val TARGET_FPS = 30
    private val JPEG_QUALITY = 65

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var captureJob: Job? = null
    private var streaming = false

    private val displayMetrics = context.resources.displayMetrics
    private val screenWidth = displayMetrics.widthPixels
    private val screenHeight = displayMetrics.heightPixels
    private val screenDensity = displayMetrics.densityDpi

    fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, null)

        // Stream at half resolution to reduce bandwidth
        val captureWidth = screenWidth / 2
        val captureHeight = screenHeight / 2

        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            PixelFormat.RGBA_8888, 2
        )

        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        val handler = Handler(handlerThread!!.looper)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SyncBridgeMirror",
            captureWidth, captureHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        streaming = true
        startStreamLoop(captureWidth, captureHeight)
        Log.d(TAG, "Screen capture started at ${captureWidth}x${captureHeight}")
    }

    private fun startStreamLoop(width: Int, height: Int) {
        captureJob = scope.launch(Dispatchers.IO) {
            val intervalMs = 1000L / TARGET_FPS
            while (streaming && isActive) {
                val start = System.currentTimeMillis()

                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bmp = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height,
                            Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        image.close()

                        // Compress to JPEG
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                        bmp.recycle()

                        val frameData = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                        val payload = JsonObject().apply {
                            addProperty("data", frameData)
                            addProperty("width", width)
                            addProperty("height", height)
                            addProperty("timestamp", System.currentTimeMillis())
                        }

                        client.send(SyncMessage(
                            type = MessageType.ScreenFrame,
                            payload = payload
                        ))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame error: ${e.message}")
                }

                val elapsed = System.currentTimeMillis() - start
                val delay = intervalMs - elapsed
                if (delay > 0) delay(delay)
            }
        }
    }

    fun stopCapture() {
        streaming = false
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        Log.d(TAG, "Screen capture stopped")
    }

    companion object {
        const val REQUEST_CODE = 1001

        fun requestPermission(activity: Activity) {
            val pm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            activity.startActivityForResult(
                pm.createScreenCaptureIntent(),
                REQUEST_CODE
            )
        }
    }
}
