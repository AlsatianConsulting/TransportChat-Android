package dev.alsatianconsulting.transportchat.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

object NsdDiscovery {
    private const val TAG = "NsdDiscovery"
    const val SERVICE_TYPE = "_lanonlychat._tcp."
    private const val NAME_PREFIX = "LanChat"

    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    fun register(context: Context, port: Int, deviceNameSuffix: String? = null) {
        require(port > 0) { "port must be > 0" }
        val mgr = (nsdManager ?: context.getSystemService(Context.NSD_SERVICE) as NsdManager)
            .also { nsdManager = it }

        val suffix = (deviceNameSuffix ?: (Build.MODEL ?: "Android")).take(20)
        val serviceName = "$NAME_PREFIX-$suffix"

        val info = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            this.serviceName = serviceName
            this.port = port
        }

        if (regListener == null) {
            regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) {
                    Log.i(TAG, "Registered: ${s.serviceName} @ ${s.port}")
                }
                override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(s: NsdServiceInfo) {
                    Log.i(TAG, "Unregistered: ${s.serviceName}")
                }
                override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Unregistration failed: $errorCode")
                }
            }
        }

        mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener!!)
    }

    fun discover(context: Context, onResolved: (NsdServiceInfo) -> Unit) {
        val mgr = (nsdManager ?: context.getSystemService(Context.NSD_SERVICE) as NsdManager)
            .also { nsdManager = it }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed $errorCode for ${si.serviceName}")
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                Log.i(TAG, "Resolved ${si.serviceName} -> ${si.host}:${si.port}")
                onResolved(si)
            }
        }

        discListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.e(TAG, "Discovery start failed: $error")
                mgr.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) {
                Log.e(TAG, "Discovery stop failed: $error")
                mgr.stopServiceDiscovery(this)
            }
            override fun onDiscoveryStarted(type: String) { Log.i(TAG, "Discovery started: $type") }
            override fun onDiscoveryStopped(type: String) { Log.i(TAG, "Discovery stopped: $type") }
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceType == SERVICE_TYPE && si.serviceName.startsWith(NAME_PREFIX)) {
                    mgr.resolveService(si, resolveListener)
                }
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${si.serviceName}")
            }
        }

        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener!!)
    }

    fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
        nsdManager?.let { mgr ->
            try { discListener?.let { mgr.stopServiceDiscovery(it) } } catch (_: Exception) {}
            try { regListener?.let { mgr.unregisterService(it) } } catch (_: Exception) {}
        }
        discListener = null
        regListener = null
    }
}
