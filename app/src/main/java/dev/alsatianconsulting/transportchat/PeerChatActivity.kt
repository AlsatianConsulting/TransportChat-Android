package dev.alsatianconsulting.transportchat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.alsatianconsulting.transportchat.data.ActiveChat
import dev.alsatianconsulting.transportchat.data.AppSettings
import dev.alsatianconsulting.transportchat.data.ChatLine
import dev.alsatianconsulting.transportchat.data.ChatStore
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border

// ---- Back-compat shims for TaskRemovedWatcherService and anyone calling UnreadCenter.clearAll() ----
fun clearAll() {
    try {
        val cls = Class.forName("dev.alsatianconsulting.transportchat.data.UnreadCenter")
        val inst = cls.kotlin.objectInstance ?: cls.getDeclaredField("INSTANCE").get(null)
        val m = runCatching { cls.getMethod("clearAll") }.getOrNull()
        m?.invoke(inst)
    } catch (_: Throwable) {
        // no-op if not present
    }
}
// Extension so calls like UnreadCenter.clearAll() resolve even if the real method doesn't exist
fun UnreadCenter.clearAll() {
    runCatching {
        // Best-effort: clear unread for all known chats
        dev.alsatianconsulting.transportchat.data.ChatStore.listOpenChats().forEach { (h, p) ->
            UnreadCenter.clearFor(h, p)
        }
    }
}

class PeerChatActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_LOCAL_NAME = "local_display_name"

        // Group (kept but optional)
        const val EXTRA_GROUP_HOSTS = "group_hosts"
        const val EXTRA_GROUP_PORTS = "group_ports"
        const val EXTRA_GROUP_LABELS = "group_labels"
        const val EXTRA_GROUP_SUBJECT = "group_subject"
        const val EXTRA_GROUP_ORIGINAL_COUNT = "group_orig_count"
    }

    private var hostArg: String = ""
    private var portArg: Int = 7777
    private var localNameArg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.init(applicationContext)

        hostArg = intent.getStringExtra(EXTRA_HOST) ?: ""
        portArg = intent.getIntExtra(EXTRA_PORT, 7777)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "$hostArg:$portArg"
        localNameArg =
            intent.getStringExtra(EXTRA_LOCAL_NAME)
                ?: intent.getStringExtra("local_display_name")
                        ?: intent.getStringExtra("display_name")

        val groupHosts: ArrayList<String>? = intent.getStringArrayListExtra(EXTRA_GROUP_HOSTS)
        val groupPorts: IntArray? = intent.getIntArrayExtra(EXTRA_GROUP_PORTS)
        val groupLabels: ArrayList<String>? = intent.getStringArrayListExtra(EXTRA_GROUP_LABELS)
        val groupSubject: String? = intent.getStringExtra(EXTRA_GROUP_SUBJECT)
        val groupOriginalCount: Int = intent.getIntExtra(EXTRA_GROUP_ORIGINAL_COUNT, -1)

        setContent {
            TransportChatTheme {
                ChatScreen(
                    peerName = name,
                    host = hostArg,
                    port = portArg,
                    localNameHint = localNameArg,
                    groupHosts = groupHosts,
                    groupPorts = groupPorts,
                    groupLabels = groupLabels,
                    initialGroupSubject = groupSubject,
                    initialGroupCount = groupOriginalCount
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActiveChat.setActive(hostArg, portArg)

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(
    peerName: String,
    host: String,
    port: Int,
    localNameHint: String?,
    groupHosts: ArrayList<String>? = null,
    groupPorts: IntArray? = null,
    groupLabels: ArrayList<String>? = null,
    initialGroupSubject: String? = null,
    initialGroupCount: Int = -1,
) {
    val context: Context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val orange: Color = Color(0xFFE87722)
    val scope = rememberCoroutineScope()

    val messages by ChatStore.messagesFlow(host, port).collectAsState()
    // TransferCenter snapshots: Map<id, TransferSnapshot> (Working integration)
    val transfersMap by TransferCenter.snapshots.collectAsState()

    // Mark incoming as read whenever the list changes (chat is visible) and send READ receipts.
    LaunchedEffect(messages) {
        val ids = ChatStore.markAllIncomingRead(host, port)
        if (ids.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                ids.forEach { id ->
                    try { ChatClient.sendReadReceipt(host, port, id, System.currentTimeMillis()) } catch (_: Throwable) {}
                }
            }
        }
    }

    val liveSettingName by AppSettings.displayName.collectAsState()
    val localDisplayName: String = remember(localNameHint, liveSettingName) {
        when {
            !localNameHint.isNullOrBlank() -> localNameHint!!
            liveSettingName.isNotBlank() -> liveSettingName
            else -> defaultDisplayName(liveSettingName)
        }
    }

    // ---- Multi-select state ----
    val selectedIds = remember { mutableStateListOf<String>() }
    var selectMode by remember { mutableStateOf(false) }
    fun toggleSelect(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
    }
    fun clearSelection() {
        selectedIds.clear()
        selectMode = false
    }

    // Made reactive so it updates immediately after rename
    var titleName by remember(peerName) { mutableStateOf(if (peerName.isBlank()) "$host:$port" else peerName) }

    var pendingOfferId by remember { mutableStateOf<String?>(null) }
    var pendingOfferName by remember { mutableStateOf("") }
    var pendingOfferSize by remember { mutableStateOf(-1L) }
    var pendingOfferMime by remember { mutableStateOf<String?>(null) }
    var openOfferDialog by remember { mutableStateOf(false) }

    var showConfirm by remember { mutableStateOf(false) }
    var attachUri by remember { mutableStateOf<Uri?>(null) }
    var note by remember { mutableStateOf("") }

    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // Overflow (⋮) and Clear Chat
    var overflowOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }

    val cameraUri = remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            cameraUri.value?.let { uri ->
                (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val (h, p) = host to port
                    ChatStore.appendOutgoing(h, p, "📷 Photo", id = UUID.randomUUID().toString())
                    runCatching { ChatClient.sendFile(context, h, p, uri) }
                        .onFailure {
                            ChatStore.appendIncoming(h, p, "⚠️ Photo send failed: ${it.message}")
                        }
                }
            }
        } else {
            Toast.makeText(context, "Capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            attachUri = uri
            note = ""
            showConfirm = true
        }
    }

    // Save location picker for incoming files (Working behavior)
    val saveLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val id = pendingOfferId
        if (id != null) {
            if (uri != null) {
                (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    runCatching { ChatServer.acceptOffer(id, uri) }
                        .onFailure {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Failed to accept: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                }
            } else {
                ChatServer.rejectOffer(id)
            }
        }
        pendingOfferId = null
        openOfferDialog = false
    }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    data class Participant(val host: String, val port: Int, val label: String)
    val primaryPeer = Participant(host, port, titleName)

    val initialParticipants: List<Participant> = remember(groupHosts, groupPorts, groupLabels, primaryPeer) {
        val gh = groupHosts
        val gp = groupPorts
        val gl = groupLabels
        if (gh != null && gp != null && gl != null && gh.size >= 2 && gh.size == gp.size && gh.size == gl.size) {
            gh.indices.map { i -> Participant(gh[i], gp[i], gl[i].ifBlank { "${gh[i]}:${gp[i]}" }) }
        } else {
            listOf(primaryPeer)
        }
    }
    val participants = remember { mutableStateListOf<Participant>().also { it.addAll(initialParticipants) } }
    val isGroup = participants.size > 1

    var groupSubject by remember { mutableStateOf(initialGroupSubject ?: "") }
    val initialCountForTitle = remember(initialGroupCount, initialParticipants) {
        if (initialGroupCount >= 0) initialGroupCount else initialParticipants.size
    }

    val appCtx = LocalContext.current.applicationContext

    LaunchedEffect(Unit) {
        ChatServer.incomingFileOffers.collect { offer ->
            if (offer.remoteHost == host && offer.localPort == port) {
                pendingOfferId = offer.id
                pendingOfferName = offer.name
                pendingOfferSize = offer.size
                pendingOfferMime = offer.mime
                openOfferDialog = true
            }
        }
    }

    // ---- Block/Unblock state with default label "Block user" until loaded ----
    val peerStore = remember { dev.alsatianconsulting.transportchat.store.PeerStore(context.applicationContext) }
    val blockedSet by peerStore.blockedFlow().collectAsState(initial = emptySet())
    var blockedLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(blockedSet) { blockedLoaded = true }
    val isBlockedForThisPeer = remember(blockedSet, host, port) { blockedSet.contains("$host:$port") }

    // ---- Build timeline BEFORE any use (messages + transfers) ----
    data class TimelineItem(val msg: ChatLine? = null, val xfer: TransferSnapshot? = null)
    val timeline: List<TimelineItem> by remember(messages, transfersMap) {
        mutableStateOf(buildList {
            // messages
            messages.forEach { add(TimelineItem(msg = it)) }
            // transfers for this peer (Working: snapshots is Map<id, TransferSnapshot>)
            transfersMap.values
                .filter { it.host == host && it.port == port }
                .forEach { add(TimelineItem(xfer = it)) }
            // order by message timestamp or transfer createdAt (keeps transfers fixed in place)
            sortBy { it.msg?.timestamp ?: it.xfer?.createdAt ?: 0L }
        })
    }

    // Auto-scroll to bottom whenever the timeline grows
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(timeline.size - 1) }
                .onFailure { runCatching { listState.scrollToItem(timeline.size - 1) } }
        }
    }

    // Export
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            val activity = (context as? ComponentActivity)
            activity?.lifecycleScope?.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        val now = System.currentTimeMillis()
                        val headerFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                        val sb = StringBuilder()
                        sb.appendLine("TransportChat — Export")
                        sb.appendLine("Peer: $titleName ($host:$port)")
                        sb.appendLine("Exported: ${headerFmt.format(java.util.Date(now))}")
                        sb.appendLine("----------------------------------------")

                        timeline.forEach { item ->
                            if (item.msg != null) {
                                val line = item.msg
                                val who = if (line.outgoing) localDisplayName else titleName
                                val t = tsFmt.format(java.util.Date(line.timestamp))
                                sb.appendLine("[$t] $who: ${line.text}")
                            } else if (item.xfer != null) {
                                val t = item.xfer
                                val whenStr = tsFmt.format(java.util.Date(t.createdAt))
                                val who = if (t.direction == TransferDirection.OUTGOING) localDisplayName else titleName
                                val dirWord = if (t.direction == TransferDirection.OUTGOING) "sent file" else "incoming file"
                                val sizeStr = if (t.size > 0) humanBytes(t.size) else "unknown size"
                                val statusText = when (t.status) {
                                    TransferStatus.WAITING -> "waiting"
                                    TransferStatus.TRANSFERRING -> "transferring ${humanBytes(t.bytes)} / ${humanBytes(t.size)}"
                                    TransferStatus.DONE -> {
                                        val end = t.finishedAt ?: System.currentTimeMillis()
                                        val secs = ((end - t.createdAt) / 1000).coerceAtLeast(0)
                                        "completed in ${secs}s"
                                    }
                                    TransferStatus.FAILED -> "failed${t.error?.let { e -> ": $e" } ?: ""}"
                                    TransferStatus.REJECTED -> "rejected"
                                    TransferStatus.CANCELLED -> "cancelled"
                                }
                                sb.appendLine("[$whenStr] $who: $dirWord \"${t.name}\" ($sizeStr) — $statusText")
                            }
                        }

                        out.write(sb.toString().toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }.onSuccess {
                    activity?.lifecycleScope?.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Export complete", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    activity?.lifecycleScope?.launch(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val title = if (isGroup) {
                        if (groupSubject.isNotBlank()) "Chat — $groupSubject"
                        else "Group Chat with $initialCountForTitle people"
                    } else {
                        "Chat — $titleName ($host:$port)"
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isGroup) {
                            AssistChip(
                                onClick = { renameOpen = true },
                                label = { Text("Rename") }
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        // ⋮ overflow menu per accepted UI
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "More",
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename Chat") },
                                    onClick = { overflowOpen = false; renameOpen = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export chat") },
                                    onClick = {
                                        overflowOpen = false
                                        val suggested = "${sanitizeFileName(titleName)}-${System.currentTimeMillis()}.txt"
                                        exportLauncher.launch(suggested)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear chat") },
                                    onClick = {
                                        overflowOpen = false
                                        clearConfirmOpen = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (blockedLoaded && isBlockedForThisPeer) "Unblock user" else "Block user") },
                                    onClick = {
                                        overflowOpen = false
                                        scope.launch {
                                            val newState = !(blockedLoaded && isBlockedForThisPeer)
                                            val result = runCatching {
                                                peerStore.setBlocked(host, port, newState)
                                            }
                                            Toast.makeText(
                                                context,
                                                if (result.isSuccess) {
                                                    if (newState) "Blocked $titleName ($host:$port)" else "Unblocked $titleName ($host:$port)"
                                                } else "Failed to ${if (newState) "block" else "unblock"} user",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(12.dp)
            ) {

                // selection action bar (only when selecting)
                if (selectMode && selectedIds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedIds.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                // Copy selected message texts in message order
                                val text = buildString {
                                    messages.forEach { m ->
                                        if (selectedIds.contains(m.id)) appendLine(m.text)
                                    }
                                }.trimEnd()
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "Copied ${selectedIds.size} message(s)", Toast.LENGTH_SHORT).show()
                                clearSelection()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAEAEA))
                        ) { Text("Copy") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { clearSelection() },
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAEAEA))
                        ) { Text("Cancel") }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                ) {
                    var attachMenuOpen by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = input,
                        onValueChange = { v -> input = v },
                        label = { Text(text = "Type a message") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            Box {
                                IconButton(onClick = { attachMenuOpen = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "More",
                                        tint = orange
                                    )
                                }
                                DropdownMenu(
                                    expanded = attachMenuOpen,
                                    onDismissRequest = { attachMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Take Photo") },
                                        onClick = {
                                            attachMenuOpen = false
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                val resolver = context.contentResolver
                                                val name = "TransportChat_${System.currentTimeMillis()}.jpg"
                                                val values = ContentValues().apply {
                                                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TransportChat")
                                                }
                                                val uri = resolver.insert(
                                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                                                )
                                                if (uri != null) {
                                                    cameraUri.value = uri
                                                    takePicture.launch(uri)
                                                } else {
                                                    Toast.makeText(context, "Unable to open camera", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Camera requires Android 10+", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Attach File") },
                                        onClick = {
                                            attachMenuOpen = false
                                            getContent.launch("*/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Send Location") },
                                        onClick = {
                                            attachMenuOpen = false
                                            requestAndSendLocation(context, host, port)
                                        }
                                    )
                                }
                            }
                        }
                    )

                    Button(
                        onClick = {
                            val textToSend = input.trim()
                            if (textToSend.isNotEmpty()) {
                                val globalId = UUID.randomUUID().toString()
                                for (p in participants) {
                                    ChatStore.appendOutgoing(p.host, p.port, textToSend, id = globalId)
                                }
                                input = ""
                                for (p in participants) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { ChatClient.sendSecureText(p.host, p.port, globalId, textToSend) }
                                            .onFailure {
                                                ChatStore.appendIncoming(p.host, p.port, "⚠️ Send failed: ${it.message}")
                                            }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = orange, contentColor = Color.White)
                    ) { Text("Send") }
                }
            }
        }
    ) { paddingVals ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVals)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(count = timeline.size) { idx ->
                    val item = timeline[idx]
                    if (item.msg != null) {
                        val line = item.msg
                        val bubbleColor: Color
                        val bubbleOn: Color
                        if (line.outgoing) {
                            bubbleColor = orange
                            bubbleOn = Color.White
                        } else {
                            bubbleColor = MaterialTheme.colorScheme.surfaceVariant
                            bubbleOn = MaterialTheme.colorScheme.onSurface
                        }

                        val linkColor = if (line.outgoing) Color.White else orange
                        val annotatedLinks = remember(line.text) { buildAnnotatedStringForLinks(line.text, linkColor) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start
                        ) {
                            // --- bullet indicator in selection mode ---
                            if (selectMode) {
                                val selected = selectedIds.contains(line.id)
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(2.dp, orange, CircleShape)
                                        .background(if (selected) orange else Color.Transparent, CircleShape)
                                        .clickable { toggleSelect(line.id) }
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            // ----------------------------------------------

                            Surface(
                                color = bubbleColor,
                                contentColor = bubbleOn,
                                tonalElevation = if (line.outgoing) 4.dp else 1.dp,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                // long-press on the bubble to toggle selection
                                Column(
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = { /* ClickableText handles URL taps */ },
                                            onLongClick = {
                                                selectMode = true
                                                toggleSelect(line.id)
                                            }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .widthIn(max = 320.dp)
                                ) {
                                    ClickableText(
                                        text = annotatedLinks,
                                        style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                                        onClick = { offset ->
                                            annotatedLinks.getStringAnnotations("URL", offset, offset)
                                                .firstOrNull()
                                                ?.let { openUrl(context, it.item) }
                                        },
                                        modifier = Modifier
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (line.outgoing) {
                                            val status = when {
                                                line.readAt != null -> "✓✓ ${timeShort(line.readAt)}"
                                                line.delivered -> "✓ Delivered"
                                                else -> "… Sending"
                                            }
                                            Text(
                                                text = status,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = bubbleOn.copy(alpha = 0.85f)
                                            )
                                        } else {
                                            Text(
                                                text = timeShort(line.timestamp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = bubbleOn.copy(alpha = 0.65f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (item.xfer != null) {
                        val t = item.xfer
                        val isOut = t.direction == TransferDirection.OUTGOING
                        val bubbleColor = if (isOut) orange else MaterialTheme.colorScheme.surfaceVariant
                        val bubbleOn = if (isOut) Color.White else MaterialTheme.colorScheme.onSurface

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = bubbleColor,
                                contentColor = bubbleOn,
                                tonalElevation = if (isOut) 4.dp else 1.dp,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(
                                        text = if (isOut) "You sent a file" else "Incoming file",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = t.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val statusText = when (t.status) {
                                        TransferStatus.TRANSFERRING -> "In progress…"
                                        TransferStatus.DONE -> "Complete"
                                        TransferStatus.REJECTED -> "Rejected"
                                        TransferStatus.FAILED -> "Failed"
                                        TransferStatus.CANCELLED -> "Cancelled"
                                        TransferStatus.WAITING -> "Waiting…"
                                        else -> t.status.toString().lowercase()
                                    }

                                    val sizeStr = if (t.size > 0) humanBytes(t.size) else "Unknown size"
                                    if (t.status == TransferStatus.TRANSFERRING) {
                                        // Progress bar
                                        if (t.size > 0) {
                                            val pct = (t.bytes.toFloat() / t.size.toFloat()).coerceIn(0f, 1f)
                                            LinearProgressIndicator(progress = pct, modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp))
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp))
                                        }
                                        // ETA
                                        val elapsedMs = (System.currentTimeMillis() - t.createdAt).coerceAtLeast(1L)
                                        val rate = if (elapsedMs > 0) t.bytes * 1000.0 / elapsedMs.toDouble() else 0.0
                                        val etaText = if (t.size > 0 && rate > 0.0) {
                                            val remaining = (t.size - t.bytes).coerceAtLeast(0)
                                            val secs = (remaining / rate).toLong().coerceAtLeast(0)
                                            etaMinsSecs(secs) + " left"
                                        } else {
                                            "estimating…"
                                        }
                                        Text(
                                            text = "${humanBytes(t.bytes)} / ${if (t.size > 0) humanBytes(t.size) else "?"}  $etaText",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                if (isOut) {
                                                    TransferCenter.requestCancel(t.id)
                                                } else {
                                                    ChatServer.cancelTransfer(t.id)
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAEAEA))
                                        ) { Text(text = "Cancel") }
                                    } else {
                                        Text(text = "$statusText • $sizeStr", style = MaterialTheme.typography.labelSmall)
                                    }

                                    if (!isOut && t.status == TransferStatus.DONE && t.savedTo != null) {
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(t.savedTo, t.mime ?: "*/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEAEAEA))
                                        ) { Text(text = "Open") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (openOfferDialog && pendingOfferId != null) {
        AlertDialog(
            onDismissRequest = { openOfferDialog = false },
            title = { Text(text = "Incoming file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = pendingOfferName)
                    Text(text = if (pendingOfferSize >= 0) humanBytes(pendingOfferSize) else "Unknown size")
                    if (!pendingOfferMime.isNullOrBlank()) Text(text = pendingOfferMime!!)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val suggested = if (pendingOfferName.isNotBlank()) pendingOfferName else "file"
                    saveLocationLauncher.launch(suggested)
                }) { Text(text = "Accept") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ChatServer.rejectOffer(pendingOfferId!!)
                    pendingOfferId = null
                }) { Text(text = "Reject") }
            }
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(text = "Send file?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Add a note (optional):")
                    OutlinedTextField(
                        value = note,
                        onValueChange = { v -> note = v },
                        label = { Text(text = "Note") },
                        singleLine = false
                        ,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = attachUri
                    if (uri != null) {
                        showConfirm = false
                        (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                            runCatching { ChatClient.sendFile(appCtx, host, port, uri) }
                                .onFailure {
                                    ChatStore.appendIncoming(host, port, "⚠️ File send failed: ${it.message}")
                                }
                        }
                    } else {
                        showConfirm = false
                    }
                }) { Text(text = "Send") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(text = "Cancel") }
            }
        )
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(text = "Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { v -> renameText = v },
                    label = { Text(text = "Display name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty()) {
                        if (isGroup) {
                            groupSubject = newName
                        } else {
                            NicknameCache.set(host = host, port = port, label = newName)
                            titleName = newName // <-- update title immediately
                        }
                    }
                    renameOpen = false
                }) { Text(text = "Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(text = "Cancel") }
            }
        )
    }

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text("Clear chat?") },
            text = { Text("This will remove all messages and file-transfer history for this peer (local only).") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirmOpen = false
                    // Clear messages
                    ChatStore.clearChat(host, port)
                    // Best-effort: clear transfers for this peer via reflection to avoid API coupling
                    runCatching {
                        val cls = TransferCenter::class.java
                        val methods = cls.methods
                        val candidates = methods.filter {
                            it.name in setOf("clearFor", "clearPeer", "clear", "removePeer", "resetPeer") &&
                                    it.parameterTypes.size == 2
                        }
                        val ok = candidates.firstOrNull { m ->
                            val a = m.parameterTypes[0]; val b = m.parameterTypes[1]
                            (a == String::class.java && (b == Int::class.javaPrimitiveType || b == java.lang.Integer::class.java)) ||
                                    ((a == Int::class.javaPrimitiveType || a == java.lang.Integer::class.java) && b == String::class.java)
                        }
                        ok?.let { m ->
                            if (m.parameterTypes[0] == String::class.java) {
                                m.invoke(TransferCenter, host, port)
                            } else {
                                m.invoke(TransferCenter, port, host)
                            }
                        }
                    }
                    // Fallback — prune snapshots for this peer (keep files)
                    runCatching {
                        val tc = TransferCenter::class.java
                        val f = tc.getDeclaredField("_snapshots")
                        f.isAccessible = true
                        val mutableState = f.get(TransferCenter)
                        val getValue = mutableState.javaClass.getMethod("getValue")
                        val setValue = mutableState.javaClass.getMethod("setValue", Any::class.java)
                        @Suppress("UNCHECKED_CAST")
                        val current = getValue.invoke(mutableState) as? Map<String, Any?> ?: emptyMap()
                        val newMap = current.filterValues { v ->
                            if (v == null) return@filterValues false
                            val kls = v.javaClass
                            val hostField = runCatching { kls.getDeclaredField("host").apply { isAccessible = true } }.getOrNull()
                            val portField = runCatching { kls.getDeclaredField("port").apply { isAccessible = true } }.getOrNull()
                            val h = hostField?.get(v) as? String
                            val p = (portField?.get(v) as? Int) ?: (portField?.get(v) as? java.lang.Integer)?.toInt()
                            !(h == host && p == port)
                        }
                        setValue.invoke(mutableState, newMap)
                    }
                    // Clear unread count
                    runCatching { UnreadCenter.clearFor(host, port) }
                    Toast.makeText(context, "Chat cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SimpleMenuItem(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.Filled.MoreVert, contentDescription = null)
    }
}

private fun sanitizeFileName(s: String): String {
    return s.replace(Regex("""[^\w\-. ]+"""), "_")
}

private fun defaultDisplayName(name: String): String {
    return if (name.isBlank()) {
        val model = android.os.Build.MODEL?.replace(Regex("""\s+"""), "") ?: "Device"
        "Transport-$model"
    } else name
}

private fun openUrl(ctx: Context, raw: String) {
    val fixed = if (raw.startsWith("www.", ignoreCase = true)) "http://$raw" else raw
    runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(fixed))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Open link"))
    }
}

private fun requestAndSendLocation(ctx: Context, host: String, port: Int) {
    Toast.makeText(ctx, "Location feature not implemented in this snippet", Toast.LENGTH_SHORT).show()
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

/** Non-@Composable helper to build link spans; ICU-safe. */
private fun buildAnnotatedStringForLinks(
    text: String,
    color: Color
): AnnotatedString {
    val pattern = """(https?://[\w\-\._~:/?#\[\]@!\$&'()*+,;=%]+)|(www\.[\w\-\._~:/?#\[\]@!\$&'()*+,;=%]+)"""
    val linkRegex = runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }
        .getOrElse { Regex("""https?://\S+|www\.\S+""", setOf(RegexOption.IGNORE_CASE)) }

    return buildAnnotatedString {
        var i = 0
        for (m in linkRegex.findAll(text)) {
            if (m.range.first > i) append(text.substring(i, m.range.first))
            val raw = text.substring(m.range)
            val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline)) { append(raw) }
            pop()
            i = m.range.last + 1
        }
        if (i < text.length) append(text.substring(i))
    }
}

/** Format an ETA like "2 mins 10 secs". */
private fun etaMinsSecs(totalSecs: Long): String {
    var s = totalSecs.coerceAtLeast(0)
    val m = s / 60
    s %= 60
    val parts = mutableListOf<String>()
    if (m > 0) parts += if (m == 1L) "1 min" else "$m mins"
    if (s > 0 || parts.isEmpty()) parts += if (s == 1L) "1 sec" else "$s secs"
    return parts.joinToString(" ")
}
