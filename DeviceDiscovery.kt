package com.syncbridge.android.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Discovers SyncBridge Windows hosts on the local network using
 * Android NSD (Network Service Discovery = Android's mDNS implementation).
 * No manual IP entry needed — just launch the app on both devices.
 */
class DeviceDiscovery(private val context: Context) {

    private val TAG = "DeviceDiscovery"
    private val SERVICE_TYPE = "_syncbridge._tcp."

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    data class DiscoveredHost(
        val name: String,
        val host: String,
        val port: Int
    )

    var onDeviceFound: ((DiscoveredHost) -> Unit)? = null
    var onDeviceLost: ((String) -> Unit)? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: $errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started for $serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
            nsdManager.resolveService(serviceInfo, makeResolveListener())
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            onDeviceLost?.invoke(serviceInfo.serviceName)
        }
    }

    fun startDiscovery() {
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Start discovery error: ${e.message}")
        }
    }

    fun stopDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Stop discovery error: ${e.message}")
        }
    }

    private fun makeResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed: $errorCode")
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = DiscoveredHost(
                name = serviceInfo.serviceName,
                host = serviceInfo.host.hostAddress ?: "",
                port = serviceInfo.port
            )
            Log.d(TAG, "Resolved: ${host.name} at ${host.host}:${host.port}")
            onDeviceFound?.invoke(host)
        }
    }

    // Also advertise ourselves so the PC can see the phone
    fun advertise(deviceName: String, port: Int = 37401) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SyncBridge-$deviceName"
            serviceType = SERVICE_TYPE
            this.port = port
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
                override fun onServiceRegistered(s: NsdServiceInfo) {
                    Log.d(TAG, "Advertised as ${s.serviceName}")
                }
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
            })
    }
}
