package com.syncbridge.android.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import com.google.gson.JsonObject
import com.syncbridge.android.core.MessageType
import com.syncbridge.android.core.SyncBridgeClient
import com.syncbridge.android.core.SyncMessage
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Streams the phone's rear (or front) camera to the PC as JPEG frames.
 * PC receives them and routes through OBS Virtual Camera.
 * Target: 1280x720 @ 30fps with hardware JPEG compression.
 */
class CameraStreamService(
    private val context: Context,
    private val client: SyncBridgeClient,
    private val scope: CoroutineScope
) {
    private val TAG = "CameraStream"
    private val TARGET_WIDTH = 1280
    private val TARGET_HEIGHT = 720
    private val TARGET_FPS = 30
    private val JPEG_QUALITY = 80

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var streaming = false
    private var useBackCamera = true

    @SuppressLint("MissingPermission")
    fun startStream(useFront: Boolean = false) {
        useBackCamera = !useFront
        handlerThread = HandlerThread("CameraStream").also { it.start() }
        val handler = Handler(handlerThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = getCameraId(cameraManager, useFront)

        imageReader = ImageReader.newInstance(
            TARGET_WIDTH, TARGET_HEIGHT, ImageFormat.JPEG, 3
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val payload = JsonObject().apply {
                    addProperty("data", b64)
                    addProperty("width", TARGET_WIDTH)
                    addProperty("height", TARGET_HEIGHT)
                    addProperty("codec", "jpeg")
                }

                scope.launch(Dispatchers.IO) {
                    client.send(SyncMessage(type = MessageType.CameraFrame, payload = payload))
                }
            } finally {
                image.close()
            }
        }, handler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCapture(camera, handler)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
            }
        }, handler)

        streaming = true
    }

    private fun startCapture(camera: CameraDevice, handler: Handler) {
        val surfaces = listOf(imageReader!!.surface)

        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session

                val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD
                ).apply {
                    addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(TARGET_FPS, TARGET_FPS))
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                }

                session.setRepeatingRequest(captureRequest.build(), null, handler)
                Log.d(TAG, "Camera streaming started")
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Camera session config failed")
            }
        }, handler)
    }

    private fun getCameraId(manager: CameraManager, useFront: Boolean): String {
        val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return manager.cameraIdList.first()
    }

    fun switchCamera() {
        stopStream()
        startStream(!useBackCamera)
    }

    fun stopStream() {
        streaming = false
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        handlerThread?.quitSafely()
        Log.d(TAG, "Camera stream stopped")
    }
}
