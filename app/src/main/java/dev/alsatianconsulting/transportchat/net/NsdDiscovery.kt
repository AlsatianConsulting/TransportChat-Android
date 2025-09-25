package dev.alsatianconsulting.transportchat.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.alsatianconsulting.transportchat.data.LocalIdentity

/**
 * NSD wrapper used by discovery/advertising.
 *
 * IMPORTANT: If you don't pass a suffix when registering, we will resolve a user display name
 * via LocalIdentity instead of falling back to Build.MODEL — so the advertised name matches
 * the one used in exports.
 */
object NsdDiscovery {
    private const val TAG = "NsdDiscovery"
    const val SERVICE_TYPE = "_lanonlychat._tcp."
    private const val NAME_PREFIX = "LanChat"

    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    @Volatile private var _currentServiceName: String? = null
    @Volatile private var _currentDisplayName: String? = null

    /** Full NSD service name currently advertised, e.g., "LanChat-Alice". */
    fun advertisedServiceName(): String? = _currentServiceName

    /** The display name portion used in the service name, e.g., "Alice". */
    fun advertisedDisplayName(): String? = _currentDisplayName

    /**
     * Register/advertise our service.
     * @param deviceNameSuffix If null, we use LocalIdentity.resolveDisplayName(context).
     */
    fun register(context: Context, port: Int, deviceNameSuffix: String? = null) {
        require(port > 0) { "port must be > 0" }
        val mgr = (nsdManager ?: context.getSystemService(Context.NSD_SERVICE) as NsdManager)
            .also { nsdManager = it }

        val suffix = (deviceNameSuffix ?: LocalIdentity.resolveDisplayName(context)).take(20)
        val serviceName = "$NAME_PREFIX-$suffix"

        _currentDisplayName = suffix
        _currentServiceName = serviceName

        val info = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            this.serviceName = serviceName
            this.port = port
        }

        if (regListener == null) {
            regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) {
                    // Some stacks may alter final name; preserve the system's result.
                    _currentServiceName = s.serviceName
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
            runCatching { discListener?.let { mgr.stopServiceDiscovery(it) } }
            runCatching { regListener?.let { mgr.unregisterService(it) } }
        }
        discListener = null
        regListener = null
        // Keep last display name for export; service name cleared.
        _currentServiceName = null
    }
}
