package dev.alsatianconsulting.transportchat.data.repo

import android.content.Context
import android.net.Uri
import dev.alsatianconsulting.transportchat.data.db.EncryptedChatDatabase
import dev.alsatianconsulting.transportchat.data.model.Chat
import dev.alsatianconsulting.transportchat.data.model.ChatType
import dev.alsatianconsulting.transportchat.data.model.Contact
import dev.alsatianconsulting.transportchat.data.model.ContactTrustStatus
import dev.alsatianconsulting.transportchat.data.model.DiscoveredPeer
import dev.alsatianconsulting.transportchat.data.model.GroupMember
import dev.alsatianconsulting.transportchat.data.model.GroupMemberRole
import dev.alsatianconsulting.transportchat.data.model.DeliveryStatus
import dev.alsatianconsulting.transportchat.data.model.Message
import dev.alsatianconsulting.transportchat.data.model.MessageType
import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import dev.alsatianconsulting.transportchat.security.CryptoUtils
import dev.alsatianconsulting.transportchat.security.SignalSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GroupRoutingContext(
    val groupId: String,
    val title: String,
    val memberProfileIds: List<String>
)

class ChatRepository(
    private val context: Context,
    private val db: EncryptedChatDatabase,
    private val signalSessionManager: SignalSessionManager,
    private val localProfileIdProvider: () -> String
) {
    private val contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    private val chatsFlow = MutableStateFlow<List<Chat>>(emptyList())
    private val blockedProfileIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val chatContentVersionFlow = MutableStateFlow(0L)

    fun isUnlocked(): Boolean = db.isUnlocked()

    fun unlockDb(dbKey: ByteArray) {
        db.unlock(dbKey)
        refreshContacts()
        refreshChats()
        refreshBlockedProfileIds()
    }

    fun lockDb() {
        db.lock()
        contactsFlow.value = emptyList()
        chatsFlow.value = emptyList()
        blockedProfileIdsFlow.value = emptySet()
    }

    fun contacts(): StateFlow<List<Contact>> = contactsFlow.asStateFlow()

    fun chats(): StateFlow<List<Chat>> = chatsFlow.asStateFlow()

    fun blockedProfileIds(): StateFlow<Set<String>> = blockedProfileIdsFlow.asStateFlow()

    fun chatContentVersion(): StateFlow<Long> = chatContentVersionFlow.asStateFlow()

    fun messages(chatId: Long): List<Message> = db.getMessages(chatId)

    fun refreshContacts() {
        if (!db.isUnlocked()) return
        contactsFlow.value = db.getContacts()
    }

    fun refreshChats() {
        if (!db.isUnlocked()) return
        chatsFlow.value = db.getChats()
    }

    fun refreshBlockedProfileIds() {
        if (!db.isUnlocked()) return
        blockedProfileIdsFlow.value = db.getBlockedProfileIds()
    }

    fun contactByProfileId(profileId: String): Contact? {
        if (!db.isUnlocked()) return null
        return db.getContactByProfileId(profileId)
    }

    fun ingestPeer(peer: DiscoveredPeer, manual: Boolean = false): Contact {
        val existing = db.getContactByProfileId(peer.profileId)
        val trustStatus = when {
            existing == null -> ContactTrustStatus.UNKNOWN
            existing.publicKey == peer.publicKey -> existing.trustStatus
            else -> ContactTrustStatus.MISMATCH
        }
        val id = db.upsertContact(
            profileId = peer.profileId,
            displayName = peer.displayName,
            host = peer.host,
            port = peer.port,
            publicKey = peer.publicKey,
            safetyPhrase = peer.safetyPhrase,
            signalBundle = peer.signalBundle,
            trustStatus = trustStatus,
            isManualPeer = manual || existing?.isManualPeer == true
        )
        val stored = db.getContactByProfileId(peer.profileId) ?: error("Failed to upsert peer")
        db.ensureDirectChat(
            remoteProfileId = peer.profileId,
            title = stored.effectiveName,
            createdByProfileId = localProfileIdProvider()
        )
        refreshContacts()
        refreshChats()
        return stored.copy(id = id)
    }

    fun addManualPeer(
        host: String,
        port: Int,
        displayName: String,
        profileId: String,
        publicKey: String,
        signalBundle: SignalPreKeyBundleData
    ): Contact {
        val safetyPhrase = CryptoUtils.safetyPhraseFromPublicKey(CryptoUtils.b64Decode(publicKey))
        return ingestPeer(
            peer = DiscoveredPeer(
                profileId = profileId,
                displayName = displayName,
                publicKey = publicKey,
                safetyPhrase = safetyPhrase,
                signalBundle = signalBundle,
                host = host,
                port = port,
                viaInterface = "manual",
                discoveredAtEpochMs = System.currentTimeMillis()
            ),
            manual = true
        )
    }

    fun renameContact(contactId: Long, nickname: String?) {
        db.updateNickname(contactId, nickname?.trim())
        refreshContacts()
        contactsFlow.value.firstOrNull { it.id == contactId }?.let { updatedContact ->
            db.ensureDirectChat(
                remoteProfileId = updatedContact.profileId,
                title = updatedContact.effectiveName,
                createdByProfileId = localProfileIdProvider()
            )
        }
        refreshChats()
        bumpChatContentVersion()
    }

    fun createGroup(title: String, memberProfileIds: List<String>): Long {
        val localId = localProfileIdProvider()
        val chatId = db.createGroupChat(
            groupId = UUID.randomUUID().toString(),
            title = title,
            createdByProfileId = localId,
            memberProfileIds = (memberProfileIds + localId).distinct()
        )
        refreshChats()
        bumpChatContentVersion()
        return chatId
    }

    fun blockProfile(profileId: String) {
        db.blockProfile(profileId)
        refreshBlockedProfileIds()
        bumpChatContentVersion()
    }

    fun unblockProfile(profileId: String) {
        db.unblockProfile(profileId)
        refreshBlockedProfileIds()
        bumpChatContentVersion()
    }

    fun isProfileBlocked(profileId: String): Boolean {
        return blockedProfileIdsFlow.value.contains(profileId)
    }

    fun localProfileId(): String = localProfileIdProvider()

    fun localGroupJoinedAtEpochMs(chatId: Long): Long? {
        return db.groupMemberJoinedAt(chatId = chatId, profileId = localProfileIdProvider())
    }

    fun groupRecipientContacts(chatId: Long): List<Contact> {
        if (!isLocalGroupMember(chatId)) return emptyList()
        val localId = localProfileIdProvider()
        val profileIds = db.getGroupMemberProfileIds(chatId)
            .filterNot { it == localId }

        if (profileIds.isEmpty()) return emptyList()

        return profileIds.mapNotNull { memberId ->
            contactsFlow.value.firstOrNull { it.profileId == memberId }
                ?: db.getContactByProfileId(memberId)
        }
    }

    fun groupRoutingContext(chatId: Long): GroupRoutingContext? {
        val chat = chatsFlow.value.firstOrNull { it.id == chatId }
            ?: db.getChats().firstOrNull { it.id == chatId }
            ?: return null
        if (chat.type != ChatType.GROUP) return null

        return GroupRoutingContext(
            groupId = chat.remoteId,
            title = chat.title,
            memberProfileIds = db.getGroupMemberProfileIds(chatId)
        )
    }

    fun groupChatByRemoteId(groupId: String): Chat? {
        return chatsFlow.value.firstOrNull { it.type == ChatType.GROUP && it.remoteId == groupId }
            ?: db.getChats().firstOrNull { it.type == ChatType.GROUP && it.remoteId == groupId }
    }

    fun chatById(chatId: Long): Chat? {
        return chatsFlow.value.firstOrNull { it.id == chatId }
            ?: db.getChats().firstOrNull { it.id == chatId }
    }

    fun isLocalGroupMember(chatId: Long): Boolean {
        return isProfileMemberOfGroup(chatId, localProfileIdProvider())
    }

    fun isLocalGroupAdmin(chatId: Long): Boolean {
        val chat = chatsFlow.value.firstOrNull { it.id == chatId }
            ?: db.getChats().firstOrNull { it.id == chatId }
            ?: return false
        return chat.type == ChatType.GROUP && chat.createdByProfileId == localProfileIdProvider()
    }

    fun isProfileMemberOfGroup(chatId: Long, profileId: String): Boolean {
        return db.getGroupMemberProfileIds(chatId).contains(profileId)
    }

    fun groupMembers(chatId: Long): List<GroupMember> {
        val chat = chatsFlow.value.firstOrNull { it.id == chatId }
            ?: db.getChats().firstOrNull { it.id == chatId }
            ?: return emptyList()
        if (chat.type != ChatType.GROUP) return emptyList()

        val localId = localProfileIdProvider()
        return db.getGroupMemberProfileIds(chatId).map { memberId ->
            val contact = contactsFlow.value.firstOrNull { it.profileId == memberId }
                ?: db.getContactByProfileId(memberId)
            val isLocal = memberId == localId
            GroupMember(
                profileId = memberId,
                displayName = when {
                    isLocal -> "You"
                    contact != null -> contact.displayName
                    else -> memberId.take(12)
                },
                nickname = if (isLocal) null else contact?.nickname,
                role = if (memberId == chat.createdByProfileId) GroupMemberRole.ADMIN else GroupMemberRole.MEMBER,
                isLocalProfile = isLocal
            )
        }
    }

    fun availableContactsForGroup(chatId: Long): List<Contact> {
        val memberIds = db.getGroupMemberProfileIds(chatId).toSet()
        val blocked = blockedProfileIdsFlow.value
        return contactsFlow.value.filterNot { memberIds.contains(it.profileId) || blocked.contains(it.profileId) }
    }

    fun replaceGroupMembers(
        chatId: Long,
        memberProfileIds: List<String>,
        adminProfileId: String? = null,
        joinedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val chat = chatsFlow.value.firstOrNull { it.id == chatId }
            ?: db.getChats().firstOrNull { it.id == chatId }
            ?: return
        if (chat.type != ChatType.GROUP) return

        val resolvedAdmin = adminProfileId?.takeIf { memberProfileIds.contains(it) } ?: chat.createdByProfileId
        val normalizedMembers = (memberProfileIds + resolvedAdmin).distinct()
        db.replaceGroupMembers(chatId, resolvedAdmin, normalizedMembers, joinedAtEpochMs = joinedAtEpochMs)
        if (chat.createdByProfileId != resolvedAdmin) {
            db.updateGroupAdmin(chatId, resolvedAdmin)
        }
        refreshChats()
        bumpChatContentVersion()
    }

    fun ensureInboundGroupChat(
        groupId: String,
        groupTitle: String?,
        senderProfileId: String,
        memberProfileIds: List<String>?,
        joinedAtEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val localId = localProfileIdProvider()
        val mergedMembers = buildList {
            add(localId)
            add(senderProfileId)
            memberProfileIds?.forEach { add(it) }
        }.distinct()

        val chatId = db.ensureGroupChat(
            groupId = groupId,
            title = groupTitle?.takeIf { it.isNotBlank() } ?: "Group",
            createdByProfileId = senderProfileId,
            memberProfileIds = mergedMembers,
            joinedAtEpochMs = joinedAtEpochMs
        )
        refreshChats()
        bumpChatContentVersion()
        return chatId
    }

    fun syncInboundGroupChat(
        groupId: String,
        groupTitle: String?,
        createdByProfileId: String,
        memberProfileIds: List<String>,
        joinedAtEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val existing = groupChatByRemoteId(groupId)
        val creatorId = when {
            memberProfileIds.contains(createdByProfileId) -> createdByProfileId
            existing != null && memberProfileIds.contains(existing.createdByProfileId) -> existing.createdByProfileId
            else -> createdByProfileId
        }
        val normalizedMembers = (memberProfileIds + creatorId).distinct()
        val title = groupTitle?.takeIf { it.isNotBlank() } ?: existing?.title ?: "Group"
        val chatId = db.ensureGroupChat(
            groupId = groupId,
            title = title,
            createdByProfileId = creatorId,
            memberProfileIds = normalizedMembers,
            joinedAtEpochMs = joinedAtEpochMs
        )
        db.replaceGroupMembers(chatId, creatorId, normalizedMembers, joinedAtEpochMs = joinedAtEpochMs)
        if (existing != null && existing.createdByProfileId != creatorId) {
            db.updateGroupAdmin(chatId, creatorId)
        }
        if (existing == null || existing.title != title) {
            db.renameChat(chatId, title)
        }
        refreshChats()
        bumpChatContentVersion()
        return chatId
    }

    fun sendTextMessage(chatId: Long, contact: Contact, text: String, expiresAt: Long?): Message {
        val localId = localProfileIdProvider()
        val signalBundle = contact.signalBundleOrNull()

        val payload = signalSessionManager.encryptForPeer(
            peerProfileId = contact.profileId,
            peerIdentityPublicKeyB64 = contact.publicKey,
            peerBundle = signalBundle,
            plaintext = text
        )

        val id = db.insertMessage(
            chatId = chatId,
            senderProfileId = localId,
            encryptedBody = payload.ciphertextB64,
            decryptedPreview = text,
            type = MessageType.TEXT,
            signalCipherType = payload.cipherType,
            expiresAtEpochMs = expiresAt,
            localOnly = false
        )
        bumpChatContentVersion()
        return db.getMessages(chatId).first { it.id == id }
    }

    fun addOutgoingGroupTextMessage(chatId: Long, text: String, expiresAt: Long?) {
        db.insertMessage(
            chatId = chatId,
            senderProfileId = localProfileIdProvider(),
            encryptedBody = text,
            decryptedPreview = text,
            type = MessageType.TEXT,
            signalCipherType = null,
            expiresAtEpochMs = expiresAt,
            localOnly = false
        )
        bumpChatContentVersion()
    }

    fun addOutgoingGroupLocationMessage(
        chatId: Long,
        latitude: Double,
        longitude: Double,
        expiresAt: Long?
    ) {
        val payloadJson = JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timestamp", System.currentTimeMillis())
            .toString()
        val preview = "Location: $latitude, $longitude"

        db.insertMessage(
            chatId = chatId,
            senderProfileId = localProfileIdProvider(),
            encryptedBody = payloadJson,
            decryptedPreview = preview,
            type = MessageType.LOCATION,
            signalCipherType = null,
            expiresAtEpochMs = expiresAt,
            localOnly = false
        )
        bumpChatContentVersion()
    }

    fun ingestIncomingTextMessage(
        chatId: Long,
        senderProfileId: String,
        senderDeviceId: Int,
        signalCipherType: Int,
        ciphertext: String,
        expiresAt: Long?,
        sentAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val preview = runCatching {
            signalSessionManager.decryptFromPeer(
                peerProfileId = senderProfileId,
                peerDeviceId = senderDeviceId,
                cipherType = signalCipherType,
                ciphertextB64 = ciphertext
            )
        }.getOrDefault("[Unable to decrypt message]")

        db.insertMessage(
            chatId = chatId,
            senderProfileId = senderProfileId,
            encryptedBody = ciphertext,
            decryptedPreview = preview,
            type = MessageType.TEXT,
            signalCipherType = signalCipherType,
            expiresAtEpochMs = expiresAt,
            localOnly = false,
            sentAtEpochMs = sentAtEpochMs
        )
        bumpChatContentVersion()
    }

    fun updateDeliveryStatus(
        chatId: Long,
        senderProfileId: String,
        sentAtEpochMs: Long,
        status: DeliveryStatus,
        readAtEpochMs: Long = 0L
    ) {
        db.updateDeliveryStatusBySentAt(chatId, senderProfileId, sentAtEpochMs, status, readAtEpochMs)
        bumpChatContentVersion()
    }

    fun sendLocationMessage(
        chatId: Long,
        contact: Contact,
        latitude: Double,
        longitude: Double,
        expiresAt: Long?
    ): Message {
        val localId = localProfileIdProvider()
        val signalBundle = contact.signalBundleOrNull()
        val payloadJson = JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timestamp", System.currentTimeMillis())
            .toString()
        val preview = "Location: $latitude, $longitude"

        val encrypted = signalSessionManager.encryptForPeer(
            peerProfileId = contact.profileId,
            peerIdentityPublicKeyB64 = contact.publicKey,
            peerBundle = signalBundle,
            plaintext = payloadJson
        )

        val id = db.insertMessage(
            chatId = chatId,
            senderProfileId = localId,
            encryptedBody = encrypted.ciphertextB64,
            decryptedPreview = preview,
            type = MessageType.LOCATION,
            signalCipherType = encrypted.cipherType,
            expiresAtEpochMs = expiresAt,
            localOnly = false
        )
        bumpChatContentVersion()
        return db.getMessages(chatId).first { it.id == id }
    }

    fun ingestIncomingLocationMessage(
        chatId: Long,
        senderProfileId: String,
        senderDeviceId: Int,
        signalCipherType: Int,
        ciphertext: String,
        expiresAt: Long?,
        sentAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val decrypted = runCatching {
            signalSessionManager.decryptFromPeer(
                peerProfileId = senderProfileId,
                peerDeviceId = senderDeviceId,
                cipherType = signalCipherType,
                ciphertextB64 = ciphertext
            )
        }.getOrDefault("{\"error\":\"decrypt_failed\"}")

        val preview = runCatching {
            val json = JSONObject(decrypted)
            "Location: ${json.getDouble("latitude")}, ${json.getDouble("longitude")}"
        }.getOrDefault("[Location received]")

        db.insertMessage(
            chatId = chatId,
            senderProfileId = senderProfileId,
            encryptedBody = ciphertext,
            decryptedPreview = preview,
            type = MessageType.LOCATION,
            signalCipherType = signalCipherType,
            expiresAtEpochMs = expiresAt,
            localOnly = false,
            sentAtEpochMs = sentAtEpochMs
        )
        bumpChatContentVersion()
    }

    fun addFileTransferMessage(
        chatId: Long,
        senderProfileId: String,
        fileName: String,
        filePath: String,
        totalBytes: Long,
        messageType: MessageType,
        localOnly: Boolean,
        expiresAtEpochMs: Long?
    ) {
        db.insertMessage(
            chatId = chatId,
            senderProfileId = senderProfileId,
            encryptedBody = filePath,
            decryptedPreview = "File: $fileName (${totalBytes} bytes) @ $filePath",
            type = messageType,
            signalCipherType = null,
            expiresAtEpochMs = expiresAtEpochMs,
            localOnly = localOnly
        )
        bumpChatContentVersion()
    }

    fun createIncomingFileOfferPlaceholder(
        chatId: Long,
        senderProfileId: String,
        fileName: String,
        metadataJson: String,
        messageType: MessageType,
        totalBytes: Long?,
        expiresAtEpochMs: Long?
    ): Long {
        val totalLabel = totalBytes?.toString() ?: "unknown"
        return db.insertMessage(
            chatId = chatId,
            senderProfileId = senderProfileId,
            encryptedBody = metadataJson,
            decryptedPreview = "Incoming file offer: $fileName ($totalLabel bytes) • Accept or Decline",
            type = messageType,
            signalCipherType = null,
            expiresAtEpochMs = expiresAtEpochMs,
            localOnly = false
        ).also { bumpChatContentVersion() }
    }

    fun createOutgoingFileTransferPlaceholder(
        chatId: Long,
        senderProfileId: String,
        fileName: String,
        filePath: String,
        messageType: MessageType,
        totalBytes: Long?,
        expiresAtEpochMs: Long?
    ): Long {
        val totalLabel = totalBytes?.toString() ?: "unknown"
        return db.insertMessage(
            chatId = chatId,
            senderProfileId = senderProfileId,
            encryptedBody = filePath,
            decryptedPreview = "Sending $fileName (0/$totalLabel bytes)",
            type = messageType,
            signalCipherType = null,
            expiresAtEpochMs = expiresAtEpochMs,
            localOnly = true
        ).also { bumpChatContentVersion() }
    }

    fun updateTransferMessageStatus(messageId: Long, decryptedPreview: String, filePath: String? = null) {
        db.updateMessageContent(messageId = messageId, encryptedBody = filePath, decryptedPreview = decryptedPreview)
        bumpChatContentVersion()
    }

    fun addLocalSystemMessage(chatId: Long, text: String) {
        db.insertMessage(
            chatId = chatId,
            senderProfileId = localProfileIdProvider(),
            encryptedBody = text,
            decryptedPreview = text,
            type = MessageType.SYSTEM,
            signalCipherType = null,
            expiresAtEpochMs = null,
            localOnly = true
        )
        bumpChatContentVersion()
    }

    fun markChatRead(chatId: Long, epochMs: Long) {
        if (epochMs <= 0L) return
        db.updateChatLastReadEpoch(chatId, epochMs)
    }

    fun loadLastReadEpochs(): Map<Long, Long> = db.loadChatLastReadEpochs()

    fun clearChat(chatId: Long) {
        db.clearChat(chatId)
        bumpChatContentVersion()
    }

    fun renameChat(chatId: Long, newName: String) {
        db.renameChat(chatId, newName)
        refreshChats()
        bumpChatContentVersion()
    }

    fun setChatDisappearingSeconds(chatId: Long, seconds: Long?) {
        db.updateChatDisappearingSeconds(chatId, seconds)
        refreshChats()
        bumpChatContentVersion()
    }

    fun exportChat(chatId: Long, title: String, messagesOverride: List<Message>? = null): Uri {
        val export = if (messagesOverride == null) {
            db.exportChatPlaintext(chatId, title)
        } else {
            buildString {
                appendLine("Chat Export: $title")
                appendLine("Exported At: ${System.currentTimeMillis()}")
                appendLine("----------------------------------------")
                messagesOverride.forEach { message ->
                    appendLine("${message.sentAtEpochMs} | ${message.senderProfileId}: ${message.decryptedPreview}")
                }
            }
        }
        val dir = File(context.filesDir, "exports")
        dir.mkdirs()
        val file = File(dir, "chat_${chatId}_${System.currentTimeMillis()}.txt")
        file.writeText(export)
        return Uri.fromFile(file)
    }

    fun verifyContact(contactId: Long, observedSafetyPhrase: String, expectedSafetyPhrase: String) {
        db.upsertVerification(
            contactId = contactId,
            observedSafetyPhrase = observedSafetyPhrase,
            expectedSafetyPhrase = expectedSafetyPhrase,
            verified = observedSafetyPhrase == expectedSafetyPhrase
        )
        refreshContacts()
        bumpChatContentVersion()
    }

    fun dismissVerificationWarning(contactId: Long) {
        db.dismissVerificationWarning(contactId)
        bumpChatContentVersion()
    }

    fun latestVerification(contactId: Long) = db.latestVerification(contactId)

    fun chatForContact(contact: Contact): Chat {
        val existing = chatsFlow.value.firstOrNull {
            it.type == ChatType.DIRECT && it.remoteId == contact.profileId
        }
        if (existing != null) return existing

        val id = db.ensureDirectChat(contact.profileId, contact.effectiveName, localProfileIdProvider())
        refreshChats()
        return chatsFlow.value.first { it.id == id }
    }

    private fun Contact.signalBundleOrNull(): SignalPreKeyBundleData? {
        val registrationId = signalRegistrationId ?: return null
        val deviceId = signalDeviceId ?: return null
        val preKeyId = signalPreKeyId ?: return null
        val preKeyPublic = signalPreKeyPublicKey ?: return null
        val signedId = signalSignedPreKeyId ?: return null
        val signedPublic = signalSignedPreKeyPublicKey ?: return null
        val signedSignature = signalSignedPreKeySignature ?: return null

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

    private fun bumpChatContentVersion() {
        chatContentVersionFlow.value += 1
    }
}
