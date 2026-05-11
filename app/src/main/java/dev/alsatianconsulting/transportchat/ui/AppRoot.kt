package dev.alsatianconsulting.transportchat.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import dev.alsatianconsulting.transportchat.data.model.Chat
import dev.alsatianconsulting.transportchat.data.model.ChatType
import dev.alsatianconsulting.transportchat.data.model.CallDirection
import dev.alsatianconsulting.transportchat.data.model.CallPhase
import dev.alsatianconsulting.transportchat.data.model.Contact
import dev.alsatianconsulting.transportchat.data.model.ContactTrustStatus
import dev.alsatianconsulting.transportchat.data.model.FileTransferOfferCodec
import dev.alsatianconsulting.transportchat.data.model.FileTransferOfferStatus
import dev.alsatianconsulting.transportchat.data.model.GroupMember
import dev.alsatianconsulting.transportchat.data.model.GroupMemberRole
import dev.alsatianconsulting.transportchat.data.model.LockType
import dev.alsatianconsulting.transportchat.data.model.DeliveryStatus
import dev.alsatianconsulting.transportchat.data.model.Message
import dev.alsatianconsulting.transportchat.data.model.MessageType
import dev.alsatianconsulting.transportchat.data.model.OutgoingTransferProgress
import dev.alsatianconsulting.transportchat.ui.theme.WarningRed
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File
import java.net.URLConnection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import android.os.Build
import kotlinx.coroutines.delay

private val URL_REGEX = Regex("""https?://[^\s<>()]+""")

private data class PermStep(
    val permission: String,
    val icon: ImageVector,
    val title: String,
    val explanation: String
)
private enum class HomeTab { ACTIVE, GROUPS }

@Composable
fun TransportChatApp(
    state: UiState,
    controller: AppController,
    onBiometricUnlock: (onResult: (Boolean) -> Unit) -> Unit,
    onPickFile: () -> Unit,
    onCapturePhoto: () -> Unit,
    onSendLocation: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onScanVerificationQr: () -> Unit,
    onAcceptIncomingCall: () -> Unit,
    onDeclineIncomingCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onEndCall: () -> Unit,
    onRequestPermission: (permission: String, onResult: (Boolean) -> Unit) -> Unit = { _, _ -> }
) {
    when (state.gateMode) {
        GateMode.SETUP -> {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("perm_rationale", Context.MODE_PRIVATE) }
            var permsDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }

            if (!permsDone) {
                PermissionsStepFlow(
                    onRequestPermission = onRequestPermission,
                    onComplete = {
                        val editor = prefs.edit()
                        editor.putBoolean("onboarding_done", true)
                        listOf("camera_photo", "camera_qr", "location", "microphone", "video_call")
                            .forEach { editor.putBoolean(it, true) }
                        editor.apply()
                        permsDone = true
                    }
                )
            } else {
                SetupLockScreen(controller, onBiometricChallenge = onBiometricUnlock)
            }
        }
        GateMode.LOCKED -> UnlockScreen(
            lockType = state.lockType ?: LockType.PASSPHRASE,
            biometricEnabled = state.biometricEnabled,
            onUnlock = { credential -> controller.unlockWithCredential(credential) },
            onBiometricUnlock = onBiometricUnlock
        )

        GateMode.READY -> {
            if (state.selectedChatId == null) {
                HomeScreen(state, controller)
            } else {
                ChatScreen(
                    state = state,
                    controller = controller,
                    onPickFile = onPickFile,
                    onCapturePhoto = onCapturePhoto,
                    onSendLocation = onSendLocation,
                    onStartVoiceCall = onStartVoiceCall,
                    onScanVerificationQr = onScanVerificationQr,
                    onStartVideoCall = onStartVideoCall,
                    onEndCall = onEndCall
                )
            }
        }
    }

    state.activeCall?.let { activeCall ->
        ActiveCallOverlay(
            activeCall = activeCall,
            controller = controller,
            onAccept = onAcceptIncomingCall,
            onDecline = onDeclineIncomingCall,
            onEnd = onEndCall
        )
    }
}

@Composable
private fun PermissionsStepFlow(
    onRequestPermission: (String, (Boolean) -> Unit) -> Unit,
    onComplete: () -> Unit
) {
    val steps = remember {
        buildList {
            add(PermStep(
                permission = android.Manifest.permission.CAMERA,
                icon = Icons.Filled.CameraAlt,
                title = "Camera",
                explanation = "TransportChat uses your camera in two ways:\n\n• To scan a contact's verification QR code so you can confirm you are speaking with the right person.\n\n• To take photos you explicitly choose to send in a chat.\n\nThe camera is never accessed in the background and activates only when you tap the relevant button."
            ))
            add(PermStep(
                permission = android.Manifest.permission.RECORD_AUDIO,
                icon = Icons.Filled.Mic,
                title = "Microphone",
                explanation = "TransportChat uses your microphone only during voice or video calls that you initiate or explicitly accept.\n\nAll audio is encrypted end-to-end using the Signal Protocol and travels only over your local network — it never reaches the internet or any external server.\n\nThe microphone is never accessed when you are not in an active call."
            ))
            add(PermStep(
                permission = android.Manifest.permission.ACCESS_FINE_LOCATION,
                icon = Icons.Filled.LocationOn,
                title = "Location",
                explanation = "TransportChat can share your current position with a contact, but only when you explicitly tap the location button in a chat.\n\nYour coordinates are sent directly to that contact over the local network. They are never stored in the app database, logged, or transmitted to any server or third party."
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermStep(
                    permission = android.Manifest.permission.POST_NOTIFICATIONS,
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    explanation = "TransportChat uses notifications solely to alert you when an incoming call arrives while the app is in the background, so you do not miss it.\n\nTransportChat does not send marketing messages, usage analytics, promotional content, or any other unsolicited notifications."
                ))
            }
        }
    }

    var step by remember { mutableStateOf(0) }

    if (step <= steps.lastIndex) {
        val current = steps[step]
        PermissionExplanationScreen(
            step = current,
            stepNumber = step + 1,
            totalSteps = steps.size,
            onAllow = { onRequestPermission(current.permission) { step++ } },
            onSkip = { step++ }
        )
    } else {
        LaunchedEffect(Unit) { onComplete() }
    }
}

