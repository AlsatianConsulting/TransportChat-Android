package dev.alsatianconsulting.transportchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.alsatianconsulting.transportchat.data.ActiveChat
import dev.alsatianconsulting.transportchat.data.AppSettings
import dev.alsatianconsulting.transportchat.data.ChatStore
import dev.alsatianconsulting.transportchat.data.RepositoryBootstrap
import dev.alsatianconsulting.transportchat.data.TransferCenter
import dev.alsatianconsulting.transportchat.data.TransferDirection
import dev.alsatianconsulting.transportchat.data.TransferSnapshot
import dev.alsatianconsulting.transportchat.data.TransferStatus
import dev.alsatianconsulting.transportchat.data.UnreadCenter
import dev.alsatianconsulting.transportchat.net.ChatClient
import dev.alsatianconsulting.transportchat.net.ChatServer
import dev.alsatianconsulting.transportchat.session.NicknameCache
import dev.alsatianconsulting.transportchat.ui.theme.TransportChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class PeerChatActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_NAME = "extra_name"
    }

    private var hostArg: String = ""
    private var portArg: Int = 7777

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.init(applicationContext)
        RepositoryBootstrap.ensureInitialized(applicationContext)

        hostArg = intent.getStringExtra(EXTRA_HOST) ?: ""
        portArg = intent.getIntExtra(EXTRA_PORT, 7777)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "$hostArg:$portArg"

        setContent {
            TransportChatTheme {
                ChatScreen(peerName = name, host = hostArg, port = portArg)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActiveChat.setActive(hostArg, portArg)
        UnreadCenter.clearFor(hostArg, portArg)

        // Mark buffered incoming as read + send receipts
        val ids = ChatStore.markAllIncomingRead(hostArg, portArg)
        lifecycleScope.launch(Dispatchers.IO) {
            val at = System.currentTimeMillis()
            for (id in ids) {
                try { ChatClient.sendReadReceipt(hostArg, portArg, id, at) } catch (_: Throwable) {}
            }
        }
    }

    override fun onStop() {
        super.onStop()
        ActiveChat.clearActive()
    }
}

