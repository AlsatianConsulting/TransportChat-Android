package dev.alsatianconsulting.transportchat.ui

import android.app.Application
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alsatianconsulting.transportchat.TransportChatApplication
import dev.alsatianconsulting.transportchat.core.AppContainer
import dev.alsatianconsulting.transportchat.data.model.Chat
import dev.alsatianconsulting.transportchat.data.model.ChatType
import dev.alsatianconsulting.transportchat.data.model.Contact
import dev.alsatianconsulting.transportchat.data.model.ActiveCallState
import dev.alsatianconsulting.transportchat.data.model.FileTransferOfferCodec
import dev.alsatianconsulting.transportchat.data.model.FileTransferOfferStatus
import dev.alsatianconsulting.transportchat.data.model.GroupMember
import dev.alsatianconsulting.transportchat.data.model.LockType
import dev.alsatianconsulting.transportchat.data.model.Message
import dev.alsatianconsulting.transportchat.data.model.MessageType
import dev.alsatianconsulting.transportchat.data.model.OutgoingTransferProgress
import dev.alsatianconsulting.transportchat.transport.service.LanTransportService
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLConnection
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

enum class GateMode {
    SETUP,
    LOCKED,
    READY
}

enum class DisappearingPreset(val seconds: Long?) {
    OFF(null),
    S30(30),
    M5(300),
    H1(3600),
    D1(86400),
    CUSTOM(null)
}

data class UiState(
    val gateMode: GateMode = GateMode.SETUP,
    val lockType: LockType? = null,
    val biometricEnabled: Boolean = false,
    val identityDisplayName: String = "",
    val identitySafetyPhrase: String = "",
    val localProfileId: String = "",
    val contacts: List<Contact> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val selectedChatId: Long? = null,
    val selectedContactId: Long? = null,
    val messages: List<Message> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val outgoingTransfers: List<OutgoingTransferProgress> = emptyList(),
    val activeCall: ActiveCallState? = null,
    val discoveredCount: Int = 0,
    val onlineProfileIds: Set<String> = emptySet(),
    val blockedProfileIds: Set<String> = emptySet(),
    val unreadByChatId: Map<Long, Int> = emptyMap(),
    val totalUnreadCount: Int = 0,
    val statusLine: String = "",
    val exportPath: String? = null,
    val disappearingPreset: DisappearingPreset = DisappearingPreset.OFF,
    val customExpireSeconds: Long = 0
)

class AppController(application: Application) : AndroidViewModel(application) {
    private val app = application as TransportChatApplication
    private val container: AppContainer = app.appContainer

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val lastReadMessageEpochByChatId = mutableMapOf<Long, Long>()

    init {
        container.ensureIdentity()
        refreshGateState()
        bindDataFlows()
    }

    private fun bindDataFlows() {
        viewModelScope.launch {
            container.chatRepository.contacts().collect { contacts ->
                _uiState.update { current ->
                    current.copy(contacts = contacts)
                }
                refreshSelectedMessages()
                recomputeUnreadCounts()
            }
        }

        viewModelScope.launch {
            container.chatRepository.chats().collect { chats ->
                _uiState.update { current -> current.copy(chats = chats) }
                refreshSelectedMessages()
                recomputeUnreadCounts()
                val selectedChatId = _uiState.value.selectedChatId
                if (selectedChatId != null) {
                    val selected = chats.firstOrNull { it.id == selectedChatId }
                    applyDisappearingForChat(selected)
                }
            }
        }

        viewModelScope.launch {
            container.chatRepository.chatContentVersion().collect {
                refreshSelectedMessages()
                recomputeUnreadCounts()
            }
        }

        viewModelScope.launch {
            container.peerRepository.peers().collect { peers ->
                val now = System.currentTimeMillis()
                val localProfileId = container.chatRepository.localProfileId()
                val activePeers = peers
                    .filter { now - it.discoveredAtEpochMs <= ONLINE_PEER_TTL_MS }
                    .filterNot { peer -> localProfileId.isNotBlank() && peer.profileId == localProfileId }
                    .groupBy { "${it.host}:${it.port}" }
                    .values
                    .mapNotNull { group -> group.maxByOrNull { it.discoveredAtEpochMs } }
                _uiState.update {
                    it.copy(
                        discoveredCount = activePeers.size,
                        onlineProfileIds = activePeers.map { peer -> peer.profileId }.toSet()
                    )
                }
            }
        }

        viewModelScope.launch {
            container.outgoingTransferProgress().collect { transfers ->
                _uiState.update { current -> current.copy(outgoingTransfers = transfers) }
            }
        }

        viewModelScope.launch {
            container.chatRepository.blockedProfileIds().collect { blocked ->
                _uiState.update { current -> current.copy(blockedProfileIds = blocked) }
                val selected = selectedContact()
                if (selected != null && blocked.contains(selected.profileId)) {
                    backToList()
                } else {
                    refreshSelectedMessages()
                    recomputeUnreadCounts()
                }
            }
        }

        viewModelScope.launch {
            container.activeCallState().collect { activeCall ->
                _uiState.update { current -> current.copy(activeCall = activeCall) }
            }
        }
    }