@Composable
private fun PermissionExplanationScreen(
    step: PermStep,
    stepNumber: Int,
    totalSteps: Int,
    onAllow: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step $stepNumber of $totalSteps",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Icon(
            step.icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = step.explanation,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
            Text("Allow ${step.title} Access")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun SetupLockScreen(
    controller: AppController,
    onBiometricChallenge: ((onResult: (Boolean) -> Unit) -> Unit)? = null
) {
    var selectedLockType by remember { mutableStateOf(LockType.PIN) }
    var credential by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val isPIN = selectedLockType == LockType.PIN
    val mismatch = credential.isNotEmpty() && confirm.isNotEmpty() && credential != confirm
    val canSubmit = credential.isNotEmpty() && credential == confirm

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (isPIN) "Create Your PIN" else "Create Your Passphrase",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This ${if (isPIN) "PIN" else "passphrase"} encrypts all your chats and contacts on this device.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Your ${if (isPIN) "PIN" else "passphrase"} cannot be reset. If you forget it, the only way to recover access is to clear all app data — permanently erasing all messages and contacts.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = credential,
            onValueChange = { credential = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (isPIN) "Enter PIN" else "Enter Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPIN) KeyboardType.NumberPassword else KeyboardType.Password
            ),
            textStyle = MaterialTheme.typography.titleLarge,
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (isPIN) "Confirm PIN" else "Confirm Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPIN) KeyboardType.NumberPassword else KeyboardType.Password
            ),
            textStyle = MaterialTheme.typography.titleLarge,
            isError = mismatch,
            supportingText = if (mismatch) { { Text("Does not match") } } else null,
            singleLine = true
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                controller.configureLock(
                    lockType = selectedLockType,
                    credential = credential,
                    biometricEnabled = false
                )
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isPIN) "Set PIN" else "Set Passphrase", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { selectedLockType = if (isPIN) LockType.PASSPHRASE else LockType.PIN; credential = ""; confirm = "" }
        ) {
            Text(if (isPIN) "Use a passphrase instead" else "Use a PIN instead")
        }
    }
}

