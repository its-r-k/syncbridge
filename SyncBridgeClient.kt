package com.syncbridge.android.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Connects to the SyncBridge Windows server.
 * Handles: RSA key exchange → AES encrypted tunnel → message loop.
 */
class SyncBridgeClient(
    private val scope: CoroutineScope
) {
    private val TAG = "SyncBridgeClient"
    private val gson = Gson()

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var aesKey: ByteArray? = null
    private var connected = false

    private val _messages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SyncMessage> = _messages

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // RSA key pair for handshake
    private val keyPair = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()

    suspend fun connect(host: String, port: Int = 37400): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                socket = Socket(host, port)
                input = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())

                // Step 1: Send Hello with our RSA public key
                sendRaw(SyncMessage(
                    type = MessageType.Hello,
                    payload = gson.toJsonTree(mapOf(
                        "deviceId" to android.provider.Settings.Secure.getString(
                            null, android.provider.Settings.Secure.ANDROID_ID),
                        "deviceName" to android.os.Build.MODEL,
                        "platform" to "android",
                        "publicKey" to encodePublicKeyToPem(keyPair.public),
                        "version" to "1.0"
                    ))
                ))

                // Step 2: Receive session key (AES key encrypted with our RSA public key)
                val sessionMsg = readRaw()
                if (sessionMsg?.type == MessageType.SessionKey) {
                    val encryptedKey = android.util.Base64.decode(
                        sessionMsg.payload?.get("key")?.asString ?: "",
                        android.util.Base64.DEFAULT
                    )
                    aesKey = decryptWithRSA(encryptedKey, keyPair.private)
                }

                connected = true
                onConnected?.invoke()
                Log.d(TAG, "Connected to $host:$port")

                // Start message read loop
                scope.launch(Dispatchers.IO) { readLoop() }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                false
            }
        }
    }

    private suspend fun readLoop() {
        while (connected && socket?.isConnected == true) {
            try {
                val msg = readEncrypted() ?: break
                _messages.emit(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Read error: ${e.message}")
                break
            }
        }
        connected = false
        onDisconnected?.invoke()
    }

    suspend fun send(message: SyncMessage) {
        withContext(Dispatchers.IO) {
            try {
                sendEncrypted(message)
            } catch (e: Exception) {
                Log.e(TAG, "Send error: ${e.message}")
            }
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────

    private fun sendRaw(msg: SyncMessage) {
        val json = gson.toJson(msg).toByteArray(Charsets.UTF_8)
        output?.writeInt(json.size)
        output?.write(json)
        output?.flush()
    }

    private fun readRaw(): SyncMessage? {
        val len = input?.readInt() ?: return null
        val buf = ByteArray(len)
        input?.readFully(buf)
        return gson.fromJson(String(buf, Charsets.UTF_8), SyncMessage::class.java)
    }

    private fun sendEncrypted(msg: SyncMessage) {
        val key = aesKey ?: run { sendRaw(msg); return }
        val plain = gson.toJson(msg).toByteArray(Charsets.UTF_8)
        val cipher = aesEncrypt(plain, key)
        output?.writeInt(cipher.size)
        output?.write(cipher)
        output?.flush()
    }

    private fun readEncrypted(): SyncMessage? {
        val key = aesKey ?: return readRaw()
        val len = input?.readInt() ?: return null
        val cipher = ByteArray(len)
        input?.readFully(cipher)
        val plain = aesDecrypt(cipher, key)
        return gson.fromJson(String(plain, Charsets.UTF_8), SyncMessage::class.java)
    }

    // ── Crypto ────────────────────────────────────────────────────────────

    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, 16)
        val cipher = data.copyOfRange(16, data.size)
        val secretKey = SecretKeySpec(key, "AES")
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return c.doFinal(cipher)
    }

    private fun decryptWithRSA(data: ByteArray, privateKey: java.security.PrivateKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

    private fun encodePublicKeyToPem(key: PublicKey): String {
        val b64 = android.util.Base64.encodeToString(key.encoded, android.util.Base64.DEFAULT)
        return "-----BEGIN PUBLIC KEY-----\n$b64-----END PUBLIC KEY-----"
    }

    fun disconnect() {
        connected = false
        socket?.close()
    }

    val isConnected get() = connected
}