    fun configureLock(
        lockType: LockType,
        credential: String,
        biometricEnabled: Boolean
    ): Boolean {
        if (credential.isBlank()) return false

        val chars = credential.toCharArray()
        return try {
            val dbKey = container.lockManager.setupLock(lockType, chars, biometricEnabled)
            container.unlockDatabase(dbKey)
            startTransportService()
            container.chatRepository.addLocalSystemMessage(
                chatId = ensureOrCreateSelfLogChat(),
                text = "Security configured. Database unlocked."
            )
            refreshGateState()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Lock setup failed", t)
            false
        } finally {
            container.lockManager.ensureCharArrayWiped(chars)
        }
    }

    fun unlockWithCredential(credential: String): Boolean {
        if (credential.isBlank()) return false

        val chars = credential.toCharArray()
        return try {
            val dbKey = container.lockManager.unlockWithCredential(chars) ?: return false
            container.unlockDatabase(dbKey)
            startTransportService()
            refreshGateState()
            true
        } finally {
            container.lockManager.ensureCharArrayWiped(chars)
        }
    }

    fun unlockWithBiometricStore(): Boolean {
        val dbKey = container.lockManager.unlockWithBiometricStore() ?: return false
        container.unlockDatabase(dbKey)
        startTransportService()
        refreshGateState()
        return true
    }

    fun lockNow() {
        container.lockDatabase()
        stopTransportService()
        refreshGateState()
    }

    fun addManualPeer(
        host: String,
        port: Int,
        nickname: String
    ): Boolean {
        val normalizedHost = host.trim()
        val normalizedNickname = nickname.trim()
        if (normalizedHost.isBlank()) {
            return false
        }
        return runCatching {
            val discovered = container.peerRepository.freshestByEndpoint(normalizedHost, port)
                ?: container.fetchEndpointPeerProfile(normalizedHost, port)
                ?: error("No peer profile available at $normalizedHost:$port. Ensure the remote app is unlocked and reachable, then retry.")

            val contact = container.chatRepository.ingestPeer(
                peer = discovered.copy(
                    host = normalizedHost,
                    port = port,
                    discoveredAtEpochMs = System.currentTimeMillis()
                ),
                manual = true
            )
            if (normalizedNickname.isNotBlank()) {
                container.chatRepository.renameContact(contact.id, normalizedNickname)
            }
            val label = if (normalizedNickname.isNotBlank()) normalizedNickname else contact.effectiveName
            _uiState.update { it.copy(statusLine = "Manual peer added: $label") }
            true
        }.getOrElse {
            _uiState.update { state -> state.copy(statusLine = "Manual peer add failed: ${it.message}") }
            false
        }
    }

    fun selectContact(contact: Contact) {
        if (_uiState.value.blockedProfileIds.contains(contact.profileId)) {
            _uiState.update { it.copy(statusLine = "Blocked contact chats are hidden") }
            return
        }
        val chat = container.chatRepository.chatForContact(contact)
        markChatRead(chat.id)
        _uiState.update {
            it.copy(
                selectedContactId = contact.id,
                selectedChatId = chat.id,
                selectedMessageIds = emptySet(),
                statusLine = "Opened chat with ${contact.effectiveName}"
            )
        }
        applyDisappearingForChat(chat)
        refreshSelectedMessages()
        recomputeUnreadCounts()
    }

    fun openChat(chat: Chat) {
        markChatRead(chat.id)
        val contact = _uiState.value.contacts.firstOrNull { it.profileId == chat.remoteId }
        if (chat.type == ChatType.DIRECT && contact != null && _uiState.value.blockedProfileIds.contains(contact.profileId)) {
            _uiState.update { it.copy(statusLine = "Blocked contact chats are hidden") }
            return
        }
        val status = if (chat.type == ChatType.DIRECT && contact == null) {
            "Direct thread is stale. Open the peer from Contacts to create a current thread."
        } else {
            "Opened ${chat.title}"
        }
        _uiState.update {
            it.copy(
                selectedChatId = chat.id,
                selectedContactId = contact?.id,
                selectedMessageIds = emptySet(),
                statusLine = status
            )
        }
        applyDisappearingForChat(chat)
        refreshSelectedMessages()
        recomputeUnreadCounts()
    }

    fun backToList() {
        _uiState.update {
            it.copy(
                selectedChatId = null,
                selectedContactId = null,
                messages = emptyList(),
                selectedMessageIds = emptySet(),
                disappearingPreset = DisappearingPreset.OFF,
                customExpireSeconds = 0
            )
        }
    }

    fun setDisappearingPreset(preset: DisappearingPreset) {
        val chat = selectedChat() ?: return
        val currentCustom = _uiState.value.customExpireSeconds
        val seconds = when (preset) {
            DisappearingPreset.OFF -> null
            DisappearingPreset.S30 -> 30L
            DisappearingPreset.M5 -> 300L
            DisappearingPreset.H1 -> 3600L
            DisappearingPreset.D1 -> 86400L
            DisappearingPreset.CUSTOM -> currentCustom.takeIf { it > 0 } ?: run {
                _uiState.update { it.copy(statusLine = "Custom expiry must be greater than 0 seconds") }
                return
            }
        }
        container.updateChatDisappearingSeconds(chat.id, seconds)
        applyDisappearingForChat(chat.copy(disappearingSeconds = seconds))
    }

    fun setCustomExpirySeconds(seconds: Long) {
        val chat = selectedChat() ?: return
        val normalized = seconds.coerceAtLeast(0)
        if (normalized == 0L) {
            _uiState.update { it.copy(statusLine = "Custom expiry must be greater than 0 seconds") }
            return
        }
        container.updateChatDisappearingSeconds(chat.id, normalized)
        applyDisappearingForChat(chat.copy(disappearingSeconds = normalized))
    }

