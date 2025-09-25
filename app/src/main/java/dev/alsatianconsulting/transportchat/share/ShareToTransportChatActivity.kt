package dev.alsatianconsulting.transportchat.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.transportchat.PeerChatActivity
import dev.alsatianconsulting.transportchat.data.AppSettings
import dev.alsatianconsulting.transportchat.data.ChatStore
import dev.alsatianconsulting.transportchat.net.ChatClient
import dev.alsatianconsulting.transportchat.net.NsdDiscovery
import dev.alsatianconsulting.transportchat.session.NicknameCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Android Share target for TransportChat.
 * Receives shared file(s), lets the user choose a recent chat, and sends using ChatClient.sendFile().
 */
class ShareToTransportChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure app data stores and caches are initialized (mirrors PeerChatActivity)
        AppSettings.init(applicationContext)
        // Best-effort init for NicknameCache (no-op if not needed)
        runCatching {
            val cls = NicknameCache::class.java
            runCatching { cls.getMethod("init", Context::class.java).invoke(null, applicationContext) }.getOrNull()
                ?: runCatching { cls.getMethod("start", Context::class.java).invoke(null, applicationContext) }.getOrNull()
        }

        // Collect incoming URIs
        val action = intent?.action
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) listOf(u) else emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> emptyList()
        }

        setContent {
            MaterialTheme {
                ShareScreen(
                    uris = uris,
                    onDone = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ShareScreen(uris: List<Uri>, onDone: () -> Unit) {
    val context = LocalContext.current
    val peers = remember { ChatStore.listOpenChats() } // (host, port) pairs
    val labels by NicknameCache.labels.collectAsState(initial = emptyMap())

    // User nicknames (manual renames)
    val peerStore = remember { dev.alsatianconsulting.transportchat.store.PeerStore(context.applicationContext) }
    val nickMap by peerStore.nicknamesFlow().collectAsState(initial = emptyMap())

    // NEW: live discovery names -> "host:port" to display name, using the same source as MainActivity
    val discoveryNames = remember { mutableStateMapOf<String, String>() }
    DisposableEffect(Unit) {
        // Start discovery; collect names as they resolve
        NsdDiscovery.discover(context.applicationContext) { si ->
            val host = si.host?.hostAddress ?: return@discover
            val port = si.port.takeIf { it > 0 } ?: return@discover
            val raw = si.serviceName ?: return@discover
            val name = raw.removePrefix("TransportChat-")
            if (name.isNotBlank()) {
                discoveryNames["$host:$port"] = name
            }
        }
        onDispose {
            // Stop discovery cleanly when this screen goes away
            NsdDiscovery.stop(context.applicationContext)
        }
    }

    var selected by remember { mutableStateOf<Pair<String, Int>?>(peers.firstOrNull()) }
    val canSend = selected != null && uris.isNotEmpty()

    val orange = Color(0xFFE87722)
    val black = Color.Black

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = black,
        contentColor = Color.White
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Share to TransportChat", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(text = if (uris.isEmpty()) "No files to share" else "Files: ${uris.size}", color = Color.White)

            if (peers.isEmpty()) {
                Text(
                    "No recent chats found.\nOpen TransportChat and start a chat first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            } else {
                Text("Choose a recipient:", color = Color.White)
                LazyColumn(Modifier.weight(1f)) {
                    items(peers.size) { idx ->
                        val (h, p) = peers[idx]
                        val key = "$h:$p"
                        val nickname =
                            nickMap[key]?.takeIf { it.isNotBlank() } // 1) user nickname
                                ?: discoveryNames[key]?.takeIf { it.isNotBlank() } // 2) LIVE advertised name (NSD)
                                ?: labels[key]?.takeIf { it.isNotBlank() } // 3) cache labels
                        val combined = nickname?.let { "$it ($h:$p)" } ?: "$h:$p"

                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            RadioButton(
                                selected = selected?.first == h && selected?.second == p,
                                onClick = { selected = h to p },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = orange,
                                    unselectedColor = orange,
                                    disabledSelectedColor = orange.copy(alpha = 0.5f),
                                    disabledUnselectedColor = orange.copy(alpha = 0.5f)
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(combined, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDone,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = black,
                        contentColor = orange
                    ),
                    border = BorderStroke(1.dp, orange)
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        val target = selected ?: return@Button
                        val host = target.first
                        val port = target.second
                        val key = "$host:$port"
                        val display =
                            nickMap[key]?.takeIf { it.isNotBlank() }
                                ?: discoveryNames[key]?.takeIf { it.isNotBlank() }
                                ?: labels[key]?.takeIf { it.isNotBlank() }
                                ?: key

                        // Navigate to the chat immediately so the Sharesheet dismisses
                        val chatIntent = Intent(context, PeerChatActivity::class.java).apply {
                            putExtra(PeerChatActivity.EXTRA_HOST, host)
                            putExtra(PeerChatActivity.EXTRA_PORT, port)
                            putExtra(PeerChatActivity.EXTRA_NAME, display)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chatIntent)

                        // Start the transfer in a process-wide scope (survives finish())
                        GlobalScope.launch(Dispatchers.IO) {
                            sendAll(context.applicationContext, host, port, uris)
                        }

                        // Finish right away to close the Sharesheet
                        onDone()
                    },
                    enabled = canSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = orange,
                        contentColor = Color.White
                    )
                ) { Text("Send") }
            }
        }
    }
}

private suspend fun sendAll(appCtx: Context, host: String, port: Int, uris: List<Uri>) {
    withContext(Dispatchers.IO) {
        uris.forEachIndexed { idx, uri ->
            runCatching {
                val local = copyToLocalCache(appCtx, uri, idx)
                ChatClient.sendFile(appCtx, host, port, local)
            }
        }
    }
}

/** Copy a shared URI to an app-private cache file and return a file:// Uri we control. */
private fun copyToLocalCache(appCtx: Context, src: Uri, index: Int): Uri {
    val cr = appCtx.contentResolver
    val nameGuess = runCatching {
        var n: String? = null
        cr.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) n = c.getString(0)
        }
        n
    }.getOrNull()
    val base = nameGuess?.takeIf { it.isNotBlank() } ?: "shared_${System.currentTimeMillis()}_$index"
    val outFile = File(appCtx.cacheDir, base)
    cr.openInputStream(src)?.use { input ->
        FileOutputStream(outFile).use { out ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.flush()
        }
    }
    return Uri.fromFile(outFile)
}
