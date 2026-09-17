package com.phonlyrics.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

data class DiscoveredMac(val name: String, val host: String, val port: Int, val stableId: String?, val pairing: Boolean)

class MacDiscoveryManager(context: Context, private val onFound: (DiscoveredMac) -> Unit,
                          private val onError: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("phone-lyrics-mdns")?.apply { setReferenceCounted(false) }
    private var discovering = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { discovering = true }
        override fun onServiceFound(service: NsdServiceInfo) {
            if (!service.serviceType.startsWith("_phonelyrics._tcp")) return
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val attrs = info.attributes
                    onFound(DiscoveredMac(info.serviceName, host, info.port,
                        attrs["id"]?.toString(Charsets.UTF_8),
                        attrs["pairing"]?.toString(Charsets.UTF_8) == "1"))
                }
            })
        }
        override fun onServiceLost(service: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(type: String) { discovering = false }
        override fun onStartDiscoveryFailed(type: String, code: Int) { onError("自动发现启动失败 ($code)"); stop() }
        override fun onStopDiscoveryFailed(type: String, code: Int) { discovering = false }
    }

    fun start() {
        if (discovering) return
        runCatching { lock?.acquire() }
        nsd.discoverServices("_phonelyrics._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (discovering) runCatching { nsd.stopServiceDiscovery(listener) }
        discovering = false
        if (lock?.isHeld == true) lock.release()
    }
}
