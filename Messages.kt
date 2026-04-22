package com.syncbridge.android.core

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

enum class MessageType {
    Hello, SessionKey, Ack, Ping, Pong,
    KeyInput, MouseInput, TouchInput,
    ClipboardSync,
    CameraFrame, CameraStart, CameraStop,
    AudioChunk, CallTransfer,
    ScreenFrame, ScreenRequest, ScreenAck,
    FileChunk, FileAck, FileMeta,
    DeviceInfo, Disconnect, Error
}

data class SyncMessage(
    @SerializedName("type") val type: MessageType,
    @SerializedName("payload") val payload: JsonElement?,
    @SerializedName("ts") val timestamp: Long = System.currentTimeMillis()
)