    fun sendMessage(text: String) {
        val chat = selectedChat() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val expiresAt = calculateExpiryEpochMs()
        if (chat.type == ChatType.GROUP) {
            val recipients = groupRecipientsForSend(chat) ?: return
            runCatching {
                container.sendGroupTextMessage(chat.id, recipients, trimmed, expiresAt)
                Log.i(TAG, "Sent group text in chat ${chat.id} to ${recipients.size} recipients")
            }.onFailure {
                Log.e(TAG, "Group send failed in chat ${chat.id}", it)
                _uiState.update { state ->
                    state.copy(statusLine = "Group send failed: ${it.message ?: "Signal session unavailable"}")
                }
            }
        } else {
            val contact = selectedContact() ?: run {
                _uiState.update {
                    it.copy(statusLine = "Direct thread is stale. Open the peer from Contacts to send.")
                }
                return
            }
            runCatching {
                container.sendTextMessage(contact, chat.id, trimmed, expiresAt)
                Log.i(TAG, "Sent text message to ${contact.profileId} in chat ${chat.id}")
            }.onFailure {
                Log.e(TAG, "Send message failed for ${contact.profileId} in chat ${chat.id}", it)
                _uiState.update { state ->
                    state.copy(statusLine = "Send failed: ${it.message ?: "Signal session unavailable"}")
                }
            }
        }
        refreshSelectedMessages()
    }

    fun addFeaturePlaceholder(action: String) {
        val chatId = _uiState.value.selectedChatId ?: return
        container.chatRepository.addLocalSystemMessage(
            chatId = chatId,
            text = "$action action queued (transport hook scaffolded)"
        )
        refreshSelectedMessages()
    }

    fun sendFileUri(uri: Uri) {
        sendFileUris(listOf(uri))
    }

