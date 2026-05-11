package dev.alsatianconsulting.transportchat.core

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.alsatianconsulting.transportchat.data.db.EncryptedChatDatabase
import dev.alsatianconsulting.transportchat.data.model.Contact
import dev.alsatianconsulting.transportchat.data.model.ContactTrustStatus
import dev.alsatianconsulting.transportchat.data.model.DiscoveredPeer
import dev.alsatianconsulting.transportchat.data.model.ActiveCallState
import dev.alsatianconsulting.transportchat.data.model.CallDirection
import dev.alsatianconsulting.transportchat.data.model.CallPhase
import dev.alsatianconsulting.transportchat.data.model.ChatType
import dev.alsatianconsulting.transportchat.data.model.FileTransferOffer
import dev.alsatianconsulting.transportchat.data.model.FileTransferOfferCodec
import dev.alsatianconsulting.transportchat.data.model.FileTransferOfferStatus
import dev.alsatianconsulting.transportchat.data.model.DeliveryStatus
import dev.alsatianconsulting.transportchat.data.model.Message
import dev.alsatianconsulting.transportchat.data.model.MessageType
import dev.alsatianconsulting.transportchat.data.model.OutgoingTransferProgress
import dev.alsatianconsulting.transportchat.data.model.OutboundEnvelope
import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import dev.alsatianconsulting.transportchat.data.repo.ChatRepository
import dev.alsatianconsulting.transportchat.data.repo.GroupRoutingContext
import dev.alsatianconsulting.transportchat.data.repo.PeerRepository
import dev.alsatianconsulting.transportchat.security.AppLockManager
import dev.alsatianconsulting.transportchat.security.CryptoUtils
import dev.alsatianconsulting.transportchat.security.IdentityManager
import dev.alsatianconsulting.transportchat.security.PersistentSignalProtocolStore
import dev.alsatianconsulting.transportchat.security.SignalSessionManager
import dev.alsatianconsulting.transportchat.transport.discovery.LanPeerDiscovery
import dev.alsatianconsulting.transportchat.transport.discovery.PeerAnnouncement
import dev.alsatianconsulting.transportchat.transport.call.WebRtcCallManager
import dev.alsatianconsulting.transportchat.transport.messaging.InboundEnvelope
import dev.alsatianconsulting.transportchat.transport.messaging.LanMessageClient
import dev.alsatianconsulting.transportchat.transport.messaging.LanMessageProtocol
import dev.alsatianconsulting.transportchat.transport.messaging.LanMessageServer
import dev.alsatianconsulting.transportchat.transport.messaging.EndpointPeerProfile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import org.webrtc.EglBase
import org.webrtc.VideoSink

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var overlayJob: Job? = null

    val lockManager = AppLockManager(appContext)
    val identityManager = IdentityManager(appContext)
    private val signalProtocolStore = PersistentSignalProtocolStore(appContext, identityManager)
    val signalSessionManager = SignalSessionManager(identityManager, signalProtocolStore)
    val encryptedDb = EncryptedChatDatabase(appContext)
    val peerRepository = PeerRepository()
    val chatRepository = ChatRepository(
        context = appContext,
        db = encryptedDb,
        signalSessionManager = signalSessionManager,
        localProfileIdProvider = { identityManager.getProfileId() ?: "" }
    )

    private val messageClient = LanMessageClient()
    private val pendingInboundOffers = ConcurrentHashMap<String, PendingInboundOfferState>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransferState>()
    private val pendingSaveUris = ConcurrentHashMap<String, Uri>()
    private val outgoingTransfers = ConcurrentHashMap<String, OutgoingTransferState>()
    private val outgoingTransferBatches = ConcurrentHashMap<String, OutgoingTransferBatchState>()
    private val _outgoingTransferProgress = MutableStateFlow<List<OutgoingTransferProgress>>(emptyList())
    private val _activeCallState = MutableStateFlow<ActiveCallState?>(null)
    private val callManager = WebRtcCallManager(
        context = appContext,
        onSignalOut = signalOut@{ peerProfileId, payloadJson ->
            val contact = chatRepository.contactByProfileId(peerProfileId) ?: return@signalOut
            runCatching {
                val identity = ensureIdentity()
                val encrypted = signalSessionManager.encryptForPeer(
                    peerProfileId = contact.profileId,
                    peerIdentityPublicKeyB64 = contact.publicKey,
                    peerBundle = contactSignalBundle(contact),
                    plaintext = payloadJson
                )
                sendEnvelope(
                    contact = contact,
                    envelope = OutboundEnvelope(
                        chatId = chatRepository.chatForContact(contact).id,
                        senderProfileId = identity.profileId,
                        senderDeviceId = localSenderDeviceId(),
                        recipientProfileId = contact.profileId,
                        messageType = MessageType.CALL_SIGNAL,
                        signalCipherType = encrypted.cipherType,
                        ciphertext = encrypted.ciphertextB64,
                        sentAtEpochMs = System.currentTimeMillis()
                    ),
                    senderPublicKey = identity.publicKey
                )
            }.onFailure {
                Log.w(TAG, "Failed to send call signal", it)
            }
        },
        onIncomingCall = { peerProfileId, audioOnly ->
            updateActiveCallState(
                peerProfileId = peerProfileId,
                audioOnly = audioOnly,
                direction = CallDirection.INCOMING,
                phase = CallPhase.RINGING,
                statusText = if (audioOnly) "Incoming voice call" else "Incoming video call"
            )
        },
        onMediaStateChanged = { peerProfileId, microphoneEnabled, cameraEnabled, localVideoAvailable, remoteVideoAvailable ->
            val current = _activeCallState.value
            if (current != null && current.peerProfileId == peerProfileId) {
                _activeCallState.value = current.copy(
                    microphoneEnabled = microphoneEnabled,
                    cameraEnabled = cameraEnabled,
                    localVideoAvailable = localVideoAvailable,
                    remoteVideoAvailable = remoteVideoAvailable
                )
            }
        },
        onCallState = { peerProfileId, state ->
            Log.i(TAG, "Call state [$peerProfileId]: $state")
            val contact = chatRepository.contactByProfileId(peerProfileId)
            val name = contact?.effectiveName ?: peerProfileId
            val chatId = contact?.let { chatRepository.chatForContact(it).id }
            if (chatId != null && chatRepository.isUnlocked()) {
                chatRepository.addLocalSystemMessage(chatId, "Call state [$name]: $state")
            }
            applyCallStateUpdate(peerProfileId = peerProfileId, rawState = state)
        }
    )

    private val discovery = LanPeerDiscovery(
        context = appContext,
        localAnnouncementProvider = {
            val identity = ensureIdentity()
            val localSignalBundle = signalSessionManager.localPreKeyBundle()
            PeerAnnouncement(
                profileId = identity.profileId,
                displayName = identity.displayName,
                publicKey = identity.publicKey,
                safetyPhrase = identity.safetyPhrase,
                signalRegistrationId = localSignalBundle.registrationId,
                signalDeviceId = localSignalBundle.deviceId,
                signalPreKeyId = localSignalBundle.preKeyId,
                signalPreKeyPublicKey = localSignalBundle.preKeyPublicKey,
                signalSignedPreKeyId = localSignalBundle.signedPreKeyId,
                signalSignedPreKeyPublicKey = localSignalBundle.signedPreKeyPublicKey,
                signalSignedPreKeySignature = localSignalBundle.signedPreKeySignature,
                messagePort = MESSAGE_PORT
            )
        },
        knownPeerHostsProvider = {
            chatRepository.contacts().value
                .map { it.host }
                .filter { it.isNotBlank() }
                .distinct()
        },
        onPeerDiscovered = { peer ->
            val localProfileId = identityManager.getProfileId()
            if (!localProfileId.isNullOrBlank() && peer.profileId == localProfileId) {
                return@LanPeerDiscovery
            }
            peerRepository.upsert(peer)
            if (chatRepository.isUnlocked()) {
                chatRepository.ingestPeer(peer)
            }
        }
    )

    private val messageServer = LanMessageServer(
        port = MESSAGE_PORT,
        onInboundEnvelope = { envelope, sourceHost ->
            runCatching {
                if (!chatRepository.isUnlocked()) return@runCatching

                val localProfileId = identityManager.getProfileId() ?: return@runCatching
                if (envelope.senderProfileId == localProfileId) return@runCatching
                if (chatRepository.isProfileBlocked(envelope.senderProfileId)) return@runCatching

                val senderContact = chatRepository.contactByProfileId(envelope.senderProfileId)
                    ?: chatRepository.ingestPeer(
                        DiscoveredPeer(
                            profileId = envelope.senderProfileId,
                            displayName = "Peer $sourceHost",
                            publicKey = envelope.senderPublicKey,
                            safetyPhrase = CryptoUtils.safetyPhraseFromPublicKey(CryptoUtils.b64Decode(envelope.senderPublicKey)),
                            signalBundle = null,
                            host = sourceHost,
                            port = MESSAGE_PORT,
                            viaInterface = "inbound",
                            discoveredAtEpochMs = System.currentTimeMillis()
                        )
                    )

                val chatId = resolveInboundChatId(senderContact, envelope) ?: return@runCatching
                handleInboundEnvelope(chatId, senderContact, envelope)
                chatRepository.refreshChats()
            }.onFailure {
                Log.w(TAG, "Inbound message handling failed", it)
            }
        },
        onProfileRequest = {
            if (!chatRepository.isUnlocked()) {
                null
            } else {
                val identity = ensureIdentity()
                val localSignalBundle = signalSessionManager.localPreKeyBundle()
                EndpointPeerProfile(
                    profileId = identity.profileId,
                    displayName = identity.displayName,
                    publicKey = identity.publicKey,
                    safetyPhrase = identity.safetyPhrase,
                    signalBundle = SignalPreKeyBundleData(
                        registrationId = localSignalBundle.registrationId,
                        deviceId = localSignalBundle.deviceId,
                        preKeyId = localSignalBundle.preKeyId,
                        preKeyPublicKey = localSignalBundle.preKeyPublicKey,
                        signedPreKeyId = localSignalBundle.signedPreKeyId,
                        signedPreKeyPublicKey = localSignalBundle.signedPreKeyPublicKey,
                        signedPreKeySignature = localSignalBundle.signedPreKeySignature
                    ),
                    messagePort = MESSAGE_PORT
                )
            }
        }
    )

    fun ensureIdentity(displayName: String = "User-${UUID.randomUUID().toString().take(4)}") =
        identityManager.loadOrCreate(displayName)

    fun unlockDatabase(dbKey: ByteArray) {
        chatRepository.unlockDb(dbKey)
        ensureIdentity()
        chatRepository.refreshContacts()
        chatRepository.refreshChats()
    }

    fun lockDatabase() {
        chatRepository.lockDb()
    }

    fun startTransport() {
        discovery.start()
        messageServer.start()
        overlayJob = scope.launch {
            delay(OVERLAY_SCAN_INITIAL_DELAY_MS)
            while (isActive) {
                runCatching { scanOverlayNetworks() }
                    .onFailure { Log.d(TAG, "Overlay scan failed", it) }
                delay(OVERLAY_SCAN_INTERVAL_MS)
            }
        }
    }

    fun stopTransport() {
        discovery.stop()
        messageServer.stop()
        overlayJob?.cancel()
        overlayJob = null
        callManager.releaseAll()
    }

    private suspend fun scanOverlayNetworks() = coroutineScope {
        if (!chatRepository.isUnlocked()) return@coroutineScope
        val candidates = overlaySubnetCandidates()
        if (candidates.isEmpty()) return@coroutineScope
        Log.d(TAG, "Overlay scan: probing ${candidates.size} addresses across overlay interfaces")
        val localProfileId = identityManager.getProfileId() ?: return@coroutineScope
        candidates.map { ip ->
            async(Dispatchers.IO) {
                runCatching {
                    val profile = messageClient.requestPeerProfile(ip, MESSAGE_PORT, OVERLAY_SCAN_CONNECT_TIMEOUT_MS)
                        ?: return@async
                    if (profile.profileId == localProfileId) return@async
                    val safetyPhrase = if (profile.safetyPhrase.isNotBlank()) {
                        profile.safetyPhrase
                    } else {
                        CryptoUtils.safetyPhraseFromPublicKey(CryptoUtils.b64Decode(profile.publicKey))
                    }
                    val peer = DiscoveredPeer(
                        profileId = profile.profileId,
                        displayName = profile.displayName,
                        publicKey = profile.publicKey,
                        safetyPhrase = safetyPhrase,
                        signalBundle = profile.signalBundle,
                        host = ip,
                        port = profile.messagePort,
                        viaInterface = "overlay-scan",
                        discoveredAtEpochMs = System.currentTimeMillis()
                    )
                    peerRepository.upsert(peer)
                    chatRepository.ingestPeer(peer)
                }
            }
        }.awaitAll()
    }

    private fun overlaySubnetCandidates(): List<String> {
        val candidates = mutableSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return emptyList()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            val isOverlay = iface.isPointToPoint ||
                name.startsWith("zt") ||   // ZeroTier
                name.startsWith("wg") ||   // WireGuard
                name.startsWith("tun") ||  // Tailscale / OpenVPN tun
                name.startsWith("tap")     // OpenVPN tap
            if (!isOverlay) continue
            for (ifAddr in iface.interfaceAddresses) {
                val addr = ifAddr.address
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val octets = addr.address
                val localLast = octets[3].toInt() and 0xFF
                val prefix = "${octets[0].toInt() and 0xFF}.${octets[1].toInt() and 0xFF}.${octets[2].toInt() and 0xFF}"
                for (last in 1..254) {
                    if (last != localLast) candidates.add("$prefix.$last")
                }
            }
        }
        return candidates.toList()
    }

    fun outgoingTransferProgress(): StateFlow<List<OutgoingTransferProgress>> = _outgoingTransferProgress.asStateFlow()
    fun activeCallState(): StateFlow<ActiveCallState?> = _activeCallState.asStateFlow()
    fun callEglBaseContext(): EglBase.Context = callManager.eglBaseContext()

    fun fetchEndpointPeerProfile(host: String, port: Int): DiscoveredPeer? {
        if (!chatRepository.isUnlocked()) return null
        val profile = messageClient.requestPeerProfile(host = host, port = port) ?: return null
        val safetyPhrase = if (profile.safetyPhrase.isNotBlank()) {
            profile.safetyPhrase
        } else {
            CryptoUtils.safetyPhraseFromPublicKey(CryptoUtils.b64Decode(profile.publicKey))
        }
        return DiscoveredPeer(
            profileId = profile.profileId,
            displayName = profile.displayName,
            publicKey = profile.publicKey,
            safetyPhrase = safetyPhrase,
            signalBundle = profile.signalBundle,
            host = host,
            port = profile.messagePort,
            viaInterface = "endpoint_fetch",
            discoveredAtEpochMs = System.currentTimeMillis()
        )
    }

    fun attachLocalCallRenderer(peerProfileId: String, sink: VideoSink) {
        callManager.attachLocalVideoSink(peerProfileId, sink)
    }

    fun detachLocalCallRenderer(peerProfileId: String, sink: VideoSink) {
        callManager.detachLocalVideoSink(peerProfileId, sink)
    }

    fun attachRemoteCallRenderer(peerProfileId: String, sink: VideoSink) {
        callManager.attachRemoteVideoSink(peerProfileId, sink)
    }

    fun detachRemoteCallRenderer(peerProfileId: String, sink: VideoSink) {
        callManager.detachRemoteVideoSink(peerProfileId, sink)
    }

    fun setCallMicrophoneEnabled(peerProfileId: String, enabled: Boolean): Boolean =
        callManager.setMicrophoneEnabled(peerProfileId, enabled)

    fun setCallCameraEnabled(peerProfileId: String, enabled: Boolean): Boolean =
        callManager.setCameraEnabled(peerProfileId, enabled)

    fun cancelOutgoingTransfer(transferId: String) {
        val state = outgoingTransfers[transferId] ?: return
        state.batchId?.let { batchId ->
            cancelOutgoingTransferBatch(batchId)
            return
        }
        state.cancelRequested.set(true)
        if (state.awaitingApproval.get() && state.sentBytes == 0L) {
            markOutgoingTransferCanceled(transferId, sentBytes = 0L)
            chatRepository.updateTransferMessageStatus(
                messageId = state.messageId,
                decryptedPreview = "File send canceled: ${state.fileName}",
                filePath = ""
            )
            return
        }
        publishOutgoingTransferProgress()
    }

    fun acceptIncomingFileOffer(transferId: String, saveUri: Uri? = null) {
        val state = pendingInboundOffers[transferId] ?: return
        if (!state.pendingResponse.compareAndSet(true, false)) return
        if (saveUri != null) pendingSaveUris[transferId] = saveUri
        chatRepository.updateTransferMessageStatus(
            messageId = state.messageId,
            decryptedPreview = "Awaiting sender: ${state.fileName}",
            filePath = FileTransferOfferCodec.encode(state.offer.copy(status = FileTransferOfferStatus.ACCEPTED))
        )
        val contact = chatRepository.contactByProfileId(state.senderProfileId) ?: return
        sendFileOfferResponse(
            contact = contact,
            kind = FILE_ACCEPT_KIND,
            transferId = transferId
        )
    }

    fun declineIncomingFileOffer(transferId: String) {
        val state = pendingInboundOffers.remove(transferId) ?: return
        if (!state.pendingResponse.compareAndSet(true, false)) return
        chatRepository.updateTransferMessageStatus(
            messageId = state.messageId,
            decryptedPreview = "Declined file offer: ${state.fileName}",
            filePath = FileTransferOfferCodec.encode(state.offer.copy(status = FileTransferOfferStatus.DECLINED))
        )
        val contact = chatRepository.contactByProfileId(state.senderProfileId) ?: return
        sendFileOfferResponse(
            contact = contact,
            kind = FILE_DECLINE_KIND,
            transferId = transferId
        )
    }

    fun sendTextMessage(contact: Contact, chatId: Long, text: String, expiresAtEpochMs: Long?) {
        val identity = ensureIdentity()
        val sent = chatRepository.sendTextMessage(
            chatId = chatId,
            contact = contact,
            text = text,
            expiresAt = expiresAtEpochMs
        )

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = sent.type,
                signalCipherType = sent.signalCipherType,
                ciphertext = sent.encryptedBody,
                sentAtEpochMs = sent.sentAtEpochMs,
                expiresAtEpochMs = sent.expiresAtEpochMs
            ),
            senderPublicKey = identity.publicKey
        )
    }

    fun sendGroupTextMessage(
        chatId: Long,
        recipients: List<Contact>,
        text: String,
        expiresAtEpochMs: Long?
    ) {
        if (recipients.isEmpty()) return

        val identity = ensureIdentity()
        val routing = chatRepository.groupRoutingContext(chatId)
            ?: error("Group routing context missing for chat $chatId")
        chatRepository.addOutgoingGroupTextMessage(
            chatId = chatId,
            text = text,
            expiresAt = expiresAtEpochMs
        )

        recipients.forEach { contact ->
            val encrypted = signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = text
            )
            sendEnvelope(
                contact = contact,
                envelope = OutboundEnvelope(
                    chatId = chatId,
                    senderProfileId = identity.profileId,
                    senderDeviceId = localSenderDeviceId(),
                    recipientProfileId = contact.profileId,
                    groupId = routing.groupId,
                    groupTitle = routing.title,
                    groupMemberProfileIds = routing.memberProfileIds,
                    messageType = MessageType.TEXT,
                    signalCipherType = encrypted.cipherType,
                    ciphertext = encrypted.ciphertextB64,
                    sentAtEpochMs = System.currentTimeMillis(),
                    expiresAtEpochMs = expiresAtEpochMs
                ),
                senderPublicKey = identity.publicKey
            )
        }
    }

    fun sendLocationMessage(
        contact: Contact,
        chatId: Long,
        latitude: Double,
        longitude: Double,
        expiresAtEpochMs: Long?
    ) {
        val identity = ensureIdentity()
        val sent = chatRepository.sendLocationMessage(
            chatId = chatId,
            contact = contact,
            latitude = latitude,
            longitude = longitude,
            expiresAt = expiresAtEpochMs
        )

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.LOCATION,
                signalCipherType = sent.signalCipherType,
                ciphertext = sent.encryptedBody,
                sentAtEpochMs = sent.sentAtEpochMs,
                expiresAtEpochMs = sent.expiresAtEpochMs
            ),
            senderPublicKey = identity.publicKey
        )
    }

    fun sendGroupLocationMessage(
        chatId: Long,
        recipients: List<Contact>,
        latitude: Double,
        longitude: Double,
        expiresAtEpochMs: Long?
    ) {
        if (recipients.isEmpty()) return

        val identity = ensureIdentity()
        val routing = chatRepository.groupRoutingContext(chatId)
            ?: error("Group routing context missing for chat $chatId")
        val payloadJson = org.json.JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timestamp", System.currentTimeMillis())
            .toString()

        chatRepository.addOutgoingGroupLocationMessage(
            chatId = chatId,
            latitude = latitude,
            longitude = longitude,
            expiresAt = expiresAtEpochMs
        )

        recipients.forEach { contact ->
            val encrypted = signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = payloadJson
            )
            sendEnvelope(
                contact = contact,
                envelope = OutboundEnvelope(
                    chatId = chatId,
                    senderProfileId = identity.profileId,
                    senderDeviceId = localSenderDeviceId(),
                    recipientProfileId = contact.profileId,
                    groupId = routing.groupId,
                    groupTitle = routing.title,
                    groupMemberProfileIds = routing.memberProfileIds,
                    messageType = MessageType.LOCATION,
                    signalCipherType = encrypted.cipherType,
                    ciphertext = encrypted.ciphertextB64,
                    sentAtEpochMs = System.currentTimeMillis(),
                    expiresAtEpochMs = expiresAtEpochMs
                ),
                senderPublicKey = identity.publicKey
            )
        }
    }

    fun sendFile(
        contact: Contact,
        chatId: Long,
        sourceUri: Uri,
        fileName: String,
        mimeType: String,
        totalBytes: Long?,
        expiresAtEpochMs: Long?
    ) {
        val identity = ensureIdentity()
        val transferId = UUID.randomUUID().toString()
        val chunkCrypto = newTransferChunkCrypto()
        val messageType = mimeToMessageType(mimeType)
        val messageId = chatRepository.createOutgoingFileTransferPlaceholder(
            chatId = chatId,
            senderProfileId = identity.profileId,
            fileName = fileName,
            filePath = "",
            messageType = messageType,
            totalBytes = totalBytes,
            expiresAtEpochMs = expiresAtEpochMs
        )
        registerOutgoingTransfer(
            transferId = transferId,
            chatId = chatId,
            messageId = messageId,
            fileName = fileName,
            totalBytes = totalBytes,
            filePathForPreview = "",
            expectedConfirmationProfileIds = setOf(contact.profileId),
            sourceUri = sourceUri,
            mimeType = mimeType,
            messageType = messageType,
            expiresAtEpochMs = expiresAtEpochMs,
            chunkCrypto = chunkCrypto,
            awaitingApproval = true
        )
        chatRepository.updateTransferMessageStatus(
            messageId = messageId,
            decryptedPreview = buildOfferPreview(fileName = fileName, totalBytes = totalBytes)
        )
        sendDirectFileOffer(
            chatId = chatId,
            contact = contact,
            senderProfileId = identity.profileId,
            senderPublicKey = identity.publicKey,
            transferId = transferId,
            fileName = fileName,
            mimeType = mimeType,
            messageType = messageType,
            totalBytes = totalBytes,
            expiresAtEpochMs = expiresAtEpochMs,
            chunkCrypto = chunkCrypto
        )
    }

    fun sendGroupFile(
        chatId: Long,
        recipients: List<Contact>,
        sourceUri: Uri,
        fileName: String,
        mimeType: String,
        totalBytes: Long?,
        expiresAtEpochMs: Long?
    ) {
        if (recipients.isEmpty()) return

        val identity = ensureIdentity()
        val routing = chatRepository.groupRoutingContext(chatId)
            ?: error("Group routing context missing for chat $chatId")
        val messageType = mimeToMessageType(mimeType)
        val batchId = UUID.randomUUID().toString()
        val localSentFile = createLocalSentFile(storageId = batchId, fileName = fileName)
        copyUriToFile(sourceUri = sourceUri, targetFile = localSentFile)
        val messageId = chatRepository.createOutgoingFileTransferPlaceholder(
            chatId = chatId,
            senderProfileId = identity.profileId,
            fileName = fileName,
            filePath = "",
            messageType = messageType,
            totalBytes = totalBytes,
            expiresAtEpochMs = expiresAtEpochMs
        )
        registerOutgoingTransferBatch(
            batchId = batchId,
            chatId = chatId,
            messageId = messageId,
            fileName = fileName,
            totalBytes = totalBytes,
            filePathForPreview = localSentFile.absolutePath,
            recipientProfileIds = recipients.map { it.profileId }.toSet()
        )
        recipients.forEach { contact ->
            val transferId = UUID.randomUUID().toString()
            val chunkCrypto = newTransferChunkCrypto()
            registerOutgoingTransfer(
                transferId = transferId,
                chatId = chatId,
                messageId = messageId,
                fileName = fileName,
                totalBytes = totalBytes,
                filePathForPreview = localSentFile.absolutePath,
                expectedConfirmationProfileIds = setOf(contact.profileId),
                sourceUri = Uri.fromFile(localSentFile),
                mimeType = mimeType,
                messageType = messageType,
                expiresAtEpochMs = expiresAtEpochMs,
                chunkCrypto = chunkCrypto,
                awaitingApproval = true,
                batchId = batchId
            )
            val sent = sendGroupFileOffer(
                chatId = chatId,
                routing = routing,
                contact = contact,
                senderProfileId = identity.profileId,
                senderPublicKey = identity.publicKey,
                transferId = transferId,
                fileName = fileName,
                mimeType = mimeType,
                messageType = messageType,
                totalBytes = totalBytes,
                expiresAtEpochMs = expiresAtEpochMs,
                chunkCrypto = chunkCrypto
            )
            if (!sent) {
                markOutgoingTransferFailed(transferId, "offer send failed")
            }
        }
        refreshOutgoingTransferBatchStatus(batchId)
        publishOutgoingTransferProgress()
    }

    fun verifyContact(contact: Contact, observedPhrase: String) {
        chatRepository.verifyContact(contact.id, observedPhrase, contact.safetyPhrase)
    }

    fun updateChatDisappearingSeconds(chatId: Long, seconds: Long?) {
        val normalized = seconds?.takeIf { it > 0 }
        chatRepository.setChatDisappearingSeconds(chatId, normalized)

        val noticeText = if (normalized == null) {
            "You turned off disappearing messages"
        } else {
            "You set disappearing messages to ${formatDisappearingDuration(normalized)}"
        }
        chatRepository.addLocalSystemMessage(chatId, noticeText)

        val chat = chatRepository.chatById(chatId) ?: return
        val identity = ensureIdentity()
        val payload = org.json.JSONObject()
            .put("kind", DISAPPEARING_TIMER_KIND)
            .put("seconds", normalized)
            .put("updatedAtEpochMs", System.currentTimeMillis())
            .toString()

        if (chat.type == ChatType.GROUP) {
            val routing = chatRepository.groupRoutingContext(chatId) ?: return
            routing.memberProfileIds
                .filterNot { it == identity.profileId }
                .forEach { profileId ->
                    val contact = chatRepository.contactByProfileId(profileId) ?: return@forEach
                    val encrypted = runCatching {
                        signalSessionManager.encryptForPeer(
                            peerProfileId = contact.profileId,
                            peerIdentityPublicKeyB64 = contact.publicKey,
                            peerBundle = contactSignalBundle(contact),
                            plaintext = payload
                        )
                    }.getOrElse {
                        Log.w(TAG, "Unable to send disappearing timer update to ${contact.profileId}", it)
                        return@forEach
                    }
                    sendEnvelope(
                        contact = contact,
                        envelope = OutboundEnvelope(
                            chatId = chatId,
                            senderProfileId = identity.profileId,
                            senderDeviceId = localSenderDeviceId(),
                            recipientProfileId = contact.profileId,
                            groupId = routing.groupId,
                            groupTitle = routing.title,
                            groupMemberProfileIds = routing.memberProfileIds,
                            messageType = MessageType.SYSTEM,
                            signalCipherType = encrypted.cipherType,
                            ciphertext = encrypted.ciphertextB64,
                            sentAtEpochMs = System.currentTimeMillis()
                        ),
                        senderPublicKey = identity.publicKey
                    )
                }
            return
        }

        val contact = chatRepository.contactByProfileId(chat.remoteId) ?: return
        val encrypted = runCatching {
            signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = payload
            )
        }.getOrElse {
            Log.w(TAG, "Unable to send disappearing timer update to ${contact.profileId}", it)
            return
        }
        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.SYSTEM,
                signalCipherType = encrypted.cipherType,
                ciphertext = encrypted.ciphertextB64,
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = identity.publicKey
        )
    }

    fun updateGroupMembers(chatId: Long, memberProfileIds: List<String>) {
        val routing = chatRepository.groupRoutingContext(chatId)
            ?: error("Group routing context missing for chat $chatId")
        if (!chatRepository.isLocalGroupAdmin(chatId)) {
            error("Only the group creator can manage members")
        }

        val identity = ensureIdentity()
        val normalizedMembers = (memberProfileIds + identity.profileId).distinct()
        val previousMembers = routing.memberProfileIds.distinct()
        if (normalizedMembers == previousMembers) return

        val updatedAtEpochMs = System.currentTimeMillis()
        chatRepository.replaceGroupMembers(
            chatId = chatId,
            memberProfileIds = normalizedMembers,
            adminProfileId = identity.profileId,
            joinedAtEpochMs = updatedAtEpochMs
        )
        chatRepository.addLocalSystemMessage(
            chatId,
            buildGroupMembershipSummary(
                updatedByProfileId = identity.profileId,
                previousMemberIds = previousMembers,
                currentMemberIds = normalizedMembers
            )
        )

        val recipientProfileIds = (previousMembers + normalizedMembers)
            .distinct()
            .filterNot { it == identity.profileId }

        recipientProfileIds.forEach { profileId ->
            val contact = chatRepository.contactByProfileId(profileId)
            if (contact == null) {
                Log.w(TAG, "Unable to send group membership update to unknown profile $profileId")
                return@forEach
            }
            sendGroupMembershipUpdate(
                contact = contact,
                groupId = routing.groupId,
                groupTitle = routing.title,
                memberProfileIds = normalizedMembers,
                adminProfileId = identity.profileId,
                senderProfileId = identity.profileId,
                senderPublicKey = identity.publicKey,
                chatId = chatId,
                updatedAtEpochMs = updatedAtEpochMs
            )
        }
    }

    fun leaveGroup(chatId: Long) {
        val routing = chatRepository.groupRoutingContext(chatId)
            ?: error("Group routing context missing for chat $chatId")

        val identity = ensureIdentity()
        val previousMembers = routing.memberProfileIds.distinct()
        if (!previousMembers.contains(identity.profileId)) {
            chatRepository.addLocalSystemMessage(chatId, "You are not a member of this group")
            return
        }
        val remainingMembers = previousMembers.filterNot { it == identity.profileId }
        if (remainingMembers.isEmpty()) {
            error("Cannot leave group with no remaining members")
        }

        val chat = chatRepository.chatById(chatId) ?: error("Group chat not found")
        val nextAdminProfileId = if (chat.createdByProfileId == identity.profileId) {
            remainingMembers.first()
        } else {
            chat.createdByProfileId
        }

        val updatedAtEpochMs = System.currentTimeMillis()
        chatRepository.replaceGroupMembers(
            chatId = chatId,
            memberProfileIds = remainingMembers,
            adminProfileId = nextAdminProfileId,
            joinedAtEpochMs = updatedAtEpochMs
        )
        chatRepository.addLocalSystemMessage(chatId, "You left the group")

        previousMembers
            .filterNot { it == identity.profileId }
            .forEach { profileId ->
                val contact = chatRepository.contactByProfileId(profileId) ?: return@forEach
                sendGroupMembershipUpdate(
                    contact = contact,
                    groupId = routing.groupId,
                    groupTitle = routing.title,
                    memberProfileIds = remainingMembers,
                    adminProfileId = nextAdminProfileId,
                    senderProfileId = identity.profileId,
                    senderPublicKey = identity.publicKey,
                    chatId = chatId,
                    updatedAtEpochMs = updatedAtEpochMs
                )
            }
    }

    fun renameGroup(chatId: Long, newTitle: String) {
        val normalizedTitle = newTitle.trim()
        if (normalizedTitle.isBlank()) error("Group name is required")

        val chat = chatRepository.chatById(chatId) ?: error("Group chat not found")
        if (chat.type != ChatType.GROUP) error("Selected chat is not a group")
        if (!chatRepository.isLocalGroupAdmin(chatId)) {
            error("Only the group creator can rename this group")
        }

        val routing = chatRepository.groupRoutingContext(chatId)
            ?: error("Group routing context missing for chat $chatId")
        val identity = ensureIdentity()
        val members = routing.memberProfileIds.distinct()
        val updatedAtEpochMs = System.currentTimeMillis()

        chatRepository.renameChat(chatId, normalizedTitle)

        members
            .filterNot { it == identity.profileId }
            .forEach { profileId ->
                val contact = chatRepository.contactByProfileId(profileId) ?: return@forEach
                sendGroupMembershipUpdate(
                    contact = contact,
                    groupId = routing.groupId,
                    groupTitle = normalizedTitle,
                    memberProfileIds = members,
                    adminProfileId = identity.profileId,
                    senderProfileId = identity.profileId,
                    senderPublicKey = identity.publicKey,
                    chatId = chatId,
                    updatedAtEpochMs = updatedAtEpochMs
                )
            }
    }

    fun startVoiceCall(contact: Contact) {
        updateActiveCallState(
            peerProfileId = contact.profileId,
            audioOnly = true,
            direction = CallDirection.OUTGOING,
            phase = CallPhase.RINGING,
            statusText = "Calling ${contact.effectiveName}"
        )
        callManager.startOutgoingCall(contact.profileId, audioOnly = true)
    }

    fun startVideoCall(contact: Contact) {
        updateActiveCallState(
            peerProfileId = contact.profileId,
            audioOnly = false,
            direction = CallDirection.OUTGOING,
            phase = CallPhase.RINGING,
            statusText = "Calling ${contact.effectiveName}"
        )
        callManager.startOutgoingCall(contact.profileId, audioOnly = false)
    }

    fun endCall(contact: Contact) {
        callManager.endCall(contact.profileId, notifyRemote = true)
    }

    fun endCall(peerProfileId: String) {
        callManager.endCall(peerProfileId, notifyRemote = true)
    }

    fun acceptIncomingCall(peerProfileId: String) {
        callManager.acceptIncomingCall(peerProfileId)
    }

    fun declineIncomingCall(peerProfileId: String) {
        callManager.declineIncomingCall(peerProfileId)
    }

    fun dismissVerification(contactId: Long) {
        chatRepository.dismissVerificationWarning(contactId)
    }

    fun verificationWarningNeeded(contact: Contact): Boolean {
        if (contact.trustStatus != ContactTrustStatus.MISMATCH) return false
        val record = chatRepository.latestVerification(contact.id) ?: return true
        return record.dismissedAtEpochMs == null
    }

    private fun updateActiveCallState(
        peerProfileId: String,
        audioOnly: Boolean,
        direction: CallDirection,
        phase: CallPhase,
        statusText: String
    ) {
        val contact = chatRepository.contactByProfileId(peerProfileId)
        val current = _activeCallState.value?.takeIf { it.peerProfileId == peerProfileId }
        _activeCallState.value = ActiveCallState(
            peerProfileId = peerProfileId,
            displayName = contact?.effectiveName ?: peerProfileId,
            audioOnly = audioOnly,
            direction = direction,
            phase = phase,
            statusText = statusText,
            microphoneEnabled = current?.microphoneEnabled ?: true,
            cameraEnabled = current?.cameraEnabled ?: false,
            localVideoAvailable = current?.localVideoAvailable ?: false,
            remoteVideoAvailable = current?.remoteVideoAvailable ?: false
        )
    }

    private fun applyCallStateUpdate(peerProfileId: String, rawState: String) {
        val current = _activeCallState.value
        val audioOnly = current?.takeIf { it.peerProfileId == peerProfileId }?.audioOnly ?: true
        val direction = current?.takeIf { it.peerProfileId == peerProfileId }?.direction ?: CallDirection.OUTGOING
        when {
            rawState == "connected" || rawState == "pc_connected" -> updateActiveCallState(
                peerProfileId = peerProfileId,
                audioOnly = audioOnly,
                direction = direction,
                phase = CallPhase.CONNECTED,
                statusText = "Connected"
            )

            rawState == "incoming" -> updateActiveCallState(
                peerProfileId = peerProfileId,
                audioOnly = audioOnly,
                direction = CallDirection.INCOMING,
                phase = CallPhase.RINGING,
                statusText = if (audioOnly) "Incoming voice call" else "Incoming video call"
            )

            rawState == "answering" || rawState == "offer_sent" || rawState == "answer_sent" ||
                rawState.startsWith("ice_") || rawState.startsWith("pc_") -> updateActiveCallState(
                peerProfileId = peerProfileId,
                audioOnly = audioOnly,
                direction = direction,
                phase = CallPhase.CONNECTING,
                statusText = rawState.replace('_', ' ')
            )

            rawState == "ended" || rawState == "declined" -> {
                _activeCallState.value = null
            }

            rawState.contains("failed") -> updateActiveCallState(
                peerProfileId = peerProfileId,
                audioOnly = audioOnly,
                direction = direction,
                phase = CallPhase.ERROR,
                statusText = rawState
            )
        }
    }

    private fun handleInboundEnvelope(chatId: Long, contact: Contact, envelope: InboundEnvelope) {
        when (envelope.messageType) {
            MessageType.TEXT -> {
                val cipherType = envelope.signalCipherType ?: return
                chatRepository.ingestIncomingTextMessage(
                    chatId = chatId,
                    senderProfileId = envelope.senderProfileId,
                    senderDeviceId = envelope.senderDeviceId,
                    signalCipherType = cipherType,
                    ciphertext = envelope.ciphertext,
                    expiresAt = envelope.expiresAtEpochMs,
                    sentAtEpochMs = envelope.sentAtEpochMs
                )
                sendDeliveryAck(contact, envelope)
            }

            MessageType.LOCATION -> {
                val cipherType = envelope.signalCipherType ?: return
                chatRepository.ingestIncomingLocationMessage(
                    chatId = chatId,
                    senderProfileId = envelope.senderProfileId,
                    senderDeviceId = envelope.senderDeviceId,
                    signalCipherType = cipherType,
                    ciphertext = envelope.ciphertext,
                    expiresAt = envelope.expiresAtEpochMs,
                    sentAtEpochMs = envelope.sentAtEpochMs
                )
                sendDeliveryAck(contact, envelope)
            }

            MessageType.FILE,
            MessageType.IMAGE -> ingestInboundFileChunk(chatId, envelope)

            MessageType.CALL_SIGNAL -> {
                val cipherType = envelope.signalCipherType ?: return
                val payload = runCatching {
                    signalSessionManager.decryptFromPeer(
                        peerProfileId = envelope.senderProfileId,
                        peerDeviceId = envelope.senderDeviceId,
                        cipherType = cipherType,
                        ciphertextB64 = envelope.ciphertext
                    )
                }.getOrElse {
                    Log.w(TAG, "Unable to decrypt call signal", it)
                    return
                }
                callManager.handleIncomingSignal(envelope.senderProfileId, payload)
            }

            MessageType.SYSTEM -> {
                val cipherType = envelope.signalCipherType ?: return
                val payload = runCatching {
                    signalSessionManager.decryptFromPeer(
                        peerProfileId = envelope.senderProfileId,
                        peerDeviceId = envelope.senderDeviceId,
                        cipherType = cipherType,
                        ciphertextB64 = envelope.ciphertext
                    )
                }.getOrElse {
                    Log.w(TAG, "Unable to decrypt system payload", it)
                    return
                }
                handleInboundSystemPayload(
                    chatId = chatId,
                    senderProfileId = envelope.senderProfileId,
                    messageType = envelope.messageType,
                    payload = payload
                )
            }

            MessageType.DELIVERY_ACK -> {
                val sentAt = envelope.ciphertext.toLongOrNull() ?: return
                val localProfileId = identityManager.getProfileId() ?: return
                chatRepository.updateDeliveryStatus(
                    chatId = envelope.chatId,
                    senderProfileId = localProfileId,
                    sentAtEpochMs = sentAt,
                    status = DeliveryStatus.DELIVERED
                )
            }

            MessageType.READ_ACK -> {
                val localProfileId = identityManager.getProfileId() ?: return
                val readAt = System.currentTimeMillis()
                envelope.ciphertext.split(",").forEach { part ->
                    val sentAt = part.trim().toLongOrNull() ?: return@forEach
                    chatRepository.updateDeliveryStatus(
                        chatId = envelope.chatId,
                        senderProfileId = localProfileId,
                        sentAtEpochMs = sentAt,
                        status = DeliveryStatus.READ,
                        readAtEpochMs = readAt
                    )
                }
            }
        }
    }

    private fun sendDeliveryAck(contact: Contact, envelope: InboundEnvelope) {
        val identity = runCatching { ensureIdentity() }.getOrNull() ?: return
        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = envelope.chatId,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.DELIVERY_ACK,
                signalCipherType = null,
                ciphertext = envelope.sentAtEpochMs.toString(),
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = identity.publicKey
        )
    }

    fun sendReadAcks(chatId: Long, contact: Contact, messages: List<Message>) {
        val localProfileId = identityManager.getProfileId() ?: return
        val toAck = messages.filter {
            it.senderProfileId != localProfileId && !it.localOnly
        }
        if (toAck.isEmpty()) return
        val identity = runCatching { ensureIdentity() }.getOrNull() ?: return
        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.READ_ACK,
                signalCipherType = null,
                ciphertext = toAck.joinToString(",") { it.sentAtEpochMs.toString() },
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = identity.publicKey
        )
    }

    private fun ingestInboundFileChunk(chatId: Long, envelope: InboundEnvelope) {
        val cipherType = envelope.signalCipherType
        val transferId = envelope.transferId ?: return
        val chunkIndex = envelope.transferChunkIndex ?: 0
        val transferName = envelope.transferName ?: "incoming_file"
        val mimeType = envelope.transferMimeType ?: "application/octet-stream"
        val isComplete = envelope.transferComplete == true
        val offerState = pendingInboundOffers[transferId]

        if (offerState == null || offerState.pendingResponse.get()) {
            Log.i(TAG, "Dropping file chunk for unaccepted transfer $transferId")
            return
        }

        val chunkBytes = runCatching {
            offerState.chunkCrypto?.let { chunkCrypto ->
                decryptTransferChunk(
                    transferId = transferId,
                    chunkIndex = chunkIndex,
                    ciphertextB64 = envelope.ciphertext,
                    chunkCrypto = chunkCrypto
                )
            } ?: signalSessionManager.decryptBytesFromPeer(
                peerProfileId = envelope.senderProfileId,
                peerDeviceId = envelope.senderDeviceId,
                cipherType = cipherType ?: error("Missing Signal cipher type for legacy file chunk"),
                ciphertextB64 = envelope.ciphertext
            )
        }.getOrElse {
            Log.w(TAG, "Failed to decrypt file chunk", it)
            return
        }

        val state = incomingTransfers.computeIfAbsent(transferId) {
            val file = createTransferStorageFile(
                rootDirName = "received",
                storageId = transferId,
                fileName = transferName
            )
            IncomingTransferState(
                transferId = transferId,
                chatId = chatId,
                messageId = offerState.messageId,
                senderProfileId = envelope.senderProfileId,
                fileName = transferName,
                mimeType = mimeType,
                messageType = envelope.messageType,
                targetFile = file,
                expectedChunks = envelope.transferChunkCount,
                expectedBytes = envelope.transferTotalBytes,
                expiresAtEpochMs = envelope.expiresAtEpochMs
            )
        }

        if (envelope.expiresAtEpochMs != null) {
            state.expiresAtEpochMs = envelope.expiresAtEpochMs
        }
        if (state.receivedChunks == 0) {
            chatRepository.updateTransferMessageStatus(
                messageId = state.messageId,
                decryptedPreview = "Receiving file: ${state.fileName}",
                filePath = FileTransferOfferCodec.encode(offerState.offer.copy(status = FileTransferOfferStatus.ACCEPTED))
            )
        }
        val wroteChunk = state.appendChunk(chunkIndex, chunkBytes)
        if (!wroteChunk) {
            Log.i(TAG, "Ignoring duplicate chunk $chunkIndex for transfer $transferId")
        }

        val reachedChunkEnd = state.expectedChunks?.let { state.receivedChunks >= it } ?: false
        val reachedByteEnd = state.expectedBytes?.let { state.receivedBytes >= it } ?: false
        val readyToComplete = when {
            state.expectedChunks != null -> reachedChunkEnd
            state.expectedBytes != null -> reachedByteEnd
            else -> isComplete
        }

        if (readyToComplete) {
            incomingTransfers.remove(transferId)
            pendingInboundOffers.remove(transferId)
            val saveUri = pendingSaveUris.remove(transferId)
            val finalPath = if (saveUri != null) {
                runCatching {
                    appContext.contentResolver.openOutputStream(saveUri)?.use { out ->
                        state.targetFile.inputStream().use { it.copyTo(out) }
                    }
                    cleanupManagedFile(state.targetFile)
                    saveUri.toString()
                }.getOrElse {
                    Log.w(TAG, "Failed to copy to save URI, keeping internal path", it)
                    state.targetFile.absolutePath
                }
            } else {
                state.targetFile.absolutePath
            }
            chatRepository.updateTransferMessageStatus(
                messageId = state.messageId,
                decryptedPreview = "File: ${state.fileName}",
                filePath = finalPath
            )
            sendFileReceiveAck(
                recipientProfileId = state.senderProfileId,
                transferId = transferId,
                receivedBytes = state.receivedBytes
            )
            Log.i(TAG, "Completed inbound transfer $transferId at chunk $chunkIndex")
        }
    }

    private fun handleInboundSystemPayload(
        chatId: Long,
        senderProfileId: String,
        messageType: MessageType,
        payload: String
    ) {
        val json = runCatching { org.json.JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("kind")) {
            GROUP_MEMBERSHIP_KIND -> {
                val groupId = json.optString("groupId")
                if (groupId.isBlank()) return
                val memberProfileIds = json.optStringArray("memberProfileIds")
                if (memberProfileIds.isEmpty()) return
                val adminProfileId = json.optString("adminProfileId").takeIf { it.isNotBlank() } ?: senderProfileId
                val updatedAtEpochMs = json.optLong("updatedAtEpochMs").takeIf { !json.isNull("updatedAtEpochMs") }
                    ?: System.currentTimeMillis()

                val existingChat = chatRepository.groupChatByRemoteId(groupId)
                if (existingChat != null && existingChat.createdByProfileId != senderProfileId) {
                    Log.w(TAG, "Ignoring group membership update for $groupId from non-admin $senderProfileId")
                    return
                }

                val previousMembers = existingChat?.let { chatRepository.groupRoutingContext(it.id)?.memberProfileIds }
                    .orEmpty()
                val chatIdForUpdate = chatRepository.syncInboundGroupChat(
                    groupId = groupId,
                    groupTitle = json.optString("groupTitle").takeIf { it.isNotBlank() },
                    createdByProfileId = adminProfileId,
                    memberProfileIds = memberProfileIds,
                    joinedAtEpochMs = updatedAtEpochMs
                )
                chatRepository.addLocalSystemMessage(
                    chatIdForUpdate,
                    buildGroupMembershipSummary(
                        updatedByProfileId = senderProfileId,
                        previousMemberIds = previousMembers,
                        currentMemberIds = chatRepository.groupRoutingContext(chatIdForUpdate)?.memberProfileIds.orEmpty()
                    )
                )
            }

            FILE_OFFER_KIND -> {
                val transferId = json.optString("transferId")
                val fileName = json.optString("fileName")
                val mimeType = json.optString("mimeType")
                if (transferId.isBlank() || fileName.isBlank() || mimeType.isBlank()) return
                if (pendingInboundOffers.containsKey(transferId) || incomingTransfers.containsKey(transferId)) return
                val chunkCrypto = if (json.has("chunkCipher")) {
                    parseTransferChunkCrypto(json) ?: return
                } else {
                    null
                }
                val offer = FileTransferOffer(
                    transferId = transferId,
                    fileName = fileName,
                    mimeType = mimeType,
                    totalBytes = json.optLong("totalBytes").takeIf { !json.isNull("totalBytes") },
                    status = FileTransferOfferStatus.PENDING
                )
                val offeredMessageType = runCatching {
                    MessageType.valueOf(json.optString("fileMessageType", messageType.name))
                }.getOrDefault(MessageType.FILE)
                val messageId = chatRepository.createIncomingFileOfferPlaceholder(
                    chatId = chatId,
                    senderProfileId = senderProfileId,
                    fileName = fileName,
                    metadataJson = FileTransferOfferCodec.encode(offer),
                    messageType = offeredMessageType,
                    totalBytes = offer.totalBytes,
                    expiresAtEpochMs = json.optLong("expiresAtEpochMs").takeIf { !json.isNull("expiresAtEpochMs") }
                )
                pendingInboundOffers[transferId] = PendingInboundOfferState(
                    transferId = transferId,
                    chatId = chatId,
                    messageId = messageId,
                    senderProfileId = senderProfileId,
                    fileName = fileName,
                    offer = offer,
                    chunkCrypto = chunkCrypto
                )
            }

            FILE_ACCEPT_KIND -> {
                val transferId = json.optString("transferId")
                if (transferId.isBlank()) return
                startApprovedOutgoingTransfer(transferId = transferId, senderProfileId = senderProfileId)
            }

            FILE_DECLINE_KIND -> {
                val transferId = json.optString("transferId")
                if (transferId.isBlank()) return
                declineOutgoingTransfer(transferId = transferId, senderProfileId = senderProfileId)
            }

            FILE_ACK_KIND -> {
                val transferId = json.optString("transferId")
                if (transferId.isBlank()) return
                acknowledgeOutgoingTransfer(transferId = transferId, senderProfileId = senderProfileId)
            }

            FILE_CANCEL_KIND -> {
                val transferId = json.optString("transferId")
                if (transferId.isBlank()) return
                cancelInboundTransfer(transferId)
            }

            DISAPPEARING_TIMER_KIND -> {
                val seconds = if (json.isNull("seconds")) {
                    null
                } else {
                    json.optLong("seconds").takeIf { it > 0 }
                }
                chatRepository.setChatDisappearingSeconds(chatId, seconds)
                val senderName = chatRepository.contactByProfileId(senderProfileId)?.effectiveName
                    ?: senderProfileId.take(8)
                val noticeText = if (seconds == null) {
                    "$senderName turned off disappearing messages"
                } else {
                    "$senderName set disappearing messages to ${formatDisappearingDuration(seconds)}"
                }
                chatRepository.addLocalSystemMessage(chatId, noticeText)
            }

            else -> return
        }
    }

    private fun cancelInboundTransfer(transferId: String) {
        val transferState = incomingTransfers.remove(transferId)
        pendingSaveUris.remove(transferId)
        if (transferState != null) {
            cleanupManagedFile(transferState.targetFile)
            pendingInboundOffers.remove(transferId)?.let { offerState ->
                chatRepository.updateTransferMessageStatus(
                    messageId = offerState.messageId,
                    decryptedPreview = "File transfer canceled: ${offerState.fileName}",
                    filePath = FileTransferOfferCodec.encode(offerState.offer.copy(status = FileTransferOfferStatus.CANCELED))
                )
            }
            Log.i(TAG, "Canceled inbound transfer $transferId and removed partial file")
            return
        }

        val offerState = pendingInboundOffers.remove(transferId) ?: return
        chatRepository.updateTransferMessageStatus(
            messageId = offerState.messageId,
            decryptedPreview = "File offer canceled: ${offerState.fileName}",
            filePath = FileTransferOfferCodec.encode(offerState.offer.copy(status = FileTransferOfferStatus.CANCELED))
        )
    }

    private fun sendFileTransferCancelNotice(transferId: String, recipientProfileIds: Set<String>) {
        if (recipientProfileIds.isEmpty()) return
        val identity = ensureIdentity()
        val payload = org.json.JSONObject()
            .put("kind", FILE_CANCEL_KIND)
            .put("transferId", transferId)
            .put("canceledAtEpochMs", System.currentTimeMillis())
            .toString()

        recipientProfileIds.forEach { profileId ->
            val contact = chatRepository.contactByProfileId(profileId) ?: return@forEach
            val encrypted = runCatching {
                signalSessionManager.encryptForPeer(
                    peerProfileId = contact.profileId,
                    peerIdentityPublicKeyB64 = contact.publicKey,
                    peerBundle = contactSignalBundle(contact),
                    plaintext = payload
                )
            }.getOrElse {
                Log.w(TAG, "Unable to send file cancel notice for $transferId to ${contact.profileId}", it)
                return@forEach
            }

            sendEnvelope(
                contact = contact,
                envelope = OutboundEnvelope(
                    chatId = chatRepository.chatForContact(contact).id,
                    senderProfileId = identity.profileId,
                    senderDeviceId = localSenderDeviceId(),
                    recipientProfileId = contact.profileId,
                    messageType = MessageType.SYSTEM,
                    signalCipherType = encrypted.cipherType,
                    ciphertext = encrypted.ciphertextB64,
                    sentAtEpochMs = System.currentTimeMillis()
                ),
                senderPublicKey = identity.publicKey
            )
        }
    }

    private fun sendFileReceiveAck(recipientProfileId: String, transferId: String, receivedBytes: Long) {
        val contact = chatRepository.contactByProfileId(recipientProfileId) ?: return
        val identity = ensureIdentity()
        val payload = org.json.JSONObject()
            .put("kind", FILE_ACK_KIND)
            .put("transferId", transferId)
            .put("receivedBytes", receivedBytes)
            .put("receivedAtEpochMs", System.currentTimeMillis())
            .toString()

        val encrypted = runCatching {
            signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = payload
            )
        }.getOrElse {
            Log.w(TAG, "Unable to send file receive ack for $transferId", it)
            return
        }

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatRepository.chatForContact(contact).id,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.SYSTEM,
                signalCipherType = encrypted.cipherType,
                ciphertext = encrypted.ciphertextB64,
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = identity.publicKey
        )
    }

    private fun sendDirectFileOffer(
        chatId: Long,
        contact: Contact,
        senderProfileId: String,
        senderPublicKey: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        messageType: MessageType,
        totalBytes: Long?,
        expiresAtEpochMs: Long?,
        chunkCrypto: TransferChunkCrypto
    ): Boolean {
        val payload = org.json.JSONObject()
            .put("kind", FILE_OFFER_KIND)
            .put("transferId", transferId)
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("fileMessageType", messageType.name)
            .put("totalBytes", totalBytes)
            .put("expiresAtEpochMs", expiresAtEpochMs)
            .put("chunkCipher", FILE_CHUNK_CIPHER_AES_GCM_V1)
            .put("chunkKey", CryptoUtils.b64(chunkCrypto.key))
            .put("chunkNoncePrefix", CryptoUtils.b64(chunkCrypto.noncePrefix))
            .toString()

        val encrypted = runCatching {
            signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = payload
            )
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to send file offer for $transferId", throwable)
            val state = outgoingTransfers[transferId]
            markOutgoingTransferFailed(transferId, throwable.message ?: "offer send failed")
            state?.let {
                chatRepository.updateTransferMessageStatus(
                    messageId = state.messageId,
                    decryptedPreview = "File send failed: ${state.fileName} (${throwable.message ?: "offer send failed"})"
                )
            }
            return false
        }

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = senderProfileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.SYSTEM,
                signalCipherType = encrypted.cipherType,
                ciphertext = encrypted.ciphertextB64,
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = senderPublicKey
        )
        return true
    }

    private fun sendGroupFileOffer(
        chatId: Long,
        routing: GroupRoutingContext,
        contact: Contact,
        senderProfileId: String,
        senderPublicKey: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        messageType: MessageType,
        totalBytes: Long?,
        expiresAtEpochMs: Long?,
        chunkCrypto: TransferChunkCrypto
    ): Boolean {
        val payload = org.json.JSONObject()
            .put("kind", FILE_OFFER_KIND)
            .put("transferId", transferId)
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("fileMessageType", messageType.name)
            .put("totalBytes", totalBytes)
            .put("expiresAtEpochMs", expiresAtEpochMs)
            .put("chunkCipher", FILE_CHUNK_CIPHER_AES_GCM_V1)
            .put("chunkKey", CryptoUtils.b64(chunkCrypto.key))
            .put("chunkNoncePrefix", CryptoUtils.b64(chunkCrypto.noncePrefix))
            .toString()

        val encrypted = runCatching {
            signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = payload
            )
        }.getOrElse {
            Log.w(TAG, "Unable to send group file offer for $transferId", it)
            return false
        }

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = senderProfileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                groupId = routing.groupId,
                groupTitle = routing.title,
                groupMemberProfileIds = routing.memberProfileIds,
                messageType = MessageType.SYSTEM,
                signalCipherType = encrypted.cipherType,
                ciphertext = encrypted.ciphertextB64,
                sentAtEpochMs = System.currentTimeMillis(),
                expiresAtEpochMs = expiresAtEpochMs
            ),
            senderPublicKey = senderPublicKey
        )
        return true
    }

    private fun sendFileOfferResponse(contact: Contact, kind: String, transferId: String) {
        val identity = ensureIdentity()
        val payload = org.json.JSONObject()
            .put("kind", kind)
            .put("transferId", transferId)
            .put("respondedAtEpochMs", System.currentTimeMillis())
            .toString()

        val encrypted = runCatching {
            signalSessionManager.encryptForPeer(
                peerProfileId = contact.profileId,
                peerIdentityPublicKeyB64 = contact.publicKey,
                peerBundle = contactSignalBundle(contact),
                plaintext = payload
            )
        }.getOrElse {
            Log.w(TAG, "Unable to send file response $kind for $transferId", it)
            return
        }

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatRepository.chatForContact(contact).id,
                senderProfileId = identity.profileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                messageType = MessageType.SYSTEM,
                signalCipherType = encrypted.cipherType,
                ciphertext = encrypted.ciphertextB64,
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = identity.publicKey
        )
    }

    private fun startApprovedOutgoingTransfer(transferId: String, senderProfileId: String) {
        val state = outgoingTransfers[transferId] ?: return
        if (state.cancelRequested.get()) return
        if (!state.awaitingApproval.compareAndSet(true, false)) return
        state.batchId?.let { batchId ->
            val batch = outgoingTransferBatches[batchId]
            if (batch != null) {
                synchronized(batch) {
                    batch.awaitingApprovalProfileIds.remove(senderProfileId)
                    batch.approvedProfileIds.add(senderProfileId)
                }
                refreshOutgoingTransferBatchStatus(batchId)
            }
        }
        val contact = chatRepository.contactByProfileId(senderProfileId) ?: run {
            markOutgoingTransferFailed(transferId, "peer unavailable")
            return
        }
        synchronized(state) {
            state.startedAtEpochMs = System.currentTimeMillis()
            state.lastPreviewUpdateEpochMs = 0L
        }
        publishOutgoingTransferProgress()
        scope.launch {
            performApprovedDirectTransfer(state = state, contact = contact)
        }
    }

    private fun declineOutgoingTransfer(transferId: String, senderProfileId: String) {
        val state = outgoingTransfers[transferId] ?: return
        val batchId = state.batchId
        if (batchId != null) {
            synchronized(state) {
                state.declined = true
                state.pendingConfirmationProfileIds.remove(senderProfileId)
            }
            val batch = outgoingTransferBatches[batchId]
            if (batch != null) {
                synchronized(batch) {
                    batch.awaitingApprovalProfileIds.remove(senderProfileId)
                    batch.declinedProfileIds.add(senderProfileId)
                }
            }
            outgoingTransfers.remove(transferId)
            refreshOutgoingTransferBatchStatus(batchId)
            finalizeOutgoingTransferBatchIfResolved(batchId)
            publishOutgoingTransferProgress()
            return
        }
        outgoingTransfers.remove(transferId)
        chatRepository.updateTransferMessageStatus(
            messageId = state.messageId,
            decryptedPreview = "File offer declined: ${state.fileName}",
            filePath = ""
        )
        publishOutgoingTransferProgress()
    }

    private fun performApprovedDirectTransfer(state: OutgoingTransferState, contact: Contact) {
        val identity = ensureIdentity()
        val localSentFile = if (state.batchId != null) {
            File(state.filePathForPreview)
        } else {
            createLocalSentFile(storageId = state.transferId, fileName = state.fileName).also { file ->
                synchronized(state) {
                    state.filePathForPreview = file.absolutePath
                }
            }
        }

        val chunkCount = state.totalBytes?.takeIf { it >= 0 }?.let {
            ceil(it.toDouble() / FILE_CHUNK_BYTES.toDouble()).toInt().coerceAtLeast(1)
        }

        var sentBytes = 0L
        runCatching {
            openInputStream(state.sourceUri).use { input ->
                val transferBlock: (FileOutputStream?) -> Unit = { output ->
                    val buffer = ByteArray(FILE_CHUNK_BYTES)
                    var read = input.read(buffer)
                    var chunkIndex = 0

                    if (read == -1) {
                        ensureTransferNotCanceled(state.transferId)
                        val encryptedChunk = encryptTransferChunk(
                            state = state,
                            chunkIndex = 0,
                            plaintext = ByteArray(0)
                        )
                        sendEnvelope(
                            contact = contact,
                            envelope = OutboundEnvelope(
                                chatId = state.chatId,
                                senderProfileId = identity.profileId,
                                senderDeviceId = localSenderDeviceId(),
                                recipientProfileId = contact.profileId,
                                messageType = state.messageType,
                                signalCipherType = null,
                                ciphertext = encryptedChunk,
                                transferId = state.transferId,
                                transferName = state.fileName,
                                transferMimeType = state.mimeType,
                                transferTotalBytes = 0,
                                transferChunkIndex = 0,
                                transferChunkCount = 1,
                                transferComplete = true,
                                sentAtEpochMs = System.currentTimeMillis(),
                                expiresAtEpochMs = state.expiresAtEpochMs
                            ),
                            senderPublicKey = identity.publicKey
                        )
                    } else {
                        while (read != -1) {
                            ensureTransferNotCanceled(state.transferId)
                            val chunk = buffer.copyOf(read)
                            output?.write(chunk)
                            sentBytes += read

                            val nextRead = input.read(buffer)
                            val isLast = nextRead == -1

                            val encryptedChunk = encryptTransferChunk(
                                state = state,
                                chunkIndex = chunkIndex,
                                plaintext = chunk
                            )

                            sendEnvelope(
                                contact = contact,
                                envelope = OutboundEnvelope(
                                    chatId = state.chatId,
                                    senderProfileId = identity.profileId,
                                    senderDeviceId = localSenderDeviceId(),
                                    recipientProfileId = contact.profileId,
                                    messageType = state.messageType,
                                    signalCipherType = null,
                                    ciphertext = encryptedChunk,
                                    transferId = state.transferId,
                                    transferName = state.fileName,
                                    transferMimeType = state.mimeType,
                                    transferTotalBytes = state.totalBytes,
                                    transferChunkIndex = chunkIndex,
                                    transferChunkCount = chunkCount,
                                    transferComplete = isLast,
                                    sentAtEpochMs = System.currentTimeMillis(),
                                    expiresAtEpochMs = state.expiresAtEpochMs
                                ),
                                senderPublicKey = identity.publicKey
                            )

                            updateOutgoingTransferProgress(state.transferId, sentBytes)
                            chunkIndex += 1
                            read = nextRead
                        }
                    }
                }
                if (state.batchId == null) {
                    FileOutputStream(localSentFile).use { output ->
                        transferBlock(output)
                    }
                } else {
                    transferBlock(null)
                }
            }
            completeOutgoingTransfer(state.transferId, sentBytes)
            if (state.batchId == null) {
                val totalLabel = state.totalBytes ?: sentBytes
                val finalPreview = buildTransferPreview(
                    fileName = state.fileName,
                    totalBytes = totalLabel,
                    filePath = localSentFile.absolutePath,
                    confirmationsReceived = 0,
                    confirmationsExpected = state.expectedConfirmationCount
                )
                chatRepository.updateTransferMessageStatus(
                    messageId = state.messageId,
                    decryptedPreview = finalPreview,
                    filePath = localSentFile.absolutePath
                )
            } else {
                refreshOutgoingTransferBatchStatus(state.batchId)
            }
        }.onFailure { throwable ->
            when (throwable) {
                is TransferCanceledException -> {
                    if (state.batchId != null) {
                        cancelOutgoingTransferBatch(state.batchId)
                    } else {
                        markOutgoingTransferCanceled(state.transferId, sentBytes)
                        cleanupManagedFile(localSentFile)
                        chatRepository.updateTransferMessageStatus(
                            messageId = state.messageId,
                            decryptedPreview = "File send canceled: ${state.fileName}",
                            filePath = ""
                        )
                    }
                }

                else -> {
                    val reason = throwable.message ?: "unknown error"
                    markOutgoingTransferFailed(state.transferId, reason)
                    if (state.batchId == null) {
                        chatRepository.updateTransferMessageStatus(
                            messageId = state.messageId,
                            decryptedPreview = "File send failed: ${state.fileName} ($reason)"
                        )
                    }
                }
            }
        }
    }

    private fun registerOutgoingTransfer(
        transferId: String,
        chatId: Long,
        messageId: Long,
        fileName: String,
        totalBytes: Long?,
        filePathForPreview: String,
        expectedConfirmationProfileIds: Set<String>,
        sourceUri: Uri,
        mimeType: String,
        messageType: MessageType,
        expiresAtEpochMs: Long?,
        chunkCrypto: TransferChunkCrypto,
        awaitingApproval: Boolean = false,
        batchId: String? = null
    ) {
        outgoingTransfers[transferId] = OutgoingTransferState(
            transferId = transferId,
            batchId = batchId,
            chatId = chatId,
            messageId = messageId,
            fileName = fileName,
            totalBytes = totalBytes,
            filePathForPreview = filePathForPreview,
            sourceUri = sourceUri,
            mimeType = mimeType,
            messageType = messageType,
            expiresAtEpochMs = expiresAtEpochMs,
            chunkCrypto = chunkCrypto,
            startedAtEpochMs = System.currentTimeMillis(),
            expectedConfirmationCount = expectedConfirmationProfileIds.size,
            recipientProfileIds = expectedConfirmationProfileIds,
            pendingConfirmationProfileIds = expectedConfirmationProfileIds.toMutableSet(),
            awaitingApproval = AtomicBoolean(awaitingApproval)
        )
        publishOutgoingTransferProgress()
    }

    private fun registerOutgoingTransferBatch(
        batchId: String,
        chatId: Long,
        messageId: Long,
        fileName: String,
        totalBytes: Long?,
        filePathForPreview: String,
        recipientProfileIds: Set<String>
    ) {
        outgoingTransferBatches[batchId] = OutgoingTransferBatchState(
            batchId = batchId,
            chatId = chatId,
            messageId = messageId,
            fileName = fileName,
            totalBytes = totalBytes,
            filePathForPreview = filePathForPreview,
            recipientProfileIds = recipientProfileIds,
            awaitingApprovalProfileIds = recipientProfileIds.toMutableSet()
        )
    }

    private fun updateOutgoingTransferProgress(transferId: String, sentBytes: Long) {
        val state = outgoingTransfers[transferId] ?: return
        val now = System.currentTimeMillis()
        synchronized(state) {
            state.sentBytes = sentBytes
            if (state.batchId == null && now - state.lastPreviewUpdateEpochMs >= PREVIEW_UPDATE_INTERVAL_MS) {
                chatRepository.updateTransferMessageStatus(
                    messageId = state.messageId,
                    decryptedPreview = buildSendingPreview(
                        fileName = state.fileName,
                        sentBytes = state.sentBytes,
                        totalBytes = state.totalBytes
                    )
                )
                state.lastPreviewUpdateEpochMs = now
            }
        }
        state.batchId?.let { batchId ->
            refreshOutgoingTransferBatchStatus(batchId)
        }
        publishOutgoingTransferProgress()
    }

    private fun completeOutgoingTransfer(transferId: String, sentBytes: Long) {
        val state = outgoingTransfers[transferId] ?: return
        synchronized(state) {
            state.sentBytes = sentBytes
            state.isComplete = true
        }
        if (state.expectedConfirmationCount == 0) {
            outgoingTransfers.remove(transferId)
        }
        state.batchId?.let { batchId ->
            refreshOutgoingTransferBatchStatus(batchId)
        }
        publishOutgoingTransferProgress()
    }

    private fun acknowledgeOutgoingTransfer(transferId: String, senderProfileId: String) {
        val state = outgoingTransfers[transferId] ?: return
        val batchId = state.batchId
        var shouldFinalize = false
        synchronized(state) {
            if (state.pendingConfirmationProfileIds.remove(senderProfileId)) {
                val confirmationsReceived = state.expectedConfirmationCount - state.pendingConfirmationProfileIds.size
                if (batchId == null) {
                    val totalLabel = state.totalBytes ?: state.sentBytes
                    chatRepository.updateTransferMessageStatus(
                        messageId = state.messageId,
                        decryptedPreview = buildTransferPreview(
                            fileName = state.fileName,
                            totalBytes = totalLabel,
                            filePath = state.filePathForPreview,
                            confirmationsReceived = confirmationsReceived,
                            confirmationsExpected = state.expectedConfirmationCount
                        ),
                        filePath = state.filePathForPreview
                    )
                }
            }
            shouldFinalize = state.isComplete && state.pendingConfirmationProfileIds.isEmpty()
        }
        if (batchId != null) {
            if (shouldFinalize) {
                outgoingTransfers.remove(transferId)
                val batch = outgoingTransferBatches[batchId]
                if (batch != null) {
                    synchronized(batch) {
                        batch.completedProfileIds.add(senderProfileId)
                    }
                }
            }
            refreshOutgoingTransferBatchStatus(batchId)
            finalizeOutgoingTransferBatchIfResolved(batchId)
        } else if (shouldFinalize) {
            outgoingTransfers.remove(transferId)
        }
        publishOutgoingTransferProgress()
    }

    private fun markOutgoingTransferCanceled(transferId: String, sentBytes: Long) {
        val state = outgoingTransfers[transferId] ?: return
        val batchId = state.batchId
        if (batchId != null) {
            cancelOutgoingTransferBatch(batchId)
            return
        }
        val recipients = synchronized(state) {
            state.sentBytes = sentBytes
            state.cancelRequested.set(true)
            state.recipientProfileIds.toSet()
        }
        sendFileTransferCancelNotice(
            transferId = transferId,
            recipientProfileIds = recipients
        )
        outgoingTransfers.remove(transferId)
        publishOutgoingTransferProgress()
    }

    private fun markOutgoingTransferFailed(transferId: String, reason: String) {
        val state = outgoingTransfers[transferId] ?: return
        val batchId = state.batchId
        synchronized(state) {
            state.failedReason = reason
        }
        outgoingTransfers.remove(transferId)
        if (batchId != null) {
            val recipientProfileId = state.recipientProfileIds.firstOrNull()
            val batch = outgoingTransferBatches[batchId]
            if (batch != null && recipientProfileId != null) {
                synchronized(batch) {
                    batch.awaitingApprovalProfileIds.remove(recipientProfileId)
                    batch.failedProfileIds.add(recipientProfileId)
                }
            }
            refreshOutgoingTransferBatchStatus(batchId)
            finalizeOutgoingTransferBatchIfResolved(batchId)
        }
        publishOutgoingTransferProgress()
    }

    private fun cancelOutgoingTransferBatch(batchId: String) {
        val batch = outgoingTransferBatches[batchId] ?: return
        val childStates = outgoingTransfers.values.filter { it.batchId == batchId }
        synchronized(batch) {
            batch.cancelRequested.set(true)
            batch.awaitingApprovalProfileIds.clear()
        }
        childStates.forEach { state ->
            synchronized(state) {
                state.cancelRequested.set(true)
            }
            sendFileTransferCancelNotice(
                transferId = state.transferId,
                recipientProfileIds = state.recipientProfileIds
            )
            outgoingTransfers.remove(state.transferId)
        }
        cleanupManagedFile(File(batch.filePathForPreview))
        chatRepository.updateTransferMessageStatus(
            messageId = batch.messageId,
            decryptedPreview = "File send canceled: ${batch.fileName}",
            filePath = ""
        )
        outgoingTransferBatches.remove(batchId)
        publishOutgoingTransferProgress()
    }

    private fun refreshOutgoingTransferBatchStatus(batchId: String) {
        val batch = outgoingTransferBatches[batchId] ?: return
        val childStates = outgoingTransfers.values.filter { it.batchId == batchId }
        val (preview, filePath) = synchronized(batch) {
            val approvals = batch.approvedProfileIds.size
            val declined = batch.declinedProfileIds.size
            val failed = batch.failedProfileIds.size
            val completed = batch.completedProfileIds.size
            val waiting = batch.awaitingApprovalProfileIds.size
            val totalRecipients = batch.recipientProfileIds.size
            val totalLabel = batch.totalBytes?.toString() ?: "unknown"
            val resolvedFilePath = if (approvals > 0 || completed > 0) batch.filePathForPreview else ""
            val activeChildren = childStates.filter { !it.awaitingApproval.get() && !it.isComplete && !it.cancelRequested.get() }
            val sentBytes = activeChildren.maxOfOrNull { candidate ->
                synchronized(candidate) { candidate.sentBytes }
            } ?: if (completed > 0) (batch.totalBytes ?: 0L) else 0L

            val statusPreview = when {
                batch.cancelRequested.get() -> "File send canceled: ${batch.fileName}"
                activeChildren.isNotEmpty() -> {
                    "Sending ${batch.fileName} ($sentBytes/$totalLabel bytes) " +
                        "• approved $approvals/$totalRecipients, received $completed, declined $declined"
                }

                approvals == 0 && waiting > 0 -> {
                    "Group file offer: ${batch.fileName} ($totalLabel bytes) • awaiting receive approval (0/$totalRecipients accepted)"
                }

                waiting > 0 -> {
                    "Group file offer: ${batch.fileName} ($totalLabel bytes) " +
                        "• accepted $approvals/$totalRecipients, declined $declined, waiting $waiting"
                }

                approvals > completed -> {
                    buildStoredFilePreview(
                        fileName = batch.fileName,
                        totalBytes = batch.totalBytes ?: 0L,
                        filePath = resolvedFilePath,
                        status = "awaiting receive confirmation ($completed/$approvals received, $declined declined, $failed failed)"
                    )
                }

                completed > 0 || declined > 0 || failed > 0 -> {
                    buildStoredFilePreview(
                        fileName = batch.fileName,
                        totalBytes = batch.totalBytes ?: 0L,
                        filePath = resolvedFilePath,
                        status = "received by $completed/$totalRecipients, declined $declined, failed $failed"
                    )
                }

                else -> "File send failed: ${batch.fileName}"
            }
            statusPreview to resolvedFilePath
        }
        chatRepository.updateTransferMessageStatus(
            messageId = batch.messageId,
            decryptedPreview = preview,
            filePath = filePath
        )
    }

    private fun finalizeOutgoingTransferBatchIfResolved(batchId: String) {
        val batch = outgoingTransferBatches[batchId] ?: return
        val activeChildExists = outgoingTransfers.values.any { it.batchId == batchId }
        val resolved = synchronized(batch) {
            batch.awaitingApprovalProfileIds.isEmpty() && !activeChildExists
        }
        if (!resolved) return
        refreshOutgoingTransferBatchStatus(batchId)
        val shouldDeletePreviewFile = synchronized(batch) {
            batch.approvedProfileIds.isEmpty() && batch.completedProfileIds.isEmpty()
        }
        if (shouldDeletePreviewFile) {
            cleanupManagedFile(File(batch.filePathForPreview))
        }
        outgoingTransferBatches.remove(batchId)
    }

    private fun ensureTransferNotCanceled(transferId: String) {
        val canceled = outgoingTransfers[transferId]?.cancelRequested?.get() == true
        if (canceled) throw TransferCanceledException()
    }

    private fun createLocalSentFile(storageId: String, fileName: String): File {
        return createTransferStorageFile(
            rootDirName = "sent",
            storageId = storageId,
            fileName = fileName
        )
    }

    private fun createTransferStorageFile(rootDirName: String, storageId: String, fileName: String): File {
        val rootDir = File(appContext.filesDir, rootDirName)
        rootDir.mkdirs()
        val storageDir = File(rootDir, storageId)
        storageDir.mkdirs()
        return File(storageDir, sanitizeFileName(fileName))
    }

    private fun cleanupManagedFile(file: File?) {
        if (file == null) return
        runCatching { file.delete() }
        val parent = file.parentFile ?: return
        if (parent.isDirectory && parent.list()?.isEmpty() == true) {
            runCatching { parent.delete() }
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun publishOutgoingTransferProgress() {
        val directProgress = outgoingTransfers.values
            .filter { it.batchId == null }
            .map { state ->
                synchronized(state) {
                    val elapsedMillis = (System.currentTimeMillis() - state.startedAtEpochMs).coerceAtLeast(1L)
                    val speed = ((state.sentBytes * 1000L) / elapsedMillis).coerceAtLeast(0L)
                    val eta = if (state.totalBytes != null && speed > 0L) {
                        ((state.totalBytes - state.sentBytes).coerceAtLeast(0L) + speed - 1L) / speed
                    } else {
                        null
                    }
                    OutgoingTransferProgress(
                        transferId = state.transferId,
                        chatId = state.chatId,
                        messageId = state.messageId,
                        fileName = state.fileName,
                        sentBytes = state.sentBytes,
                        totalBytes = state.totalBytes,
                        speedBytesPerSec = speed,
                        etaSeconds = eta,
                        isComplete = state.isComplete,
                        awaitingApproval = state.awaitingApproval.get(),
                        cancelRequested = state.cancelRequested.get(),
                        failedReason = state.failedReason,
                        confirmationsReceived = state.expectedConfirmationCount - state.pendingConfirmationProfileIds.size,
                        confirmationsExpected = state.expectedConfirmationCount
                    )
                }
            }

        val batchProgress = outgoingTransferBatches.values.mapNotNull { batch ->
            val childStates = outgoingTransfers.values.filter { it.batchId == batch.batchId }
            if (childStates.isEmpty()) return@mapNotNull null

            synchronized(batch) {
                val representative = childStates.maxByOrNull { candidate ->
                    synchronized(candidate) { candidate.sentBytes }
                }
                val sentBytes = representative?.let { candidate ->
                    synchronized(candidate) { candidate.sentBytes }
                } ?: 0L
                val speed = representative?.let { candidate ->
                    synchronized(candidate) {
                        val elapsedMillis = (System.currentTimeMillis() - candidate.startedAtEpochMs).coerceAtLeast(1L)
                        ((candidate.sentBytes * 1000L) / elapsedMillis).coerceAtLeast(0L)
                    }
                } ?: 0L
                val eta = if (batch.totalBytes != null && speed > 0L) {
                    ((batch.totalBytes - sentBytes).coerceAtLeast(0L) + speed - 1L) / speed
                } else {
                    null
                }
                OutgoingTransferProgress(
                    transferId = childStates.first().transferId,
                    chatId = batch.chatId,
                    messageId = batch.messageId,
                    fileName = batch.fileName,
                    sentBytes = sentBytes,
                    totalBytes = batch.totalBytes,
                    speedBytesPerSec = speed,
                    etaSeconds = eta,
                    isComplete = false,
                    awaitingApproval = batch.approvedProfileIds.isEmpty() && batch.awaitingApprovalProfileIds.isNotEmpty(),
                    cancelRequested = batch.cancelRequested.get(),
                    failedReason = null,
                    confirmationsReceived = batch.completedProfileIds.size,
                    confirmationsExpected = batch.approvedProfileIds.size
                )
            }
        }

        _outgoingTransferProgress.value = (directProgress + batchProgress)
            .sortedByDescending { it.messageId }
    }

    private fun buildSendingPreview(fileName: String, sentBytes: Long, totalBytes: Long?): String {
        val totalLabel = totalBytes?.toString() ?: "unknown"
        return "Sending $fileName ($sentBytes/$totalLabel bytes)"
    }

    private fun buildOfferPreview(fileName: String, totalBytes: Long?): String {
        val totalLabel = totalBytes?.toString() ?: "unknown"
        return "File offer: $fileName ($totalLabel bytes) • awaiting receive approval"
    }

    private fun buildTransferPreview(
        fileName: String,
        totalBytes: Long,
        filePath: String,
        confirmationsReceived: Int,
        confirmationsExpected: Int
    ): String {
        val status = when {
            confirmationsExpected <= 0 -> "sent"
            confirmationsReceived >= confirmationsExpected -> "received"
            else -> "awaiting receive confirmation ($confirmationsReceived/$confirmationsExpected)"
        }
        return buildStoredFilePreview(
            fileName = fileName,
            totalBytes = totalBytes,
            filePath = filePath,
            status = status
        )
    }

    private fun buildStoredFilePreview(
        fileName: String,
        totalBytes: Long,
        filePath: String,
        status: String
    ): String {
        val pathSegment = filePath.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
        return "File: $fileName ($totalBytes bytes)$pathSegment • $status"
    }

    private fun resolveInboundChatId(contact: Contact, envelope: InboundEnvelope): Long? {
        val groupId = envelope.groupId
        if (!groupId.isNullOrBlank()) {
            val existingGroupChat = chatRepository.groupChatByRemoteId(groupId)
            if (existingGroupChat != null) {
                if (!chatRepository.isProfileMemberOfGroup(existingGroupChat.id, envelope.senderProfileId)) {
                    Log.w(TAG, "Dropping group envelope for $groupId from non-member ${envelope.senderProfileId}")
                    return null
                }
                return existingGroupChat.id
            }
            return chatRepository.ensureInboundGroupChat(
                groupId = groupId,
                groupTitle = envelope.groupTitle,
                senderProfileId = envelope.senderProfileId,
                memberProfileIds = envelope.groupMemberProfileIds
            )
        }
        return chatRepository.chatForContact(contact).id
    }

    private fun sendEnvelope(contact: Contact, envelope: OutboundEnvelope, senderPublicKey: String) {
        val payload = LanMessageProtocol.encode(envelope, senderPublicKey)
        messageClient.send(contact.host, contact.port, payload)
    }

    private fun sendGroupMembershipUpdate(
        contact: Contact,
        groupId: String,
        groupTitle: String,
        memberProfileIds: List<String>,
        adminProfileId: String,
        senderProfileId: String,
        senderPublicKey: String,
        chatId: Long,
        updatedAtEpochMs: Long
    ) {
        val payload = org.json.JSONObject()
            .put("kind", GROUP_MEMBERSHIP_KIND)
            .put("groupId", groupId)
            .put("groupTitle", groupTitle)
            .put("adminProfileId", adminProfileId)
            .put("memberProfileIds", org.json.JSONArray().apply { memberProfileIds.forEach(::put) })
            .put("updatedAtEpochMs", updatedAtEpochMs)
            .toString()

        val encrypted = signalSessionManager.encryptForPeer(
            peerProfileId = contact.profileId,
            peerIdentityPublicKeyB64 = contact.publicKey,
            peerBundle = contactSignalBundle(contact),
            plaintext = payload
        )

        sendEnvelope(
            contact = contact,
            envelope = OutboundEnvelope(
                chatId = chatId,
                senderProfileId = senderProfileId,
                senderDeviceId = localSenderDeviceId(),
                recipientProfileId = contact.profileId,
                groupId = groupId,
                groupTitle = groupTitle,
                groupMemberProfileIds = memberProfileIds,
                messageType = MessageType.SYSTEM,
                signalCipherType = encrypted.cipherType,
                ciphertext = encrypted.ciphertextB64,
                sentAtEpochMs = System.currentTimeMillis()
            ),
            senderPublicKey = senderPublicKey
        )
    }

    private fun buildGroupMembershipSummary(
        updatedByProfileId: String,
        previousMemberIds: List<String>,
        currentMemberIds: List<String>
    ): String {
        val added = currentMemberIds.filterNot(previousMemberIds::contains)
        val removed = previousMemberIds.filterNot(currentMemberIds::contains)
        val actor = profileLabel(updatedByProfileId)
        val localProfileId = identityManager.getProfileId().orEmpty()

        if (removed.contains(localProfileId)) {
            return "You were removed from the group by $actor"
        }

        val changes = buildList {
            if (added.isNotEmpty()) add("added ${added.joinToString(", ") { profileLabel(it) }}")
            if (removed.isNotEmpty()) add("removed ${removed.joinToString(", ") { profileLabel(it) }}")
        }

        return when {
            changes.isEmpty() -> "Group membership refreshed by $actor"
            actor == "You" -> "You updated the group: ${changes.joinToString("; ")}"
            else -> "$actor updated the group: ${changes.joinToString("; ")}"
        }
    }

    private fun profileLabel(profileId: String): String {
        val localProfileId = identityManager.getProfileId()
        if (profileId == localProfileId) return "You"
        return chatRepository.contactByProfileId(profileId)?.effectiveName ?: profileId.take(12)
    }

    private fun org.json.JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.optString(index)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun newTransferChunkCrypto(): TransferChunkCrypto {
        return TransferChunkCrypto(
            key = CryptoUtils.randomBytes(FILE_CHUNK_KEY_BYTES),
            noncePrefix = CryptoUtils.randomBytes(FILE_CHUNK_NONCE_PREFIX_BYTES)
        )
    }

    private fun parseTransferChunkCrypto(json: org.json.JSONObject): TransferChunkCrypto? {
        val cipher = json.optString("chunkCipher")
        if (cipher != FILE_CHUNK_CIPHER_AES_GCM_V1) {
            Log.w(TAG, "Unsupported file chunk cipher: $cipher")
            return null
        }
        val key = runCatching { CryptoUtils.b64Decode(json.optString("chunkKey")) }.getOrElse {
            Log.w(TAG, "Invalid file chunk key encoding", it)
            return null
        }
        val noncePrefix = runCatching { CryptoUtils.b64Decode(json.optString("chunkNoncePrefix")) }.getOrElse {
            Log.w(TAG, "Invalid file chunk nonce encoding", it)
            return null
        }
        if (key.size != FILE_CHUNK_KEY_BYTES || noncePrefix.size != FILE_CHUNK_NONCE_PREFIX_BYTES) {
            Log.w(TAG, "Invalid file chunk crypto sizes for offer")
            return null
        }
        return TransferChunkCrypto(key = key, noncePrefix = noncePrefix)
    }

    private fun encryptTransferChunk(
        state: OutgoingTransferState,
        chunkIndex: Int,
        plaintext: ByteArray
    ): String {
        val ciphertext = CryptoUtils.encryptAesGcmWithIv(
            plaintext = plaintext,
            key = state.chunkCrypto.key,
            iv = transferChunkNonce(state.chunkCrypto, chunkIndex),
            aad = transferChunkAad(state.transferId, chunkIndex)
        )
        return CryptoUtils.b64(ciphertext)
    }

    private fun decryptTransferChunk(
        transferId: String,
        chunkIndex: Int,
        ciphertextB64: String,
        chunkCrypto: TransferChunkCrypto
    ): ByteArray {
        return CryptoUtils.decryptAesGcmWithIv(
            ciphertext = CryptoUtils.b64Decode(ciphertextB64),
            key = chunkCrypto.key,
            iv = transferChunkNonce(chunkCrypto, chunkIndex),
            aad = transferChunkAad(transferId, chunkIndex)
        )
    }

    private fun transferChunkNonce(chunkCrypto: TransferChunkCrypto, chunkIndex: Int): ByteArray {
        require(chunkIndex >= 0) { "Chunk index must be non-negative" }
        return chunkCrypto.noncePrefix + java.nio.ByteBuffer.allocate(4).putInt(chunkIndex).array()
    }

    private fun transferChunkAad(transferId: String, chunkIndex: Int): ByteArray {
        return "$transferId:$chunkIndex".toByteArray(Charsets.UTF_8)
    }

    private fun contactSignalBundle(contact: Contact): SignalPreKeyBundleData? {
        val registrationId = contact.signalRegistrationId ?: return null
        val deviceId = contact.signalDeviceId ?: return null
        val preKeyId = contact.signalPreKeyId ?: return null
        val preKeyPublic = contact.signalPreKeyPublicKey ?: return null
        val signedId = contact.signalSignedPreKeyId ?: return null
        val signedPublic = contact.signalSignedPreKeyPublicKey ?: return null
        val signedSignature = contact.signalSignedPreKeySignature ?: return null

        return SignalPreKeyBundleData(
            registrationId = registrationId,
            deviceId = deviceId,
            preKeyId = preKeyId,
            preKeyPublicKey = preKeyPublic,
            signedPreKeyId = signedId,
            signedPreKeyPublicKey = signedPublic,
            signedPreKeySignature = signedSignature
        )
    }

    private fun localSenderDeviceId(): Int = signalSessionManager.localPreKeyBundle().deviceId

    private fun openInputStream(uri: Uri): InputStream {
        appContext.contentResolver.openInputStream(uri)?.let { return it }
        if (uri.scheme == "file") {
            val path = uri.path ?: error("Invalid file URI")
            return FileInputStream(path)
        }
        error("Unable to open input stream for $uri")
    }

    private fun copyUriToFile(sourceUri: Uri, targetFile: File) {
        openInputStream(sourceUri).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    private fun mimeToMessageType(mimeType: String): MessageType {
        return if (mimeType.startsWith("image/")) MessageType.IMAGE else MessageType.FILE
    }

    private data class IncomingTransferState(
        val transferId: String,
        val chatId: Long,
        val messageId: Long,
        val senderProfileId: String,
        val fileName: String,
        val mimeType: String,
        val messageType: MessageType,
        val targetFile: File,
        val expectedChunks: Int?,
        val expectedBytes: Long?,
        var expiresAtEpochMs: Long?
    ) {
        private val receivedChunkIndexes = mutableSetOf<Int>()
        var receivedChunks: Int = 0
            private set
        var receivedBytes: Long = 0
            private set

        @Synchronized
        fun appendChunk(chunkIndex: Int, bytes: ByteArray): Boolean {
            if (!receivedChunkIndexes.add(chunkIndex)) {
                return false
            }
            RandomAccessFile(targetFile, "rw").use { output ->
                output.seek(chunkIndex.toLong() * FILE_CHUNK_BYTES.toLong())
                output.write(bytes)
            }
            receivedChunks = receivedChunkIndexes.size
            receivedBytes += bytes.size
            return true
        }
    }

    private data class OutgoingTransferState(
        val transferId: String,
        val batchId: String? = null,
        val chatId: Long,
        val messageId: Long,
        val fileName: String,
        val totalBytes: Long?,
        var filePathForPreview: String,
        val sourceUri: Uri,
        val mimeType: String,
        val messageType: MessageType,
        val expiresAtEpochMs: Long?,
        val chunkCrypto: TransferChunkCrypto,
        var startedAtEpochMs: Long,
        val expectedConfirmationCount: Int,
        val recipientProfileIds: Set<String>,
        val pendingConfirmationProfileIds: MutableSet<String>,
        val awaitingApproval: AtomicBoolean,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false)
    ) {
        var sentBytes: Long = 0L
        var isComplete: Boolean = false
        var declined: Boolean = false
        var failedReason: String? = null
        var lastPreviewUpdateEpochMs: Long = 0L
    }

    private data class OutgoingTransferBatchState(
        val batchId: String,
        val chatId: Long,
        val messageId: Long,
        val fileName: String,
        val totalBytes: Long?,
        val filePathForPreview: String,
        val recipientProfileIds: Set<String>,
        val awaitingApprovalProfileIds: MutableSet<String>,
        val approvedProfileIds: MutableSet<String> = mutableSetOf(),
        val declinedProfileIds: MutableSet<String> = mutableSetOf(),
        val completedProfileIds: MutableSet<String> = mutableSetOf(),
        val failedProfileIds: MutableSet<String> = mutableSetOf(),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false)
    )

    private data class PendingInboundOfferState(
        val transferId: String,
        val chatId: Long,
        val messageId: Long,
        val senderProfileId: String,
        val fileName: String,
        val offer: FileTransferOffer,
        val chunkCrypto: TransferChunkCrypto?,
        val pendingResponse: AtomicBoolean = AtomicBoolean(true)
    )

    private data class TransferChunkCrypto(
        val key: ByteArray,
        val noncePrefix: ByteArray
    )

    private class TransferCanceledException : RuntimeException("Transfer canceled")

    companion object {
        private const val TAG = "AppContainer"
        const val MESSAGE_PORT = 53990
        private const val OVERLAY_SCAN_INTERVAL_MS = 60_000L
        private const val OVERLAY_SCAN_INITIAL_DELAY_MS = 10_000L
        private const val OVERLAY_SCAN_CONNECT_TIMEOUT_MS = 300
        private const val FILE_CHUNK_BYTES = 48 * 1024
        private const val FILE_CHUNK_KEY_BYTES = 32
        private const val FILE_CHUNK_NONCE_PREFIX_BYTES = 8
        private const val FILE_CHUNK_CIPHER_AES_GCM_V1 = "aes-gcm-v1"
        private const val PREVIEW_UPDATE_INTERVAL_MS = 500L
        private const val GROUP_MEMBERSHIP_KIND = "group.membership.v1"
        private const val FILE_OFFER_KIND = "file.offer.v1"
        private const val FILE_ACCEPT_KIND = "file.offer.accept.v1"
        private const val FILE_DECLINE_KIND = "file.offer.decline.v1"
        private const val FILE_ACK_KIND = "file.receive.ack.v1"
        private const val FILE_CANCEL_KIND = "file.transfer.cancel.v1"
        private const val DISAPPEARING_TIMER_KIND = "chat.disappearing.v1"

        fun formatDisappearingDuration(seconds: Long): String = when (seconds) {
            30L    -> "30 seconds"
            300L   -> "5 minutes"
            3600L  -> "1 hour"
            86400L -> "1 day"
            else   -> when {
                seconds < 60    -> "$seconds seconds"
                seconds < 3600  -> "${seconds / 60} minutes"
                seconds < 86400 -> "${seconds / 3600} hours"
                else            -> "${seconds / 86400} days"
            }
        }
    }
}
