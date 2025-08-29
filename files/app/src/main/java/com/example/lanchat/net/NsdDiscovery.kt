package com.example.lanchat.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Peer(val name: String, val host: String, val port: Int)

class NsdDiscovery(ctx: Context, private val serviceName: String, private val portProvider: () -> Int) {
    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers = _peers.asStateFlow()

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val p = serviceInfo.port
            val name = serviceInfo.serviceName
            _peers.value = (_peers.value + Peer(name, host, p)).distinctBy { it.name }
        }
    }

    fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = serviceName
            serviceType = "_lanonly._tcp."
            port = portProvider()
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
    }

    fun startDiscovery() {
        stopDiscovery()
        discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == "_lanonly._tcp.") {
                    nsdManager.resolveService(serviceInfo, resolveListener)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == serviceInfo.serviceName }
            }
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) { stopDiscovery() }
        }
        nsdManager.discoverServices("_lanonly._tcp.", NsdManager.PROTOCOL_DNS_SD, discListener)
    }

    fun stopDiscovery() {
        runCatching { discListener?.let { nsdManager.stopServiceDiscovery(it) } }
        discListener = null
    }

    fun unregister() {
        runCatching { regListener?.let { nsdManager.unregisterService(it) } }
        regListener = null
    }
}