@Composable
private fun UnlockScreen(
    lockType: LockType,
    biometricEnabled: Boolean,
    onUnlock: (String) -> Boolean,
    onBiometricUnlock: (onResult: (Boolean) -> Unit) -> Unit
) {
    var credential by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Unlock TransportChat", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = credential,
            onValueChange = { credential = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (lockType == LockType.PIN) "PIN" else "Passphrase") }
        )

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = WarningRed)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val ok = onUnlock(credential)
                if (!ok) {
                    error = "Unlock failed"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Unlock")
        }

        if (biometricEnabled) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    onBiometricUnlock { ok ->
                        if (!ok) error = "Biometric unlock failed"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unlock with biometrics")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: UiState, controller: AppController) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showManualDialog by remember { mutableStateOf(false) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPeerJsonDialog by remember { mutableStateOf(false) }
    var showLocalVerificationQrDialog by remember { mutableStateOf(false) }
    var peerJsonPayload by remember { mutableStateOf("") }
    var localVerificationQrPayload by remember { mutableStateOf("") }
    var homeTab by remember { mutableStateOf(HomeTab.ACTIVE) }

    LaunchedEffect(state.statusLine) {
        if (state.statusLine.isNotBlank()) {
            snackbarHostState.showSnackbar(state.statusLine)
        }
    }
    val directChatsByProfile = remember(state.chats) {
        state.chats
            .filter { it.type == ChatType.DIRECT }
            .associateBy { it.remoteId }
    }
    val onlineContacts = remember(state.contacts, state.onlineProfileIds, state.blockedProfileIds) {
        state.contacts
            .filter { state.onlineProfileIds.contains(it.profileId) }
            .filterNot { state.blockedProfileIds.contains(it.profileId) }
            .sortedBy { it.effectiveName.lowercase() }
    }
    val groupChats = remember(state.chats) {
        state.chats
            .filter { it.type == ChatType.GROUP }
            .filter { controller.isLocalGroupMember(it.id) }
            .sortedBy { it.title.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TransportChat",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = "Add Peer")
                    }
                    IconButton(onClick = { showGroupDialog = true }) {
                        Icon(Icons.Filled.Group, contentDescription = "New Group")
                    }
                    IconButton(onClick = { showBlockedDialog = true }) {
                        Icon(Icons.Filled.Block, contentDescription = "Blocked")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { controller.lockNow() }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            Text("Discovered peers: ${state.discoveredCount}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Unread messages: ${state.totalUnreadCount}", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            TabRow(selectedTabIndex = if (homeTab == HomeTab.ACTIVE) 0 else 1) {
                Tab(
                    selected = homeTab == HomeTab.ACTIVE,
                    onClick = { homeTab = HomeTab.ACTIVE },
                    text = { Text("Active Users") }
                )
                Tab(
                    selected = homeTab == HomeTab.GROUPS,
                    onClick = { homeTab = HomeTab.GROUPS },
                    text = { Text("Groups") }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (homeTab == HomeTab.ACTIVE) {
                    if (onlineContacts.isEmpty()) {
                        item {
                            Text("No active users", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(onlineContacts, key = { "online-contact-${it.id}" }) { contact ->
                        val directChat = directChatsByProfile[contact.profileId]
                        val unreadCount = directChat?.let { state.unreadByChatId[it.id] ?: 0 } ?: 0
                        UnifiedConversationRow(
                            title = directChat?.title ?: contact.effectiveName,
                            subtitle = "Online • ${contact.host}:${contact.port}",
                            unreadCount = unreadCount,
                            verified = contact.trustStatus == ContactTrustStatus.VERIFIED,
                            onClick = { controller.selectContact(contact) }
                        )
                    }
                } else {
                    if (groupChats.isEmpty()) {
                        item {
                            Text("No groups", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(groupChats, key = { "group-chat-${it.id}" }) { chat ->
                        UnifiedConversationRow(
                            title = chat.title,
                            subtitle = "Group",
                            unreadCount = state.unreadByChatId[chat.id] ?: 0,
                            onClick = { controller.openChat(chat) }
                        )
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualPeerDialog(
            onDismiss = { showManualDialog = false },
            onSubmit = { host, port, nickname ->
                controller.addManualPeer(
                    host = host,
                    port = port,
                    nickname = nickname
                )
                showManualDialog = false
            }
        )
    }

    if (showGroupDialog) {
        CreateGroupDialog(
            contacts = dedupeContactsForGroupSelection(
                state.contacts.filterNot { state.blockedProfileIds.contains(it.profileId) }
            ),
            onDismiss = { showGroupDialog = false },
            onCreate = { title, members ->
                controller.createGroup(title, members)
                showGroupDialog = false
            }
        )
    }

    if (showPeerJsonDialog && peerJsonPayload.isNotBlank()) {
        PeerJsonDialog(
            payload = peerJsonPayload,
            onDismiss = { showPeerJsonDialog = false }
        )
    }

    if (showSettingsDialog) {
        HomeSettingsDialog(
            displayName = state.identityDisplayName,
            safetyPhrase = state.identitySafetyPhrase,
            onDismiss = { showSettingsDialog = false },
            onSaveDisplayName = { controller.updateLocalDisplayName(it) },
            onShowPeerJson = {
                peerJsonPayload = controller.localManualPeerJsonPayload()
                showPeerJsonDialog = true
            },
            onShowVerificationQr = {
                localVerificationQrPayload = controller.localVerificationQrPayload()
                showLocalVerificationQrDialog = true
            }
        )
    }

    if (showBlockedDialog) {
        BlockedProfilesDialog(
            blockedContacts = controller.blockedContacts(),
            blockedProfileIds = state.blockedProfileIds,
            onDismiss = { showBlockedDialog = false },
            onUnblock = { profileId -> controller.unblockProfile(profileId) }
        )
    }

    if (showLocalVerificationQrDialog && localVerificationQrPayload.isNotBlank()) {
        VerificationQrDialog(
            payload = localVerificationQrPayload,
            onDismiss = { showLocalVerificationQrDialog = false }
        )
    }
}

@Composable
private fun HomeSettingsDialog(
    displayName: String,
    safetyPhrase: String,
    onDismiss: () -> Unit,
    onSaveDisplayName: (String) -> Unit,
    onShowPeerJson: () -> Unit,
    onShowVerificationQr: () -> Unit
) {
    var editedDisplayName by remember(displayName) { mutableStateOf(displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = editedDisplayName,
                    onValueChange = { editedDisplayName = it },
                    label = { Text("Display name") },
                    singleLine = true
                )
                Text("Safety phrase", fontWeight = FontWeight.Bold)
                SelectionContainer {
                    Text(safetyPhrase, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onShowVerificationQr) {
                    Text("Display Verification QR")
                }
                TextButton(onClick = onShowPeerJson) {
                    Text("My Peer JSON (advanced)")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSaveDisplayName(editedDisplayName)
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PeerJsonDialog(
    payload: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My Peer JSON") },
        text = {
            SelectionContainer {
                Text(payload, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, payload)
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun BlockedProfilesDialog(
    blockedContacts: List<Contact>,
    blockedProfileIds: Set<String>,
    onDismiss: () -> Unit,
    onUnblock: (String) -> Unit
) {
    val contactByProfileId = remember(blockedContacts) { blockedContacts.associateBy { it.profileId } }
    val rows = remember(blockedProfileIds, contactByProfileId) {
        blockedProfileIds.toList().sortedBy { profileId ->
            contactByProfileId[profileId]?.effectiveName?.lowercase() ?: profileId
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Blocked Users") },
        text = {
            if (rows.isEmpty()) {
                Text("No blocked users")
            } else {
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(rows, key = { it }) { profileId ->
                        val contact = contactByProfileId[profileId]
                        val label = contact?.effectiveName ?: profileId.take(12)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label)
                                contact?.let {
                                    Text(
                                        text = "${it.host}:${it.port}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            TextButton(onClick = { onUnblock(profileId) }) { Text("Unblock") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    state: UiState,
    controller: AppController,
    onPickFile: () -> Unit,
    onCapturePhoto: () -> Unit,
    onSendLocation: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onScanVerificationQr: () -> Unit,
    onStartVideoCall: () -> Unit,
    onEndCall: () -> Unit
) {
    val chat = controller.selectedChat() ?: return
    val contact = controller.selectedContact()
    val isGroupChat = chat.type == ChatType.GROUP
    val isGroupAdmin = isGroupChat && controller.isSelectedGroupAdmin()
    val canSendInGroup = !isGroupChat || controller.canSendInSelectedGroup()
    val isVerifiedContact = contact?.trustStatus == ContactTrustStatus.VERIFIED
    val context = LocalContext.current
    val selectionCount = state.selectedMessageIds.size
    val transfersForChat = remember(state.outgoingTransfers, chat.id) {
        state.outgoingTransfers
            .filter { it.chatId == chat.id }
            .associateBy { it.messageId }
    }
    val messageListState = rememberLazyListState()
    var pendingScrollToLatest by remember(chat.id) { mutableStateOf(true) }
    val isNearLatestMessage by remember(messageListState) {
        derivedStateOf {
            val layoutInfo = messageListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) {
                true
            } else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= totalItems - 2
            }
        }
    }
    val latestMessageId = state.messages.lastOrNull()?.id

    var input by remember(chat.id) { mutableStateOf("") }
    var renameDialog by remember { mutableStateOf(false) }
    var nicknameDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showGroupMembersDialog by remember { mutableStateOf(false) }
    var showCreateGroupFromConversationDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showDisappearingMenu by remember { mutableStateOf(false) }
    var showCustomDisappearingDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var customDisappearingInput by remember(chat.id) { mutableStateOf(state.customExpireSeconds.toString()) }
    var qrPayload by remember { mutableStateOf("") }
    var pendingAcceptMessage by remember { mutableStateOf<dev.alsatianconsulting.transportchat.data.model.Message?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val msg = pendingAcceptMessage
        pendingAcceptMessage = null
        if (msg != null) {
            controller.acceptIncomingFileOffer(msg, uri)
        }
    }

    LaunchedEffect(chat.id, latestMessageId, pendingScrollToLatest) {
        val lastIndex = state.messages.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        if (pendingScrollToLatest || isNearLatestMessage) {
            messageListState.scrollToItem(lastIndex)
            pendingScrollToLatest = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chat.title)
                        if (isVerifiedContact) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Verified contact",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = { controller.backToList() }) { Text("Back") }
                },
                actions = {
                    if (selectionCount > 0) {
                        TextButton(
                            onClick = {
                                val payload = controller.selectedMessagesCopyPayload()
                                if (payload.isNotBlank()) {
                                    copyToClipboard(context, payload)
                                    controller.updateStatus("Copied $selectionCount message(s)")
                                }
                                controller.clearMessageSelection()
                            }
                        ) { Text("Copy") }
                        TextButton(onClick = { controller.clearMessageSelection() }) { Text("Cancel") }
                    } else {
                        if (!isGroupChat) {
                            IconButton(onClick = onStartVoiceCall) {
                                Icon(
                                    imageVector = Icons.Filled.Call,
                                    contentDescription = "Voice call"
                                )
                            }
                            IconButton(onClick = onStartVideoCall) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = "Video call"
                                )
                            }
                        }
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear") },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearConfirmDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export") },
                                onClick = {
                                    showOverflowMenu = false
                                    controller.exportSelectedChat()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showOverflowMenu = false
                                    renameDialog = true
                                }
                            )
                            if (contact != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Nickname") },
                                    onClick = {
                                        showOverflowMenu = false
                                        nicknameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Scan QR") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onScanVerificationQr()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Display QR") },
                                    onClick = {
                                        showOverflowMenu = false
                                        qrPayload = controller.localVerificationQrPayload()
                                        showQrDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Create Group") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showCreateGroupFromConversationDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (controller.isSelectedContactBlocked()) "Unblock" else "Block") },
                                    onClick = {
                                        showOverflowMenu = false
                                        if (controller.isSelectedContactBlocked()) {
                                            controller.unblockSelectedContact()
                                        } else {
                                            controller.blockSelectedContact()
                                        }
                                    }
                                )
                            } else if (isGroupChat) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Leave Group") },
                                    onClick = {
                                        showOverflowMenu = false
                                        controller.leaveSelectedGroup()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(10.dp)
        ) {
            if (contact != null && controller.showVerificationWarning()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarningRed.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Identity mismatch warning for ${contact.effectiveName}",
                            color = WarningRed,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { controller.dismissVerificationWarning() }) {
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (contact != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Safety phrase: ${contact.safetyPhrase}")
                    if (isVerifiedContact) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Verified contact",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                var verifyInput by remember(contact.id) { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = verifyInput,
                        onValueChange = { verifyInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Observed phrase") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { controller.verifySelectedContact(verifyInput) }) {
                        Text("Verify")
                    }
                }
            } else if (isGroupChat) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showGroupMembersDialog = true }) {
                        Text(if (isGroupAdmin) "Members" else "View Members")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isGroupAdmin) {
                        "You are the group admin"
                    } else {
                        "Only the group creator can add or remove members"
                    }
                )
            }

            if (isGroupChat && !canSendInGroup) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarningRed.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "You are no longer a member of this group. History is still available, but sending is disabled.",
                        color = WarningRed,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            val localProfileId = state.localProfileId
            val memberNameByProfileId: Map<String, String> = if (isGroupChat) {
                remember(chat.id) {
                    controller.selectedGroupMembers().associate { it.profileId to it.effectiveName }
                }
            } else {
                emptyMap()
            }

            Spacer(modifier = Modifier.height(8.dp))
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                state = messageListState,
                modifier = Modifier.weight(1f)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    val isOutgoing = message.senderProfileId == localProfileId
                    val senderDisplayName = if (isGroupChat && !isOutgoing) {
                        memberNameByProfileId[message.senderProfileId] ?: message.senderProfileId.take(8)
                    } else ""
                    MessageCard(
                        message = message,
                        selected = state.selectedMessageIds.contains(message.id),
                        transfer = transfersForChat[message.id],
                        selectionMode = selectionCount > 0,
                        isFileMessage = controller.isFileMessage(message),
                        isOutgoing = isOutgoing,
                        isGroupChat = isGroupChat,
                        senderDisplayName = senderDisplayName,
                        onTap = {
                            if (selectionCount > 0) {
                                controller.toggleMessageSelection(message.id)
                            }
                        },
                        onLongPress = { controller.startMessageSelection(message.id) },
                        onOpenFile = {
                            val path = message.encryptedBody
                            if (path.startsWith("content://")) {
                                val uri = Uri.parse(path)
                                val mimeType = context.contentResolver.getType(uri) ?: "*/*"
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { context.startActivity(intent) }
                                catch (_: ActivityNotFoundException) { controller.updateStatus("No app to open this file") }
                            } else {
                                val file = File(path)
                                if (!openFileInExternalViewer(context, file)) {
                                    controller.updateStatus("Unable to open file")
                                }
                            }
                        },
                        onCancelTransfer = { transferId ->
                            controller.cancelOutgoingTransfer(transferId)
                        },
                        onAcceptFileOffer = {
                            val offer = dev.alsatianconsulting.transportchat.data.model.FileTransferOfferCodec.decode(message.encryptedBody)
                            pendingAcceptMessage = message
                            saveFileLauncher.launch(offer?.fileName ?: "file")
                        },
                        onDeclineFileOffer = {
                            controller.declineIncomingFileOffer(message)
                        }
                    )
                }
            }

            state.exportPath?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Last export: $it", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        enabled = canSendInGroup
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Attach"
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("File") },
                            onClick = {
                                showAttachmentMenu = false
                                onPickFile()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Photo") },
                            onClick = {
                                showAttachmentMenu = false
                                onCapturePhoto()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Location") },
                            onClick = {
                                showAttachmentMenu = false
                                onSendLocation()
                            }
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { showDisappearingMenu = true },
                        enabled = canSendInGroup
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Disappearing messages"
                        )
                    }
                    DropdownMenu(
                        expanded = showDisappearingMenu,
                        onDismissRequest = { showDisappearingMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Off") },
                            onClick = {
                                showDisappearingMenu = false
                                controller.setDisappearingPreset(DisappearingPreset.OFF)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("30 seconds") },
                            onClick = {
                                showDisappearingMenu = false
                                controller.setDisappearingPreset(DisappearingPreset.S30)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("5 minutes") },
                            onClick = {
                                showDisappearingMenu = false
                                controller.setDisappearingPreset(DisappearingPreset.M5)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("1 hour") },
                            onClick = {
                                showDisappearingMenu = false
                                controller.setDisappearingPreset(DisappearingPreset.H1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("1 day") },
                            onClick = {
                                showDisappearingMenu = false
                                controller.setDisappearingPreset(DisappearingPreset.D1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Custom") },
                            onClick = {
                                showDisappearingMenu = false
                                showCustomDisappearingDialog = true
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank() && canSendInGroup) {
                            pendingScrollToLatest = true
                            controller.sendMessage(input)
                            input = ""
                        }
                    })
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    pendingScrollToLatest = true
                    controller.sendMessage(input)
                    input = ""
                }, enabled = canSendInGroup) {
                    Text("Send")
                }
            }
        }
    }

    if (showGroupMembersDialog && isGroupChat) {
        GroupMembersDialog(
            members = controller.selectedGroupMembers(),
            contacts = dedupeContactsForGroupSelection(controller.availableContactsForSelectedGroup()),
            isAdmin = isGroupAdmin,
            blockedProfileIds = state.blockedProfileIds,
            onDismiss = { showGroupMembersDialog = false },
            onSave = {
                controller.updateSelectedGroupMembers(it)
                showGroupMembersDialog = false
            }
        )
    }

    if (showCreateGroupFromConversationDialog && contact != null) {
        CreateGroupFromConversationDialog(
            primaryContact = contact,
            contacts = dedupeContactsForGroupSelection(
                state.contacts.filterNot {
                    it.profileId == contact.profileId || state.blockedProfileIds.contains(it.profileId)
                }
            ),
            onDismiss = { showCreateGroupFromConversationDialog = false },
            onCreate = { title, additionalMembers ->
                controller.createGroupFromSelectedConversation(title, additionalMembers)
                showCreateGroupFromConversationDialog = false
            }
        )
    }

    if (renameDialog) {
        RenameChatDialog(
            currentName = chat.title,
            onDismiss = { renameDialog = false },
            onRename = {
                controller.renameSelectedChat(it)
                renameDialog = false
            }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear chat") },
            text = { Text("Delete all messages in \"${chat.title}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        controller.clearSelectedChat()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarningRed
                    )
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (nicknameDialog && contact != null) {
        RenameNicknameDialog(
            currentName = contact.nickname.orEmpty(),
            onDismiss = { nicknameDialog = false },
            onRename = {
                controller.renameContactNickname(it)
                nicknameDialog = false
            }
        )
    }

    if (showQrDialog && qrPayload.isNotBlank()) {
        VerificationQrDialog(
            payload = qrPayload,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showCustomDisappearingDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDisappearingDialog = false },
            title = { Text("Custom disappearing timer") },
            text = {
                OutlinedTextField(
                    value = customDisappearingInput,
                    onValueChange = { customDisappearingInput = it },
                    label = { Text("Seconds") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    controller.setCustomExpirySeconds(customDisappearingInput.toLongOrNull() ?: 0L)
                    showCustomDisappearingDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDisappearingDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun dedupeContactsForGroupSelection(contacts: List<Contact>): List<Contact> {
    return contacts
        .groupBy { "${it.host}:${it.port}" }
        .values
        .map { endpointContacts ->
            endpointContacts.maxByOrNull { it.lastSeenEpochMs } ?: endpointContacts.first()
        }
        .sortedBy { it.effectiveName.lowercase() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageCard(
    message: Message,
    selected: Boolean,
    transfer: OutgoingTransferProgress?,
    selectionMode: Boolean,
    isFileMessage: Boolean,
    isOutgoing: Boolean,
    isGroupChat: Boolean,
    senderDisplayName: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onOpenFile: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onAcceptFileOffer: () -> Unit,
    onDeclineFileOffer: () -> Unit
) {
    if (message.type == MessageType.SYSTEM) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message.decryptedPreview,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        return
    }

    val bubbleOrange   = ComposeColor(0xFFE07818)
    val bubbleDark     = ComposeColor(0xFF2C2C2E)
    val bubbleSelected = MaterialTheme.colorScheme.primaryContainer
    val bubbleColor    = when {
        selected    -> bubbleSelected
        isOutgoing  -> bubbleOrange
        else        -> bubbleDark
    }
    val textColor = ComposeColor.White

    val attachmentPath = message.encryptedBody
    val offer = FileTransferOfferCodec.decode(attachmentPath)
    val isContentUri = attachmentPath.startsWith("content://")
    val hasAttachment = isFileMessage && attachmentPath.isNotBlank() && (
        isContentUri || File(attachmentPath).exists()
    )
    val activeTransfer = transfer?.takeIf { !it.isComplete && it.failedReason == null }
    val pendingOffer = offer?.takeIf { it.status == FileTransferOfferStatus.PENDING }
    val showAttachmentRow = activeTransfer != null || pendingOffer != null || hasAttachment

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        // Sender name above bubble — group incoming only
        if (isGroupChat && !isOutgoing && senderDisplayName.isNotBlank()) {
            Text(
                text = senderDisplayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .combinedClickable(
                    onClick = { if (selectionMode) onTap() },
                    onLongClick = onLongPress
                ),
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isOutgoing) 14.dp else 4.dp,
                bottomEnd   = if (isOutgoing) 4.dp  else 14.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleColor)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (selected) {
                    Text(
                        text = "Selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                LinkifiedText(text = message.decryptedPreview, textColor = textColor)

                if (isFileMessage && showAttachmentRow) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                activeTransfer != null -> activeTransfer.fileName
                                pendingOffer != null -> pendingOffer.fileName
                                hasAttachment -> if (isContentUri) {
                                    message.decryptedPreview.removePrefix("File: ").ifBlank { "File" }
                                } else {
                                    File(attachmentPath).name
                                }
                                offer != null -> offer.fileName
                                else -> "File unavailable"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (activeTransfer != null) {
                            TextButton(
                                enabled = !activeTransfer.cancelRequested,
                                onClick = { onCancelTransfer(activeTransfer.transferId) }
                            ) {
                                Text(if (activeTransfer.cancelRequested) "Canceling..." else "Cancel", color = textColor)
                            }
                        } else if (pendingOffer != null) {
                            Row {
                                TextButton(onClick = onDeclineFileOffer) { Text("Decline", color = textColor) }
                                TextButton(onClick = onAcceptFileOffer)  { Text("Accept",  color = textColor) }
                            }
                        } else if (hasAttachment) {
                            TextButton(onClick = onOpenFile) { Text("Open", color = textColor) }
                        }
                    }
                }

                transfer?.let { progress ->
                    Spacer(modifier = Modifier.height(6.dp))
                    TransferProgressSection(transfer = progress, textColor = textColor)
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Live countdown for disappearing messages
                val expiresAtMs = message.expiresAtEpochMs
                var remainingMs by remember(expiresAtMs) {
                    mutableStateOf(expiresAtMs?.let { it - System.currentTimeMillis() } ?: -1L)
                }
                if (expiresAtMs != null) {
                    LaunchedEffect(expiresAtMs) {
                        while (remainingMs > 0L) {
                            delay(500L)
                            remainingMs = expiresAtMs - System.currentTimeMillis()
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Left: countdown timer
                    if (expiresAtMs != null && remainingMs >= 0L) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Disappearing",
                            tint = textColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = formatCountdown(remainingMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Right: sent time + delivery receipt
                    Text(
                        text = formatMessageTime(message.sentAtEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    if (isOutgoing) {
                        when (message.deliveryStatus) {
                            DeliveryStatus.SENT -> { /* nothing — not yet delivered */ }
                            DeliveryStatus.DELIVERED -> {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = "Delivered",
                                    tint = textColor.copy(alpha = 0.75f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DeliveryStatus.READ -> {
                                Spacer(modifier = Modifier.width(4.dp))
                                if (message.readAtEpochMs > 0L) {
                                    Text(
                                        text = "Read ${formatMessageTime(message.readAtEpochMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ComposeColor(0xFFFFD580)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Icon(
                                    imageVector = Icons.Filled.DoneAll,
                                    contentDescription = "Read",
                                    tint = ComposeColor(0xFFFFD580),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferProgressSection(
    transfer: OutgoingTransferProgress,
    textColor: ComposeColor = ComposeColor.Unspecified
) {
    val mutedColor = textColor.copy(alpha = 0.75f)

    if (transfer.awaitingApproval) {
        Text(
            text = if (transfer.cancelRequested) "Canceling..." else "Awaiting receive approval",
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor
        )
        return
    }

    val progressFraction = transfer.totalBytes
        ?.takeIf { it > 0 }
        ?.let { transfer.sentBytes.toFloat() / it.toFloat() }

    val accentOrange = ComposeColor(0xFFE07818)
    if (progressFraction != null) {
        LinearProgressIndicator(
            progress = { progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = accentOrange,
            trackColor = textColor.copy(alpha = 0.2f)
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = accentOrange,
            trackColor = textColor.copy(alpha = 0.2f)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    val totalBytes = transfer.totalBytes
    val sentLabel = formatBytes(transfer.sentBytes)
    val totalLabel = totalBytes?.let(::formatBytes) ?: "?"
    val pctLabel = progressFraction?.let { " (${(it * 100).toInt()}%)" } ?: ""
    val etaLabel = transfer.etaSeconds?.let(::formatEta) ?: "--"
    Text(
        text = "$sentLabel / $totalLabel$pctLabel  •  ${formatSpeed(transfer.speedBytesPerSec)}  •  ETA $etaLabel",
        style = MaterialTheme.typography.labelSmall,
        color = mutedColor
    )

    val statusText = when {
        transfer.failedReason != null -> "Failed: ${transfer.failedReason}"
        transfer.cancelRequested && !transfer.isComplete -> "Canceling..."
        transfer.awaitingConfirmations ->
            "Awaiting confirmation (${transfer.confirmationsReceived}/${transfer.confirmationsExpected})"
        transfer.isComplete -> "Sent"
        else -> null
    }

    if (statusText != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (transfer.failedReason != null) WarningRed else mutedColor
        )
    }
}

@Composable
private fun LinkifiedText(text: String, textColor: ComposeColor = ComposeColor.Unspecified) {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    val annotated = remember(text, linkStyle) {
        buildAnnotatedString {
            var cursor = 0
            URL_REGEX.findAll(text).forEach { match ->
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val url = match.value
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(style = linkStyle)
                    )
                ) {
                    append(url)
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor
    )
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("transportchat", value))
}

@Composable
private fun ActiveCallOverlay(
    activeCall: dev.alsatianconsulting.transportchat.data.model.ActiveCallState,
    controller: AppController,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit
) {
    val accentColor = ComposeColor(0xFFE87722)
    val mutedButtonColor = ComposeColor(0xFF2A2A2A)
    val surfaceColor = ComposeColor(0xFF151515)
    val isIncomingRinging = activeCall.direction == CallDirection.INCOMING && activeCall.phase == CallPhase.RINGING
    val title = if (activeCall.audioOnly) "Voice Call" else "Video Call"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Black.copy(alpha = 0.96f))
            .zIndex(1f)
    ) {
        if (!activeCall.audioOnly && activeCall.phase != CallPhase.RINGING && activeCall.remoteVideoAvailable) {
            CallVideoSurface(
                controller = controller,
                peerProfileId = activeCall.peerProfileId,
                isLocal = false,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CallBackdrop(
                title = title,
                displayName = activeCall.displayName,
                statusText = activeCall.statusText,
                accentColor = accentColor
            )
        }

        if (!activeCall.audioOnly && activeCall.phase != CallPhase.RINGING) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(20.dp)
                    .size(width = 120.dp, height = 180.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                    .background(surfaceColor)
            ) {
                when {
                    activeCall.localVideoAvailable && activeCall.cameraEnabled -> CallVideoSurface(
                        controller = controller,
                        peerProfileId = activeCall.peerProfileId,
                        isLocal = true,
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> CallVideoUnavailableTile(
                        label = if (activeCall.cameraEnabled) "Starting camera" else "Camera off",
                        accentColor = accentColor
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(20.dp)
        ) {
            Text(text = title, color = accentColor, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = activeCall.displayName,
                color = ComposeColor.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = activeCall.statusText, color = ComposeColor.White.copy(alpha = 0.84f))
        }

        if (isIncomingRinging) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (activeCall.audioOnly) "Incoming voice call" else "Incoming video call",
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = activeCall.displayName,
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = activeCall.statusText, color = ComposeColor.White.copy(alpha = 0.84f))
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onDecline,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = mutedButtonColor,
                                contentColor = ComposeColor.White
                            )
                        ) {
                            Text("Decline")
                        }
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = ComposeColor.White
                            )
                        ) {
                            Text("Accept")
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CallControlButton(
                    label = if (activeCall.microphoneEnabled) "Mute" else "Unmute",
                    containerColor = if (activeCall.microphoneEnabled) accentColor else mutedButtonColor,
                    onClick = { controller.toggleCallMicrophone() }
                )
                if (!activeCall.audioOnly) {
                    CallControlButton(
                        label = if (activeCall.cameraEnabled) "Camera Off" else "Camera On",
                        containerColor = if (activeCall.cameraEnabled) accentColor else mutedButtonColor,
                        onClick = { controller.toggleCallCamera() }
                    )
                }
                CallControlButton(
                    label = if (activeCall.phase == CallPhase.ERROR) "Dismiss" else "End",
                    containerColor = ComposeColor(0xFFB95E18),
                    onClick = onEnd
                )
            }
        }
    }
}

@Composable
private fun CallBackdrop(
    title: String,
    displayName: String,
    statusText: String,
    accentColor: ComposeColor
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(accentColor.copy(alpha = 0.18f))
                    .border(1.dp, accentColor.copy(alpha = 0.8f), RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    color = accentColor,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(text = title, color = accentColor, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayName,
                color = ComposeColor.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = statusText, color = ComposeColor.White.copy(alpha = 0.82f))
        }
    }
}

@Composable
private fun CallVideoUnavailableTile(
    label: String,
    accentColor: ComposeColor
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = accentColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CallControlButton(
    label: String,
    containerColor: ComposeColor,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = ComposeColor.White
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Text(label)
    }
}

@Composable
private fun CallVideoSurface(
    controller: AppController,
    peerProfileId: String,
    isLocal: Boolean,
    modifier: Modifier = Modifier
) {
    val eglContext = remember(controller) { controller.callEglBaseContext() }
    val rendererState = remember(peerProfileId, isLocal) { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setMirror(isLocal)
                setZOrderMediaOverlay(isLocal)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                rendererState.value = this
                if (isLocal) {
                    controller.attachLocalCallRenderer(peerProfileId, this)
                } else {
                    controller.attachRemoteCallRenderer(peerProfileId, this)
                }
            }
        }
    )

    DisposableEffect(peerProfileId, isLocal) {
        onDispose {
            rendererState.value?.let { view ->
                if (isLocal) {
                    controller.detachLocalCallRenderer(peerProfileId, view)
                } else {
                    controller.detachRemoteCallRenderer(peerProfileId, view)
                }
                view.release()
            }
            rendererState.value = null
        }
    }
}

private fun openFileInExternalViewer(context: Context, file: File): Boolean {
    if (!file.exists() || !file.isFile) return false
    val fileUri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false

    val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return String.format("%.1f %s", value, units[index.coerceAtLeast(0)])
}

private fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

@Composable
private fun VerificationQrDialog(
    payload: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(payload) { createVerificationQrBitmap(payload, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verification QR") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Verification QR code",
                        modifier = Modifier.size(240.dp)
                    )
                } else {
                    Text("Unable to generate QR code")
                }
                Text("Share this code for identity verification.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun createVerificationQrBitmap(payload: String, sizePx: Int): Bitmap? {
    return runCatching {
        val bitMatrix = MultiFormatWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                val index = y * sizePx + x
                pixels[index] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}

@Composable
private fun UnifiedConversationRow(
    title: String,
    subtitle: String,
    unreadCount: Int,
    verified: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    if (verified) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Verified contact",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(subtitle)
            }
            if (unreadCount > 0) {
                Badge {
                    Text(unreadCount.toString())
                }
            }
        }
    }
}

private fun formatCountdown(ms: Long): String {
    if (ms <= 0L) return "0s"
    val seconds = (ms / 1000L).coerceAtLeast(1L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0   -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else        -> "${secs}s"
    }
}

private fun formatMessageTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val instant = Instant.ofEpochMilli(epochMs)
    val localDt = instant.atZone(ZoneId.systemDefault())
    val today = LocalDate.now(ZoneId.systemDefault())
    return if (localDt.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(localDt)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(localDt)
    }
}

private fun formatEpochMillis(epochMs: Long): String {
    if (epochMs <= 0L) return "Unknown time"
    val instant = Instant.ofEpochMilli(epochMs)
    val localTime = instant.atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(localTime)
}

@Composable
private fun ManualPeerDialog(
    onDismiss: () -> Unit,
    onSubmit: (
        host: String,
        port: Int,
        nickname: String
    ) -> Unit
) {
    var peerJson by remember { mutableStateOf("") }
    var parseStatus by remember { mutableStateOf<String?>(null) }
    var parseStatusIsError by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("53990") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Peer Add") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = peerJson,
                    onValueChange = { peerJson = it },
                    label = { Text("Peer JSON (optional)") }
                )
                Button(onClick = {
                    val parsed = runCatching { JSONObject(peerJson) }.getOrNull()
                    if (parsed == null) {
                        parseStatus = "Invalid JSON"
                        parseStatusIsError = true
                        return@Button
                    }
                    val parsedHost = sequenceOf(
                        parsed.optString("host"),
                        parsed.optString("ip"),
                        parsed.optString("address"),
                        parsed.optString("hostname")
                    ).firstOrNull { it.isNotBlank() }.orEmpty()
                    if (parsedHost.isNotBlank()) host = parsedHost

                    val parsedPort = when {
                        parsed.has("messagePort") -> parsed.optInt("messagePort")
                        parsed.has("port") -> parsed.optInt("port")
                        else -> 0
                    }
                    if (parsedPort > 0) port = parsedPort.toString()

                    parsed.optString("displayName").takeIf { it.isNotBlank() }?.let { nickname = it }

                    val missingFields = buildList {
                        if (host.isBlank()) add("host")
                    }

                    if (missingFields.isEmpty()) {
                        parseStatus = "Peer endpoint parsed"
                        parseStatusIsError = false
                    } else {
                        parseStatus = "Parsed with missing fields: ${missingFields.joinToString(", ")}"
                        parseStatusIsError = true
                    }
                }) {
                    Text("Parse JSON")
                }
                parseStatus?.let {
                    Text(
                        text = it,
                        color = if (parseStatusIsError) WarningRed else MaterialTheme.colorScheme.onSurface
                    )
                }
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP/Hostname") })
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("Nickname") })
                Text(
                    text = "Only endpoint and nickname are required. Identity details are resolved automatically from discovery.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(
                    host,
                    port.toIntOrNull() ?: 53990,
                    nickname
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CreateGroupDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Group") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Group Name") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected.contains(contact.profileId)) {
                                        selected.remove(contact.profileId)
                                    } else {
                                        selected.add(contact.profileId)
                                    }
                                }
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected.contains(contact.profileId),
                                onCheckedChange = {
                                    if (it) selected.add(contact.profileId) else selected.remove(contact.profileId)
                                }
                            )
                            Column {
                                Text(contact.effectiveName)
                                Text(
                                    text = "${contact.host}:${contact.port}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, selected.toList()) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun GroupMembersDialog(
    members: List<GroupMember>,
    contacts: List<Contact>,
    isAdmin: Boolean,
    blockedProfileIds: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val memberIds = remember(members) { members.map { it.profileId }.toSet() }
    val selected = remember(members) {
        mutableStateListOf<String>().apply {
            addAll(members.filterNot { it.isLocalProfile }.map { it.profileId })
        }
    }
    val memberLookup = remember(members) { members.associateBy { it.profileId } }
    val contactLookup = remember(contacts) { contacts.associateBy { it.profileId } }
    val optionIds = remember(members, contacts) {
        buildList {
            members.filterNot { it.isLocalProfile }.forEach { add(it.profileId) }
            contacts.forEach { contact ->
                if (!contains(contact.profileId)) add(contact.profileId)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isAdmin) "Manage Group" else "Group Members") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                members.firstOrNull { it.isLocalProfile }?.let { localMember ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(localMember.effectiveName)
                        Text(
                            text = if (localMember.role == GroupMemberRole.ADMIN) "Admin" else "Member",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(optionIds, key = { it }) { profileId ->
                        val groupMember = memberLookup[profileId]
                        val contact = contactLookup[profileId]
                        val label = groupMember?.effectiveName ?: contact?.effectiveName ?: profileId.take(12)
                        val role = groupMember?.role ?: GroupMemberRole.MEMBER
                        val checked = selected.contains(profileId)
                        val inGroup = memberIds.contains(profileId)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isAdmin) {
                                    if (!isAdmin) return@clickable
                                    if (checked) {
                                        selected.remove(profileId)
                                    } else if (!selected.contains(profileId)) {
                                        selected.add(profileId)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isAdmin) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            if (it) {
                                                if (!selected.contains(profileId)) selected.add(profileId)
                                            } else {
                                                selected.remove(profileId)
                                            }
                                        }
                                    )
                                }
                                Column {
                                    Text(label)
                                    val endpoint = contact?.let { "${it.host}:${it.port}" }
                                    Text(
                                        text = endpoint ?: "Profile ${profileId.take(12)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            Text(
                                text = when {
                                    role == GroupMemberRole.ADMIN -> "Admin"
                                    blockedProfileIds.contains(profileId) -> "Blocked"
                                    inGroup -> "Member"
                                    else -> "Not in group"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isAdmin) {
                TextButton(onClick = { onSave(selected.distinct()) }) { Text("Save") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isAdmin) "Cancel" else "Done") }
        }
    )
}

@Composable
private fun CreateGroupFromConversationDialog(
    primaryContact: Contact,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Group from Conversation") },
        text = {
            Column {
                Text(
                    text = "Required participant: ${primaryContact.effectiveName}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Group Name") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add optional additional people",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected.contains(contact.profileId)) {
                                        selected.remove(contact.profileId)
                                    } else {
                                        selected.add(contact.profileId)
                                    }
                                }
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected.contains(contact.profileId),
                                onCheckedChange = {
                                    if (it) selected.add(contact.profileId) else selected.remove(contact.profileId)
                                }
                            )
                            Text(contact.effectiveName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, selected.toList()) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameChatDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Chat") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Chat name") })
        },
        confirmButton = { TextButton(onClick = { onRename(value) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RenameNicknameDialog(currentName: String, onDismiss: () -> Unit, onRename: (String?) -> Unit) {
    var value by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local Nickname") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Nickname") })
        },
        confirmButton = { TextButton(onClick = { onRename(value.ifBlank { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
