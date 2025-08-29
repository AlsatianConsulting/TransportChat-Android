package dev.alsatianconsulting.transportchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import dev.alsatianconsulting.transportchat.data.ActiveChat
import dev.alsatianconsulting.transportchat.data.AppSettings
import dev.alsatianconsulting.transportchat.data.IncomingDispatcher
import dev.alsatianconsulting.transportchat.data.RepositoryBootstrap
import dev.alsatianconsulting.transportchat.data.UnreadCenter
import dev.alsatianconsulting.transportchat.net.ChatServer
import dev.alsatianconsulting.transportchat.session.NicknameCache
import dev.alsatianconsulting.transportchat.ui.theme.TransportChatTheme
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : ComponentActivity() {

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App singletons
        AppSettings.init(applicationContext)
        RepositoryBootstrap.ensureInitialized(applicationContext)
        ChatServer.init(applicationContext)
        ChatServer.start(AppSettings.listenPortValue)
        IncomingDispatcher.start(applicationContext)

        // Ask for notifications on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            TransportChatTheme {
                MainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActiveChat.clearActive()
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current

    val displayName by AppSettings.displayName.collectAsState()
    val listenPort by AppSettings.listenPort.collectAsState()

    val nsdController = remember { NsdController(ctx.applicationContext) }

    val localIp = remember { ipString(ctx) }
    val selfIps = remember { allLocalIpv4() }

    val peersMap = nsdController.peers
    val peerList = peersMap.values.toList()

    val unreadMap by UnreadCenter.unreadCounts.collectAsState()

    // 🔸 Ephemeral labels (session-only)
    val labels by NicknameCache.labels.collectAsState(initial = emptyMap())

    // Hoisted rename state for the peers-row ⋮ menu
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameTargetHost by remember { mutableStateOf<String?>(null) }
    var renameTargetPort by remember { mutableStateOf<Int?>(null) }

    // (Re)register server + service when settings change
    LaunchedEffect(listenPort) {
        ChatServer.start(listenPort)
        nsdController.reRegister(displayNameOrDefault(displayName), listenPort, selfIps)
    }
    LaunchedEffect(displayName) {
        nsdController.reRegister(displayNameOrDefault(displayName), listenPort, selfIps)
    }
    LaunchedEffect(Unit) {
        nsdController.startAll(displayNameOrDefault(displayName), listenPort, selfIps)
    }
    DisposableEffect(Unit) {
        onDispose { nsdController.stopAll() }
    }

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(listenPort.toString()) }

    val orange = Color(0xFFE87722)

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Transport Chat",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = { startSettings(ctx) }) { Text("Settings") }
            }

            // Status (two lines)
            Column {
                Text(
                    "Status — Listening $localIp:$listenPort",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Display Name — ${displayNameOrDefault(displayName)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HorizontalDivider()

            // Peers + Refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Peers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = { nsdController.refreshDiscovery(selfIps) }) {
                    Text("Refresh")
                }
            }

            if (peerList.isEmpty()) {
                Text("No peers discovered yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(peerList.size) { idx ->
                        val p = peerList[idx]
                        val unread = unreadMap[UnreadCenter.key(p.host, p.port)] ?: 0

                        // overlay ephemeral label
                        val idKey = "${p.host}:${p.port}"
                        val label = labels[idKey]?.takeIf { it.isNotBlank() } ?: p.name
                        val peerWithLabel = p.copy(name = label)

                        PeerRow(
                            peer = peerWithLabel,
                            unread = unread,
                            onClick = { openChat(ctx, peerWithLabel) },
                            onMoreSend = { openChat(ctx, peerWithLabel) },
                            onMoreRename = {
                                renameTargetHost = p.host
                                renameTargetPort = p.port
                                renameText = label
                                showRename = true
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // Manual connect
            Text(
                "Manual Connect",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("Host/IP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { s -> manualPort = s.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.width(120.dp)
                )
                Button(
                    onClick = {
                        val host = manualHost.trim()
                        val port = manualPort.toIntOrNull()?.coerceIn(1, 65535) ?: return@Button
                        if (host.isNotEmpty()) {
                            val pseudoPeer = Peer(
                                key = "manual:$host:$port",
                                name = "$host:$port",
                                host = host,
                                port = port,
                                isSelf = false
                            )
                            openChat(ctx, pseudoPeer)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = orange)
                ) { Text("Connect") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // Rename dialog (drives ephemeral cache only; not persisted)
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Display name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val h = renameTargetHost
                    val p = renameTargetPort
                    val newName = renameText.trim()
                    if (h != null && p != null) {
                        NicknameCache.set(h, p, if (newName.isBlank()) null else newName)
                    }
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

private fun startSettings(ctx: Context) {
    // Ensure you have SettingsActivity; otherwise comment this out.
    ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
}

private fun openChat(ctx: Context, p: Peer) {
    UnreadCenter.clearFor(p.host, p.port)
    val i = Intent(ctx, PeerChatActivity::class.java).apply {
        putExtra(PeerChatActivity.EXTRA_HOST, p.host)
        putExtra(PeerChatActivity.EXTRA_PORT, p.port)
        putExtra(PeerChatActivity.EXTRA_NAME, p.name)
    }
    ctx.startActivity(i)
}

@Composable
private fun PeerRow(
    peer: Peer,
    unread: Int,
    onClick: () -> Unit,
    onMoreSend: () -> Unit,
    onMoreRename: () -> Unit
) {
    val orange = Color(0xFFE87722)
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${peer.host}:${peer.port}", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread > 0) {
                    Surface(color = orange, shape = MaterialTheme.shapes.small) {
                        Text(
                            "$unread",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color.Black,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                if (peer.isSelf) {
                    Text("This device", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(12.dp))
                }
                // ⋮ menu (three stacked dots)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Send Chat") },
                            onClick = { menuOpen = false; onMoreSend() }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename Chat") },
                            onClick = { menuOpen = false; onMoreRename() }
                        )
                    }
                }
            }
        }
    }
}

private data class Peer(
    val key: String,
    val name: String,
    val host: String,
    val port: Int,
    val isSelf: Boolean
)

private class NsdController(private val appCtx: Context) {
    private val TAG = "NSD"
    private val SERVICE_TYPE = "_lanonlychat._tcp."
    private val nsdManager: NsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifi by lazy { appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var mcastLock: WifiManager.MulticastLock? = null

    val peers = mutableStateMapOf<String, Peer>()

    private var regListener: NsdManager.RegistrationListener? = null
    private var registeredName: String? = null
    private var registeredPort: Int? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var selfIpv4: Set<String> = emptySet()

    fun startAll(name: String, port: Int, selfIps: Set<String>) {
        if (regListener == null) register(name, port)
        if (discoveryListener == null) startDiscovery(selfIps)
    }

    fun stopAll() {
        stopDiscovery()
        unregister()
        releaseLock()
        peers.clear()
    }

    fun reRegister(name: String, port: Int, selfIps: Set<String>) {
        if (registeredName == name && registeredPort == port && regListener != null) return
        unregister()
        register(name, port)
        refreshDiscovery(selfIps)
    }

    fun refreshDiscovery(selfIps: Set<String>) {
        stopDiscovery()
        startDiscovery(selfIps)
    }

    private fun register(name: String, port: Int) {
        if (name.isBlank() || port !in 1..65535) {
            Log.w(TAG, "Not registering: invalid name/port")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                registeredName = NsdServiceInfo.serviceName
                registeredPort = port
                Log.d(TAG, "Registered ${NsdServiceInfo.serviceName} on $port")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Unregistered ${serviceInfo.serviceName}")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregister failed: $errorCode")
            }
        }
        regListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregister() {
        regListener?.let { l ->
            runCatching { nsdManager.unregisterService(l) }
            regListener = null
            registeredName = null
            registeredPort = null
        }
    }

    private fun startDiscovery(selfIps: Set<String>) {
        selfIpv4 = selfIps
        acquireLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed: $errorCode"); stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode"); stopDiscovery()
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed: $errorCode for ${serviceInfo.serviceName}")
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val hostRaw = resolved.host ?: return
                        val host = (hostRaw.hostAddress ?: hostRaw.canonicalHostName ?: return)
                            .substringBefore('%')
                        val port = resolved.port.takeIf { it > 0 } ?: return

                        // Strong self-filter:
                        val nameMatch = registeredName != null && resolved.serviceName == registeredName
                        val ipMatch = host in selfIpv4
                        val portMatch = registeredPort != null && port == registeredPort
                        if (nameMatch || ipMatch || (nameMatch && portMatch)) {
                            Log.d(TAG, "Skip self: ${resolved.serviceName} -> $host:$port")
                            return
                        }

                        val key = "${resolved.serviceName}@$host:$port"
                        peers[key] = Peer(
                            key = key,
                            name = resolved.serviceName,
                            host = host,
                            port = port,
                            isSelf = false
                        )
                        Log.d(TAG, "Resolved ${resolved.serviceName} -> $host:$port")
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val removeKey = peers.keys.firstOrNull { it.startsWith("${serviceInfo.serviceName}@") }
                if (removeKey != null) peers.remove(removeKey)
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        discoveryListener?.let { l ->
            runCatching { nsdManager.stopServiceDiscovery(l) }
            discoveryListener = null
        }
    }

    private fun acquireLock() {
        val lock = mcastLock
        if (lock?.isHeld == true) return
        mcastLock = wifi.createMulticastLock("transportchat-lock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseLock() {
        val lock = mcastLock
        if (lock != null && lock.isHeld) lock.release()
        mcastLock = null
    }
}

private fun displayNameOrDefault(name: String): String {
    return if (name.isBlank()) {
        val model = android.os.Build.MODEL?.replace(Regex("""\s+"""), "") ?: "Device"
        "Transport-$model"
    } else name
}

private fun ipString(ctx: Context): String {
    return try {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip != 0) {
            @Suppress("DEPRECATION")
            Formatter.formatIpAddress(ip)
        } else {
            firstIpv4() ?: "0.0.0.0"
        }
    } catch (_: Throwable) {
        firstIpv4() ?: "0.0.0.0"
    }
}

private fun firstIpv4(): String? = allLocalIpv4().firstOrNull()

private fun allLocalIpv4(): Set<String> {
    val out = mutableSetOf<String>()
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptySet()
    for (ni in Collections.list(ifaces)) {
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in Collections.list(ni.inetAddresses)) {
            if (addr is Inet4Address && !addr.isLoopbackAddress) out += addr.hostAddress
        }
    }
    return out
}
