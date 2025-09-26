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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import dev.alsatianconsulting.transportchat.data.AppSettings
import dev.alsatianconsulting.transportchat.data.ChatStore
import dev.alsatianconsulting.transportchat.data.IncomingDispatcher
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

        AppSettings.init(applicationContext)
        ChatServer.init(applicationContext)
        ChatServer.start(AppSettings.listenPortValue)
        IncomingDispatcher.start(applicationContext)

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
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val orange = Color(0xFFE87722)

    val displayName by AppSettings.displayName.collectAsState()
    val listenPort by AppSettings.listenPort.collectAsState()

    // Effective values that can be changed from the Settings menu (without touching other logic)
    var nameOverride by remember { mutableStateOf<String?>(null) }
    var portOverride by remember { mutableStateOf<Int?>(null) }
    val myDisplay = (nameOverride ?: displayName).ifBlank { "Device-${Build.MODEL ?: "Android"}" }
    val myPort = portOverride ?: listenPort

    val nsdController = remember { NsdController(ctx.applicationContext) }

    val localIp = remember { ipString(ctx) }
    val selfIps = remember { allLocalIpv4() }

    val peersMap = nsdController.peers
    // Filter out this device from the peers list
    val peerList = peersMap.values.filter { !it.isSelf }.sortedBy { it.name }

    // Online/offline means "advertise or not"; discovery always runs.
    var online by remember { mutableStateOf(true) }

    // Always keep discovery running
    LaunchedEffect(Unit) {
        nsdController.refreshDiscovery(selfIps)
    }
    // Apply advertising state and react to name/port changes
    LaunchedEffect(myPort, myDisplay, online) {
        if (online) {
            nsdController.reRegister(myDisplay, myPort, selfIps)
        } else {
            nsdController.stopAdvertise()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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

                // Settings menu
                var settingsOpen by remember { mutableStateOf(false) }
                var changeNameOpen by remember { mutableStateOf(false) }
                var changePortOpen by remember { mutableStateOf(false) }

                Box {
                    OutlinedButton(
                        onClick = { settingsOpen = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black,
                            contentColor = orange
                        ),
                        border = BorderStroke(1.dp, orange)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = orange
                        )
                    }
                    DropdownMenu(expanded = settingsOpen, onDismissRequest = { settingsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (online) "Appear offline" else "Appear online") },
                            onClick = {
                                settingsOpen = false
                                // Toggle only; LaunchedEffect handles advertising change.
                                online = !online
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Change Name") },
                            onClick = { settingsOpen = false; changeNameOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Change Port") },
                            onClick = { settingsOpen = false; changePortOpen = true }
                        )
                    }
                }

                // Change Name dialog (local device)
                if (changeNameOpen) {
                    var newName by remember { mutableStateOf(myDisplay) }
                    AlertDialog(
                        onDismissRequest = { changeNameOpen = false },
                        title = { Text("Change Name") },
                        text = {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("Your name") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty()) {
                                    nameOverride = trimmed
                                    if (online) nsdController.reRegister(trimmed, myPort, selfIps)
                                }
                                changeNameOpen = false
                            }) { Text("Save") }
                        },
                        // ADD: Reset button alongside Cancel to revert to default (network-derived) by clearing override
                        dismissButton = {
                            Row {
                                TextButton(onClick = {
                                    // Clear override; re-register with AppSettings.displayName (or default derivation)
                                    nameOverride = null
                                    if (online) nsdController.reRegister(
                                        (displayName).ifBlank { "Device-${Build.MODEL ?: "Android"}" },
                                        myPort,
                                        selfIps
                                    )
                                    changeNameOpen = false
                                }) { Text("Reset") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { changeNameOpen = false }) { Text("Cancel") }
                            }
                        }
                    )
                }

                // Change Port dialog
                if (changePortOpen) {
                    var newPort by remember { mutableStateOf(myPort.toString()) }
                    AlertDialog(
                        onDismissRequest = { changePortOpen = false },
                        title = { Text("Change Port") },
                        text = {
                            OutlinedTextField(
                                value = newPort,
                                onValueChange = { s -> newPort = s.filter { it.isDigit() }.take(5) },
                                label = { Text("Listen port") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val p = newPort.toIntOrNull()?.coerceIn(1, 65535)
                                if (p != null) {
                                    portOverride = p
                                    ChatServer.start(p)
                                    if (online) nsdController.reRegister(myDisplay, p, selfIps)
                                }
                                changePortOpen = false
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { changePortOpen = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            Column {
                Text(
                    "Status — Listening $localIp:$myPort",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Display Name — $myDisplay",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HorizontalDivider()

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { nsdController.refreshDiscovery(selfIps) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black,
                            contentColor = orange
                        ),
                        border = BorderStroke(1.dp, orange)
                    ) { Text("Refresh") }
                    OutlinedButton(
                        onClick = { nsdController.manualAddOpen = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black,
                            contentColor = orange
                        ),
                        border = BorderStroke(1.dp, orange)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Manual Connect",
                            tint = orange
                        )
                    }
                }
            }

            if (peerList.isEmpty()) {
                Text("No peers discovered yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(peerList, key = { "${it.host}:${it.port}" }) { p ->
                        val msgs by ChatStore.messagesFlow(p.host, p.port).collectAsState(initial = emptyList())
                        val unread = remember(msgs) { msgs.count { m -> !m.outgoing && m.readAt == null } }

                        val idKey = "${p.host}:${p.port}"
                        val label = NicknameCache.labels.collectAsState(initial = emptyMap()).value[idKey]
                            ?.takeIf { it.isNotBlank() } ?: p.name
                        val peerWithLabel = p.copy(name = label)

                        PeerRow(
                            peer = peerWithLabel,
                            unread = unread,
                            onClick = { openChat(ctx, peerWithLabel) },
                            onMoreSend = { openChat(ctx, peerWithLabel) },
                            onMoreRename = { nsdController.showRenameFor(p.host, p.port, label) }
                        )
                    }
                }
            }
        }
    }

    // Rename dialog for a peer
    if (nsdController.renameOpen) {
        AlertDialog(
            onDismissRequest = { nsdController.renameOpen = false },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = nsdController.renameText,
                    onValueChange = { nsdController.renameText = it },
                    label = { Text("Display name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { nsdController.applyRename() }) { Text("Save") }
            },
            // Keep Cancel; Save logic unchanged. Reset for peer nickname is handled by clearing text and saving.
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // Clear nickname to revert to advertised network name
                        nsdController.renameText = ""
                        nsdController.applyRename()
                    }) { Text("Reset") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { nsdController.renameOpen = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (nsdController.manualAddOpen) {
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf(myPort.toString()) }
        var nick by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { nsdController.manualAddOpen = false },
            title = { Text("Manual Connect") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Host/IP") }, singleLine = true)
                    OutlinedTextField(
                        port,
                        { s -> port = s.filter { it.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true
                    )
                    OutlinedTextField(nick, { nick = it }, label = { Text("Nickname (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val h = host.trim()
                    val p = port.toIntOrNull()?.coerceIn(1, 65535)
                    if (h.isNotEmpty() && p != null) {
                        val name = if (nick.isBlank()) "$h:$p" else nick.trim()
                        openChat(ctx, Peer(key = "manual:$h:$p", name = name, host = h, port = p, isSelf = false))
                        nsdController.manualAddOpen = false
                    }
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { nsdController.manualAddOpen = false }) { Text("Cancel") }
            }
        )
    }
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
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Send Chat") }, onClick = { menuOpen = false; onMoreSend() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onMoreRename() })
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

private fun ipString(ctx: Context): String {
    return try {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        Formatter.formatIpAddress(ip)
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

private class NsdController(private val appCtx: Context) {
    private val TAG = "NSD"
    private val SERVICE_TYPE = "_lanonlychat._tcp."
    private val nsdManager: NsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager

    val peers: MutableMap<String, Peer> = mutableStateMapOf()
    private var regListener: NsdManager.RegistrationListener? = null
    private var registeredName: String? = null

    var renameOpen by mutableStateOf(false)
    var renameText by mutableStateOf("")
    private var renameHost: String? = null
    private var renamePort: Int? = null

    var manualAddOpen by mutableStateOf(false)

    fun showRenameFor(host: String, port: Int, current: String) {
        renameHost = host; renamePort = port; renameText = current; renameOpen = true
    }

    // Allow clearing nickname (blank) to revert to advertised name
    fun applyRename() {
        val h = renameHost; val p = renamePort; val newName = renameText.trim()
        if (!h.isNullOrBlank() && p != null) {
            // If newName is blank, we store a blank label; UI falls back to advertised name.
            NicknameCache.set(h, p, newName)
        }
        renameOpen = false
    }

    fun startAll(name: String, port: Int, selfIps: Set<String>) {
        reRegister(name, port, selfIps)
        refreshDiscovery(selfIps)
    }
    fun stopAll() {
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Throwable) {}
        try { regListener?.let { nsdManager.unregisterService(it) } } catch (_: Throwable) {}
        regListener = null
        registeredName = null
    }

    // Stop advertising only (keep discovery running)
    fun stopAdvertise() {
        try { regListener?.let { nsdManager.unregisterService(it) } } catch (_: Throwable) {}
        regListener = null
        registeredName = null
    }

    fun reRegister(name: String, port: Int, selfIps: Set<String>) {
        try { regListener?.let { nsdManager.unregisterService(it) } } catch (_: Throwable) {}

        val info = NsdServiceInfo().apply {
            serviceName = "TransportChat-$name"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) { registeredName = NsdServiceInfo.serviceName }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Register failed: $errorCode") }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Unregister failed: $errorCode") }
        }
        regListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    fun refreshDiscovery(selfIps: Set<String>) {
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Throwable) {}
        peers.clear()
        // SAFETY: wrap discoverServices to avoid crash if already discovering or in race.
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices failed: ${t.message}")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "Discovery start failed: $errorCode") }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "Discovery stop failed: $errorCode") }
        override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "Discovery started") }
        override fun onDiscoveryStopped(serviceType: String) { Log.d(TAG, "Discovery stopped") }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != SERVICE_TYPE) return
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed: $errorCode")
                }
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val port = resolved.port.takeIf { it > 0 } ?: return
                    val rawName = resolved.serviceName ?: "$host:$port"
                    val isSelf = (registeredName != null && rawName == registeredName)
                    val name = rawName.removePrefix("TransportChat-")

                    // Capture previous advertised name before updating the map
                    val id = "$host:$port"
                    val previousAdvertised = peers[id]?.name

                    // Update current advertised entry
                    peers[id] = Peer(key = id, name = name, host = host, port = port, isSelf = isSelf)

                    // Sync nickname store with advertised name ONLY if it looks app-generated
                    // (blank or equal to previous advertised name). Never overwrite a user-custom nickname.
                    runCatching {
                        val existing = runCatching { NicknameCache.get(host, port) }.getOrNull()
                        val looksAuto = existing.isNullOrBlank() || (previousAdvertised != null && existing == previousAdvertised)
                        if (looksAuto && name.isNotBlank()) {
                            NicknameCache.set(host, port, name)
                        }
                    }
                }
            })
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            peers.remove("$host:${serviceInfo.port}")
        }
    }
}