@Composable
private fun ChatScreen(peerName: String, host: String, port: Int) {
    val context = LocalContext.current
    val orange = Color(0xFFE87722)
    val scope = rememberCoroutineScope()

    val messages by ChatStore.messagesFlow(host, port).collectAsState()
    val transfers by TransferCenter.snapshots.collectAsState()

    // 🔸 derive title from ephemeral cache (session-only label)
    val labels by NicknameCache.labels.collectAsState(initial = emptyMap())
    val idKey = "$host:$port"
    val titleName = labels[idKey]?.takeIf { it.isNotBlank() } ?: peerName

    // Incoming file offers → choose save location
    var pendingOfferId by remember { mutableStateOf<String?>(null) }
    var pendingOfferName by remember { mutableStateOf("file") }
    var pendingOfferMime by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(host, port) {
        ChatServer.incomingFileOffers.collect { offer ->
            if (offer.remoteHost == host && offer.localPort == port) {
                pendingOfferId = offer.id
                pendingOfferName = offer.name
                pendingOfferMime = offer.mime
            }
        }
    }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(pendingOfferMime ?: "*/*")
    ) { uri: Uri? ->
        val id = pendingOfferId
        if (id != null) {
            if (uri != null) ChatServer.acceptOffer(id, uri) else ChatServer.rejectOffer(id)
        }
        pendingOfferId = null
    }

    // Outgoing file (confirm + optional note)
    var showConfirm by remember { mutableStateOf(false) }
    var attachUri by remember { mutableStateOf<Uri?>(null) }
    var note by remember { mutableStateOf("") }
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachUri = uri
        if (uri != null) {
            note = ""
            showConfirm = true
        }
    }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Chat — $titleName ($host:$port)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider()

            // Messages + transfers
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.size) { idx ->
                    val line = messages[idx]

                    val bubbleColor: Color
                    val bubbleOn: Color
                    if (line.outgoing) {
                        bubbleColor = orange
                        bubbleOn = Color.White
                    } else {
                        bubbleColor = MaterialTheme.colorScheme.surfaceVariant
                        bubbleOn = MaterialTheme.colorScheme.onSurface
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = bubbleColor,
                            contentColor = bubbleOn,
                            tonalElevation = if (line.outgoing) 4.dp else 1.dp,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 320.dp)) {
                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                // status / time row
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (line.outgoing) {
                                        val status = when {
                                            line.readAt != null -> "✓✓ ${timeShort(line.readAt)}"
                                            line.delivered -> "✓ Delivered"
                                            else -> "… Sending"
                                        }
                                        Text(
                                            status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = bubbleOn.copy(alpha = 0.85f)
                                        )
                                    } else {
                                        Text(
                                            timeShort(line.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = bubbleOn.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val list = transfers.values
                    .filter { it.host == host && it.port == port }
                    .sortedBy { it.id }
                items(list.size) { i ->
                    val t = list[i]
                    TransferRow(t)
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Type a message") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { getContent.launch("*/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = orange)
                ) {
                    Text("Attach")
                }
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            val id = UUID.randomUUID().toString()
                            ChatStore.appendOutgoing(host, port, text, id = id)
                            input = ""
                            scope.launch {
                                try {
                                    ChatClient.sendSecureText(host, port, id, text)
                                } catch (t: Throwable) {
                                    ChatStore.appendIncoming(host, port, "⚠️ Send failed: ${t.message}")
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = orange)
                ) { Text("Send") }
            }
        }
    }

    // Incoming offer → choose save location
    if (pendingOfferId != null) {
        AlertDialog(
            onDismissRequest = {
                ChatServer.rejectOffer(pendingOfferId!!)
                pendingOfferId = null
            },
            title = { Text("Incoming file") },
            text = { Text("Receive “$pendingOfferName”? Choose where to save.") },
            confirmButton = {
                TextButton(onClick = { createDoc.launch(pendingOfferName) }) { Text("Choose location") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ChatServer.rejectOffer(pendingOfferId!!)
                    pendingOfferId = null
                }) { Text("Reject") }
            }
        )
    }

    // Outgoing file confirm + optional note
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Send file?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a note (optional):")
                    OutlinedTextField(value = note, onValueChange = { note = it }, singleLine = false, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = attachUri ?: run { showConfirm = false; return@TextButton }
                    showConfirm = false
                    if (note.isNotBlank()) ChatStore.appendOutgoing(host, port, "📎 $note")
                    val appCtx = context.applicationContext
                    scope.launch {
                        try {
                            ChatClient.sendFile(appCtx, host, port, uri)
                        } catch (e: Throwable) {
                            ChatStore.appendIncoming(host, port, "⚠️ File send failed: ${e.message}")
                        }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TransferRow(t: TransferSnapshot) {
    val ctx = LocalContext.current
    val pct = if (t.size > 0) t.bytes.toFloat() / t.size.toFloat() else 0f
    val label = when (t.status) {
        TransferStatus.WAITING -> "Waiting… ${t.name}"
        TransferStatus.TRANSFERRING -> "${if (t.direction == TransferDirection.OUTGOING) "Sending" else "Receiving"} ${t.name} (${humanBytes(t.bytes)} / ${humanBytes(t.size)})"
        TransferStatus.DONE -> "Done — ${t.name}"
        TransferStatus.FAILED -> "Failed — ${t.error ?: "unknown"}"
        TransferStatus.REJECTED -> "Rejected — ${t.name}"
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (t.status == TransferStatus.TRANSFERRING) {
            LinearProgressIndicator(progress = pct, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (t.status == TransferStatus.DONE && t.direction == TransferDirection.INCOMING && t.savedTo != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(t.savedTo, t.mime ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { ctx.startActivity(Intent.createChooser(intent, "Open with")) }
                }) { Text("Open") }
            }
        }
    }
}

private fun humanBytes(v: Long): String {
    if (v < 0L) return "—"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        v >= gb -> String.format("%.2f GB", v / gb)
        v >= mb -> String.format("%.2f MB", v / mb)
        v >= kb -> String.format("%.1f KB", v / kb)
        else -> "$v B"
    }
}

private fun timeShort(millis: Long): String {
    return try {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        fmt.format(java.util.Date(millis))
    } catch (_: Throwable) { "" }
}