    fun sendFileUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val uniqueUris = uris.distinct()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (uniqueUris.size == 1) {
                    sendPreparedFile(uniqueUris.first())
                } else {
                    val zipFile = createZipForUris(uniqueUris)
                    sendPreparedFile(
                        sourceUri = Uri.fromFile(zipFile),
                        overrideName = zipFile.name,
                        overrideMimeType = ZIP_MIME_TYPE
                    )
                }
            }.onFailure {
                _uiState.update { state -> state.copy(statusLine = "File send failed: ${it.message}") }
            }
        }
    }

    fun sendPhotoBitmap(bitmap: Bitmap) {
        val app = getApplication<Application>()
        val file = File(app.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        runCatching {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            sendFileUri(Uri.fromFile(file))
        }.onFailure {
            _uiState.update { state -> state.copy(statusLine = "Photo send failed: ${it.message}") }
        }
    }

    fun sendOneTimeLocation() {
        val chat = selectedChat() ?: return
        val locationManager = getApplication<Application>().getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            Log.e(TAG, "Location manager unavailable")
            _uiState.update { it.copy(statusLine = "Location service unavailable") }
            return
        }

        val location = runCatching {
            locationManager.getProviders(true)
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        }.getOrNull()

        if (location == null) {
            val providers = runCatching { locationManager.getProviders(true) }.getOrDefault(emptyList())
            Log.w(TAG, "No location available from providers=$providers")
            _uiState.update { it.copy(statusLine = "No location available (check permissions/GPS)") }
            return
        }

        runCatching {
            if (chat.type == ChatType.GROUP) {
                val recipients = groupRecipientsForSend(chat) ?: return@runCatching
                container.sendGroupLocationMessage(
                    chatId = chat.id,
                    recipients = recipients,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    expiresAtEpochMs = calculateExpiryEpochMs()
                )
            } else {
                val contact = selectedContact() ?: run {
                    _uiState.update {
                        it.copy(statusLine = "Direct thread is stale. Open the peer from Contacts to send.")
                    }
                    return
                }
                container.sendLocationMessage(
                    contact = contact,
                    chatId = chat.id,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    expiresAtEpochMs = calculateExpiryEpochMs()
                )
                Log.i(TAG, "Location sent to ${contact.profileId}")
            }
            refreshSelectedMessages()
            _uiState.update { it.copy(statusLine = "Location sent") }
        }.onFailure {
            Log.e(TAG, "Location send failed in chat ${chat.id}", it)
            _uiState.update { state -> state.copy(statusLine = "Location send failed: ${it.message}") }
        }
    }

    fun startVoiceCall() {
        val contact = selectedContact() ?: run {
            _uiState.update {
                it.copy(statusLine = "Direct thread is stale. Open the peer from Contacts to call.")
            }
            return
        }
        runCatching {
            container.startVoiceCall(contact)
            Log.i(TAG, "Voice call requested for ${contact.profileId}")
            _uiState.update { it.copy(statusLine = "Voice call starting with ${contact.effectiveName}") }
        }.onFailure {
            Log.e(TAG, "Voice call failed for ${contact.profileId}", it)
            _uiState.update { state -> state.copy(statusLine = "Voice call failed: ${it.message}") }
        }
    }

    fun startVideoCall() {
        val contact = selectedContact() ?: run {
            _uiState.update {
                it.copy(statusLine = "Direct thread is stale. Open the peer from Contacts to call.")
            }
            return
        }
        runCatching {
            container.startVideoCall(contact)
            Log.i(TAG, "Video call requested for ${contact.profileId}")
            _uiState.update { it.copy(statusLine = "Video call starting with ${contact.effectiveName}") }
        }.onFailure {
            Log.e(TAG, "Video call failed for ${contact.profileId}", it)
            _uiState.update { state -> state.copy(statusLine = "Video call failed: ${it.message}") }
        }
    }

    fun endCall() {
        val peerProfileId = _uiState.value.activeCall?.peerProfileId
        if (peerProfileId != null) {
            runCatching {
                container.endCall(peerProfileId)
                _uiState.update { it.copy(statusLine = "Call ended") }
            }.onFailure {
                Log.e(TAG, "End call failed for $peerProfileId", it)
                _uiState.update { state -> state.copy(statusLine = "End call failed: ${it.message}") }
            }
            return
        }

        val contact = selectedContact() ?: run {
            _uiState.update {
                it.copy(statusLine = "Direct thread is stale. Open the peer from Contacts to call.")
            }
            return
        }
        runCatching {
            container.endCall(contact)
            Log.i(TAG, "End call requested for ${contact.profileId}")
            _uiState.update { it.copy(statusLine = "Call ended") }
        }.onFailure {
            Log.e(TAG, "End call failed for ${contact.profileId}", it)
            _uiState.update { state -> state.copy(statusLine = "End call failed: ${it.message}") }
        }
    }

    fun acceptIncomingCall() {
        val activeCall = _uiState.value.activeCall ?: return
        container.acceptIncomingCall(activeCall.peerProfileId)
        _uiState.update { it.copy(statusLine = "Answering ${activeCall.displayName}") }
    }

    fun declineIncomingCall() {
        val activeCall = _uiState.value.activeCall ?: return
        container.declineIncomingCall(activeCall.peerProfileId)
        _uiState.update { it.copy(statusLine = "Declined call from ${activeCall.displayName}") }
    }

    fun callEglBaseContext(): EglBase.Context = container.callEglBaseContext()

    fun attachLocalCallRenderer(peerProfileId: String, renderer: SurfaceViewRenderer) {
        container.attachLocalCallRenderer(peerProfileId, renderer)
    }

    fun detachLocalCallRenderer(peerProfileId: String, renderer: SurfaceViewRenderer) {
        container.detachLocalCallRenderer(peerProfileId, renderer)
    }

    fun attachRemoteCallRenderer(peerProfileId: String, renderer: SurfaceViewRenderer) {
        container.attachRemoteCallRenderer(peerProfileId, renderer)
    }

    fun detachRemoteCallRenderer(peerProfileId: String, renderer: SurfaceViewRenderer) {
        container.detachRemoteCallRenderer(peerProfileId, renderer)
    }

    fun toggleCallMicrophone() {
        val activeCall = _uiState.value.activeCall ?: return
        val enabled = !activeCall.microphoneEnabled
        if (container.setCallMicrophoneEnabled(activeCall.peerProfileId, enabled)) {
            _uiState.update {
                it.copy(statusLine = if (enabled) "Microphone enabled" else "Microphone muted")
            }
        }
    }

    fun toggleCallCamera() {
        val activeCall = _uiState.value.activeCall ?: return
        if (activeCall.audioOnly) {
            _uiState.update { it.copy(statusLine = "Camera is unavailable in a voice call") }
            return
        }

        val enabled = !activeCall.cameraEnabled
        if (container.setCallCameraEnabled(activeCall.peerProfileId, enabled)) {
            _uiState.update {
                it.copy(statusLine = if (enabled) "Camera enabled" else "Camera paused")
            }
        } else {
            _uiState.update { it.copy(statusLine = "Camera is unavailable for this call") }
        }
    }

    fun updateStatus(message: String) {
        _uiState.update { state -> state.copy(statusLine = message) }
    }

    fun startMessageSelection(messageId: Long) {
        _uiState.update { state ->
            state.copy(selectedMessageIds = state.selectedMessageIds + messageId)
        }
    }

    fun toggleMessageSelection(messageId: Long) {
        _uiState.update { state ->
            val updated = if (state.selectedMessageIds.contains(messageId)) {
                state.selectedMessageIds - messageId
            } else {
                state.selectedMessageIds + messageId
            }
            state.copy(selectedMessageIds = updated)
        }
    }

    fun clearMessageSelection() {
        _uiState.update { state -> state.copy(selectedMessageIds = emptySet()) }
    }

    fun isSelectionMode(): Boolean = _uiState.value.selectedMessageIds.isNotEmpty()

    fun selectedMessagesCopyPayload(): String {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.isEmpty()) return ""
        return _uiState.value.messages
            .filter { selectedIds.contains(it.id) }
            .sortedBy { it.sentAtEpochMs }
            .joinToString(separator = "\n") { message ->
                "${message.senderProfileId}: ${message.decryptedPreview}"
            }
    }

    fun cancelOutgoingTransfer(transferId: String) {
        container.cancelOutgoingTransfer(transferId)
        _uiState.update { state -> state.copy(statusLine = "Cancel requested for transfer") }
    }

    fun acceptIncomingFileOffer(message: Message, saveUri: Uri? = null) {
        val offer = FileTransferOfferCodec.decode(message.encryptedBody)
            ?.takeIf { it.status == FileTransferOfferStatus.PENDING }
            ?: return
        container.acceptIncomingFileOffer(offer.transferId, saveUri)
        refreshSelectedMessages()
        _uiState.update { state -> state.copy(statusLine = "Accepted file offer: ${offer.fileName}") }
    }

    fun declineIncomingFileOffer(message: Message) {
        val offer = FileTransferOfferCodec.decode(message.encryptedBody)
            ?.takeIf { it.status == FileTransferOfferStatus.PENDING }
            ?: return
        container.declineIncomingFileOffer(offer.transferId)
        refreshSelectedMessages()
        _uiState.update { state -> state.copy(statusLine = "Declined file offer: ${offer.fileName}") }
    }

    fun isFileMessage(message: Message): Boolean {
        return message.type == MessageType.FILE || message.type == MessageType.IMAGE
    }

    fun renameContactNickname(nickname: String?) {
        val contact = selectedContact() ?: return
        container.chatRepository.renameContact(contact.id, nickname)
        _uiState.update { it.copy(statusLine = "Nickname updated for ${contact.displayName}") }
    }

    fun verifySelectedContact(observedPhrase: String) {
        val contact = selectedContact() ?: return
        container.verifyContact(contact, observedPhrase.trim())
        refreshSelectedMessages()
    }

    fun localVerificationQrPayload(): String {
        return JSONObject()
            .put("type", VERIFY_QR_TYPE)
            .put("profileId", container.identityManager.getProfileId().orEmpty())
            .put("safetyPhrase", container.identityManager.getSafetyPhrase().orEmpty())
            .toString()
    }

    fun localManualPeerJsonPayload(): String {
        val identity = container.ensureIdentity()
        val bundle = container.signalSessionManager.localPreKeyBundle()
        val payload = JSONObject()
            .put("profileId", identity.profileId)
            .put("displayName", identity.displayName)
            .put("publicKey", identity.publicKey)
            .put("safetyPhrase", identity.safetyPhrase)
            .put("signalRegistrationId", bundle.registrationId)
            .put("signalDeviceId", bundle.deviceId)
            .put("signalPreKeyId", bundle.preKeyId)
            .put("signalPreKeyPublicKey", bundle.preKeyPublicKey)
            .put("signalSignedPreKeyId", bundle.signedPreKeyId)
            .put("signalSignedPreKeyPublicKey", bundle.signedPreKeyPublicKey)
            .put("signalSignedPreKeySignature", bundle.signedPreKeySignature)
            .put("messagePort", AppContainer.MESSAGE_PORT)
        localIpv4Address()?.let { payload.put("host", it) }
        return payload.toString()
    }

    fun verifySelectedContactFromQrPayload(payload: String) {
        val contact = selectedContact() ?: return
        runCatching {
            val json = JSONObject(payload)
            val type = json.optString("type")
            require(type == VERIFY_QR_TYPE) { "Invalid verification QR" }
            val scannedProfileId = json.optString("profileId")
            val scannedPhrase = json.optString("safetyPhrase")
            require(scannedProfileId.isNotBlank() && scannedPhrase.isNotBlank()) {
                "Verification QR missing fields"
            }
            if (scannedProfileId != contact.profileId) {
                _uiState.update { state ->
                    state.copy(statusLine = "QR profile mismatch for ${contact.effectiveName}")
                }
                return
            }
            container.verifyContact(contact, scannedPhrase)
            refreshSelectedMessages()
            _uiState.update { state ->
                state.copy(statusLine = "QR verification saved for ${contact.effectiveName}")
            }
        }.onFailure {
            _uiState.update { state -> state.copy(statusLine = "QR verify failed: ${it.message}") }
        }
    }

    fun dismissVerificationWarning() {
        val contact = selectedContact() ?: return
        container.dismissVerification(contact.id)
        refreshSelectedMessages()
    }

    fun clearSelectedChat() {
        val chatId = _uiState.value.selectedChatId ?: return
        container.chatRepository.clearChat(chatId)
        refreshSelectedMessages()
        _uiState.update { it.copy(statusLine = "Chat cleared", selectedMessageIds = emptySet()) }
    }

    fun exportSelectedChat() {
        val chatId = _uiState.value.selectedChatId ?: return
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        val uri = container.chatRepository.exportChat(chatId, chat.title, visibleMessagesForChat(chat.id))
        _uiState.update { it.copy(exportPath = uri.path, statusLine = "Chat exported to ${uri.path}") }
    }

    fun renameSelectedChat(newName: String) {
        val chatId = _uiState.value.selectedChatId ?: return
        val normalized = newName.trim()
        if (normalized.isBlank()) return
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        runCatching {
            if (chat.type == ChatType.GROUP) {
                container.renameGroup(chatId, normalized)
            } else {
                container.chatRepository.renameChat(chatId, normalized)
            }
            _uiState.update { it.copy(statusLine = "Chat renamed to $normalized") }
        }.onFailure {
            _uiState.update { state ->
                state.copy(statusLine = "Rename failed: ${it.message ?: "Unable to rename chat"}")
            }
        }
    }

    fun updateLocalDisplayName(displayName: String) {
        val normalized = displayName.trim()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(statusLine = "Display name cannot be blank") }
            return
        }
        container.identityManager.updateDisplayName(normalized)
        refreshGateState()
        _uiState.update { it.copy(statusLine = "Display name updated to $normalized") }
    }

    fun createGroup(title: String, memberProfileIds: List<String>) {
        val normalizedMembers = memberProfileIds
            .distinct()
            .filterNot { _uiState.value.blockedProfileIds.contains(it) }
        if (title.isBlank()) {
            _uiState.update { it.copy(statusLine = "Group name is required") }
            return
        }
        if (normalizedMembers.isEmpty()) {
            _uiState.update { it.copy(statusLine = "Select at least 1 person to create a group") }
            return
        }
        val chatId = container.chatRepository.createGroup(title.trim(), normalizedMembers)
        markChatRead(chatId)
        _uiState.update {
            it.copy(
                selectedChatId = chatId,
                selectedContactId = null,
                selectedMessageIds = emptySet(),
                statusLine = "Group created"
            )
        }
        applyDisappearingForChat(selectedChat())
        refreshSelectedMessages()
        recomputeUnreadCounts()
    }

    fun createGroupFromSelectedConversation(title: String, additionalMemberProfileIds: List<String>) {
        val contact = selectedContact() ?: run {
            _uiState.update { it.copy(statusLine = "Open a direct conversation first") }
            return
        }
        val blocked = _uiState.value.blockedProfileIds
        if (blocked.contains(contact.profileId)) {
            _uiState.update { it.copy(statusLine = "Cannot create a group from a blocked contact") }
            return
        }
        val normalizedAdditional = additionalMemberProfileIds
            .distinct()
            .filterNot { it == contact.profileId }
            .filterNot { blocked.contains(it) }
        val members = (listOf(contact.profileId) + normalizedAdditional).distinct()
        createGroup(title = title, memberProfileIds = members)
    }

    fun isSelectedGroupAdmin(): Boolean {
        val chat = selectedChat() ?: return false
        return chat.type == ChatType.GROUP && container.chatRepository.isLocalGroupAdmin(chat.id)
    }

    fun canSendInSelectedGroup(): Boolean {
        val chat = selectedChat() ?: return false
        if (chat.type != ChatType.GROUP) return true
        return container.chatRepository.isLocalGroupMember(chat.id)
    }

    fun isLocalGroupMember(chatId: Long): Boolean {
        return container.chatRepository.isLocalGroupMember(chatId)
    }

    fun selectedGroupMembers(): List<GroupMember> {
        val chat = selectedChat() ?: return emptyList()
        if (chat.type != ChatType.GROUP) return emptyList()
        return container.chatRepository.groupMembers(chat.id)
    }

    fun availableContactsForSelectedGroup(): List<Contact> {
        val chat = selectedChat() ?: return emptyList()
        if (chat.type != ChatType.GROUP) return emptyList()
        return container.chatRepository.availableContactsForGroup(chat.id)
    }

    fun updateSelectedGroupMembers(memberProfileIds: List<String>) {
        val chat = selectedChat() ?: return
        if (chat.type != ChatType.GROUP) return
        runCatching {
            container.updateGroupMembers(chat.id, memberProfileIds)
            _uiState.update { it.copy(statusLine = "Group members updated") }
        }.onFailure {
            Log.e(TAG, "Group member update failed in chat ${chat.id}", it)
            _uiState.update { state ->
                state.copy(statusLine = "Group update failed: ${it.message ?: "Unable to sync group members"}")
            }
        }
    }

    fun leaveSelectedGroup() {
        val chat = selectedChat() ?: return
        if (chat.type != ChatType.GROUP) return
        runCatching {
            container.leaveGroup(chat.id)
            _uiState.update { it.copy(statusLine = "Left group ${chat.title}") }
            backToList()
        }.onFailure {
            Log.e(TAG, "Leave group failed in chat ${chat.id}", it)
            _uiState.update { state ->
                state.copy(statusLine = "Leave group failed: ${it.message ?: "Unable to leave group"}")
            }
        }
    }

    fun isSelectedContactBlocked(): Boolean {
        val contact = selectedContact() ?: return false
        return _uiState.value.blockedProfileIds.contains(contact.profileId)
    }

    fun blockSelectedContact() {
        val contact = selectedContact() ?: return
        container.chatRepository.blockProfile(contact.profileId)
        _uiState.update { it.copy(statusLine = "Blocked ${contact.effectiveName}") }
        backToList()
    }

    fun unblockSelectedContact() {
        val contact = selectedContact() ?: return
        container.chatRepository.unblockProfile(contact.profileId)
        _uiState.update { it.copy(statusLine = "Unblocked ${contact.effectiveName}") }
    }

    fun blockedContacts(): List<Contact> {
        val blocked = _uiState.value.blockedProfileIds
        return _uiState.value.contacts.filter { blocked.contains(it.profileId) }
            .sortedBy { it.effectiveName.lowercase() }
    }

    fun unblockProfile(profileId: String) {
        if (profileId.isBlank()) return
        container.chatRepository.unblockProfile(profileId)
        val contact = _uiState.value.contacts.firstOrNull { it.profileId == profileId }
        val label = contact?.effectiveName ?: profileId.take(12)
        _uiState.update { it.copy(statusLine = "Unblocked $label") }
    }

    fun selectedChat(): Chat? {
        val chatId = _uiState.value.selectedChatId ?: return null
        return _uiState.value.chats.firstOrNull { it.id == chatId }
    }

    fun selectedContact(): Contact? {
        val contactId = _uiState.value.selectedContactId ?: return null
        return _uiState.value.contacts.firstOrNull { it.id == contactId }
    }

    fun showVerificationWarning(): Boolean {
        val contact = selectedContact() ?: return false
        return container.verificationWarningNeeded(contact)
    }

    fun refreshSelectedMessages() {
        val chatId = _uiState.value.selectedChatId
        if (chatId == null || !container.chatRepository.isUnlocked()) {
            _uiState.update { it.copy(messages = emptyList(), selectedMessageIds = emptySet()) }
            return
        }
        val messages = visibleMessagesForChat(chatId)
        if (messages.isNotEmpty()) {
            val epoch = messages.maxOf { it.sentAtEpochMs }
            lastReadMessageEpochByChatId[chatId] = epoch
            viewModelScope.launch(Dispatchers.IO) {
                container.chatRepository.markChatRead(chatId, epoch)
            }
        }
        val validIds = messages.map { it.id }.toSet()
        _uiState.update { state ->
            state.copy(
                messages = messages,
                selectedMessageIds = state.selectedMessageIds.filterTo(mutableSetOf()) { validIds.contains(it) }
            )
        }
        val chat = selectedChat()
        val contact = selectedContact()
        if (chat?.type == ChatType.DIRECT && contact != null) {
            viewModelScope.launch(Dispatchers.IO) {
                container.sendReadAcks(chatId, contact, messages)
            }
        }
    }

    private fun markChatRead(chatId: Long) {
        val latestEpoch = container.chatRepository.messages(chatId).maxOfOrNull { it.sentAtEpochMs } ?: 0L
        lastReadMessageEpochByChatId[chatId] = latestEpoch
        viewModelScope.launch(Dispatchers.IO) {
            container.chatRepository.markChatRead(chatId, latestEpoch)
        }
    }

    private fun recomputeUnreadCounts() {
        if (!container.chatRepository.isUnlocked()) {
            _uiState.update { it.copy(unreadByChatId = emptyMap(), totalUnreadCount = 0) }
            return
        }
        val localProfileId = container.identityManager.getProfileId().orEmpty()
        val chats = _uiState.value.chats
        val selectedChatId = _uiState.value.selectedChatId
        val unreadByChat = mutableMapOf<Long, Int>()
        val blocked = _uiState.value.blockedProfileIds
        chats.forEach { chat ->
            if (chat.id == selectedChatId) return@forEach  // currently open — always 0
            if (chat.type == ChatType.DIRECT && blocked.contains(chat.remoteId)) return@forEach
            val lastRead = lastReadMessageEpochByChatId[chat.id] ?: 0L
            val unreadCount = visibleMessagesForChat(chat.id).count { message ->
                message.senderProfileId != localProfileId && message.sentAtEpochMs > lastRead
            }
            if (unreadCount > 0) {
                unreadByChat[chat.id] = unreadCount
            }
        }
        _uiState.update {
            it.copy(
                unreadByChatId = unreadByChat,
                totalUnreadCount = unreadByChat.values.sum()
            )
        }
    }

    private fun calculateExpiryEpochMs(): Long? {
        val state = _uiState.value
        val fallbackSeconds = when (state.disappearingPreset) {
            DisappearingPreset.OFF -> null
            DisappearingPreset.S30 -> 30L
            DisappearingPreset.M5 -> 300L
            DisappearingPreset.H1 -> 3600L
            DisappearingPreset.D1 -> 86400L
            DisappearingPreset.CUSTOM -> state.customExpireSeconds.takeIf { it > 0 }
        }
        val seconds = selectedChat()?.disappearingSeconds ?: fallbackSeconds
        return seconds?.let { System.currentTimeMillis() + (it * 1000) }
    }

    private fun visibleMessagesForChat(chatId: Long): List<Message> {
        val chat = _uiState.value.chats.firstOrNull { it.id == chatId }
            ?: container.chatRepository.chatById(chatId)
            ?: return emptyList()
        val blocked = _uiState.value.blockedProfileIds
        val localProfileId = container.chatRepository.localProfileId()
        val joinedAtCutoff = if (chat.type == ChatType.GROUP) {
            container.chatRepository.localGroupJoinedAtEpochMs(chat.id)
        } else {
            null
        }
        return container.chatRepository.messages(chatId).filter { message ->
            val hideBlockedGroupMessage =
                chat.type == ChatType.GROUP &&
                    message.senderProfileId != localProfileId &&
                    blocked.contains(message.senderProfileId)
            val beforeGroupJoinWindow = joinedAtCutoff != null && message.sentAtEpochMs < joinedAtCutoff
            !hideBlockedGroupMessage && !beforeGroupJoinWindow
        }
    }

    private fun applyDisappearingForChat(chat: Chat?) {
        val seconds = chat?.disappearingSeconds
        val (preset, custom) = when (seconds) {
            null -> DisappearingPreset.OFF to 0L
            30L -> DisappearingPreset.S30 to 30L
            300L -> DisappearingPreset.M5 to 300L
            3600L -> DisappearingPreset.H1 to 3600L
            86400L -> DisappearingPreset.D1 to 86400L
            else -> DisappearingPreset.CUSTOM to seconds
        }
        _uiState.update { state ->
            state.copy(disappearingPreset = preset, customExpireSeconds = custom)
        }
    }

    private fun refreshGateState() {
        val unlockState = container.lockManager.getState(container.chatRepository.isUnlocked())
        val gate = when {
            !unlockState.configured -> GateMode.SETUP
            !unlockState.unlocked -> GateMode.LOCKED
            else -> GateMode.READY
        }
        _uiState.update {
            it.copy(
                gateMode = gate,
                lockType = unlockState.lockType,
                biometricEnabled = unlockState.biometricEnabled,
                identityDisplayName = container.identityManager.getDisplayName(),
                identitySafetyPhrase = container.identityManager.getSafetyPhrase().orEmpty(),
                localProfileId = container.identityManager.getProfileId().orEmpty()
            )
        }
        if (gate == GateMode.READY) {
            val persisted = container.chatRepository.loadLastReadEpochs()
            persisted.forEach { (chatId, epoch) ->
                lastReadMessageEpochByChatId.putIfAbsent(chatId, epoch)
            }
        }
    }

    private fun ensureOrCreateSelfLogChat(): Long {
        val selfId = container.identityManager.getProfileId().orEmpty()
        return container.chatRepository.createGroup("System", listOf(selfId))
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).name }
        }
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)
        }
    }

    private fun localIpv4Address(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { iface -> iface.isUp && !iface.isLoopback }
                .flatMap { iface -> iface.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun resolveSize(uri: Uri): Long? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return runCatching { File(path).length() }.getOrNull()
        }
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
    }

    private fun sendPreparedFile(
        sourceUri: Uri,
        overrideName: String? = null,
        overrideMimeType: String? = null
    ) {
        val chat = selectedChat() ?: return
        val resolver = getApplication<Application>().contentResolver
        val fileName = overrideName ?: resolveDisplayName(sourceUri) ?: "file_${System.currentTimeMillis()}"
        val mimeType = overrideMimeType
            ?: resolver.getType(sourceUri)
            ?: URLConnection.guessContentTypeFromName(fileName)
            ?: "application/octet-stream"
        val totalBytes = resolveSize(sourceUri)
        val expiresAt = calculateExpiryEpochMs()

        if (chat.type == ChatType.GROUP) {
            val recipients = groupRecipientsForSend(chat) ?: return
            container.sendGroupFile(
                chatId = chat.id,
                recipients = recipients,
                sourceUri = sourceUri,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                expiresAtEpochMs = expiresAt
            )
        } else {
            val contact = selectedContact() ?: run {
                _uiState.update {
                    it.copy(statusLine = "Direct thread is stale. Open the peer from Contacts to send.")
                }
                return
            }
            container.sendFile(
                contact = contact,
                chatId = chat.id,
                sourceUri = sourceUri,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                expiresAtEpochMs = expiresAt
            )
        }
        refreshSelectedMessages()
        _uiState.update { it.copy(statusLine = "File sent: $fileName") }
    }

    private fun groupRecipientsForSend(chat: Chat): List<Contact>? {
        if (chat.type != ChatType.GROUP) return emptyList()
        if (!container.chatRepository.isLocalGroupMember(chat.id)) {
            _uiState.update { it.copy(statusLine = "You are no longer a member of this group") }
            return null
        }
        val recipients = container.chatRepository.groupRecipientContacts(chat.id)
        if (recipients.isEmpty()) {
            _uiState.update { it.copy(statusLine = "No reachable recipients in group") }
            return null
        }
        return recipients
    }

    private fun createZipForUris(uris: List<Uri>): File {
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        val cacheDir = File(application.cacheDir, "outgoing-zips").apply { mkdirs() }
        val zipFile = File(cacheDir, "files_${System.currentTimeMillis()}.zip")
        val usedNames = mutableSetOf<String>()

        ZipOutputStream(zipFile.outputStream().buffered()).use { zipOut ->
            uris.forEachIndexed { index, uri ->
                val baseName = resolveDisplayName(uri)?.takeIf { it.isNotBlank() } ?: "file_${index + 1}"
                val entryName = uniqueZipEntryName(baseName, usedNames)
                val input = resolver.openInputStream(uri)
                    ?: if (uri.scheme == "file") {
                        val path = uri.path ?: error("Invalid file uri: $uri")
                        File(path).inputStream()
                    } else {
                        error("Unable to open input stream for $uri")
                    }

                input.use { stream ->
                    zipOut.putNextEntry(ZipEntry(entryName))
                    stream.copyTo(zipOut)
                    zipOut.closeEntry()
                }
            }
        }
        return zipFile
    }

    private fun uniqueZipEntryName(baseName: String, usedNames: MutableSet<String>): String {
        val sanitized = sanitizeEntryName(baseName)
        if (usedNames.add(sanitized)) return sanitized

        val dotIndex = sanitized.lastIndexOf('.')
        val stem = if (dotIndex > 0) sanitized.substring(0, dotIndex) else sanitized
        val extension = if (dotIndex > 0) sanitized.substring(dotIndex) else ""
        var counter = 2
        while (true) {
            val candidate = "$stem ($counter)$extension"
            if (usedNames.add(candidate)) return candidate
            counter += 1
        }
    }

    private fun sanitizeEntryName(name: String): String {
        val normalized = name.replace('\\', '/').substringAfterLast('/').trim()
        return normalized.ifBlank { "file" }
    }

    private fun startTransportService() {
        LanTransportService.start(getApplication())
    }

    private fun stopTransportService() {
        LanTransportService.stop(getApplication())
    }

    companion object {
        private const val TAG = "AppController"
        private const val VERIFY_QR_TYPE = "transportchat.verify.v1"
        private const val ZIP_MIME_TYPE = "application/zip"
        private const val ONLINE_PEER_TTL_MS = 25_000L
    }
}
