package dev.alsatianconsulting.transportchat.data.model

import java.time.Instant

enum class ContactTrustStatus {
    UNKNOWN,
    VERIFIED,
    MISMATCH
}

enum class MessageType {
    TEXT,
    FILE,
    IMAGE,
    LOCATION,
    CALL_SIGNAL,
    SYSTEM,
    DELIVERY_ACK,
    READ_ACK
}

enum class DeliveryStatus {
    SENT,
    DELIVERED,
    READ
}

enum class ChatType {
    DIRECT,
    GROUP
}

enum class GroupMemberRole {
    ADMIN,
    MEMBER
}

enum class LockType {
    PIN,
    PASSPHRASE
}

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallPhase {
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    ERROR
}

data class Contact(
    val id: Long,
    val profileId: String,
    val displayName: String,
    val nickname: String?,
    val host: String,
    val port: Int,
    val publicKey: String,
    val safetyPhrase: String,
    val signalRegistrationId: Int?,
    val signalDeviceId: Int?,
    val signalPreKeyId: Int?,
    val signalPreKeyPublicKey: String?,
    val signalSignedPreKeyId: Int?,
    val signalSignedPreKeyPublicKey: String?,
    val signalSignedPreKeySignature: String?,
    val trustStatus: ContactTrustStatus,
    val isManualPeer: Boolean,
    val lastSeenEpochMs: Long
) {
    val effectiveName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: displayName
}

data class Chat(
    val id: Long,
    val remoteId: String,
    val type: ChatType,
    val title: String,
    val createdByProfileId: String,
    val createdAtEpochMs: Long,
    val disappearingSeconds: Long?
)

data class GroupMember(
    val profileId: String,
    val displayName: String,
    val nickname: String?,
    val role: GroupMemberRole,
    val isLocalProfile: Boolean
) {
    val effectiveName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: displayName
}

data class Message(
    val id: Long,
    val chatId: Long,
    val senderProfileId: String,
    val encryptedBody: String,
    val decryptedPreview: String,
    val type: MessageType,
    val signalCipherType: Int?,
    val expiresAtEpochMs: Long?,
    val sentAtEpochMs: Long,
    val localOnly: Boolean = false,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENT,
    val readAtEpochMs: Long = 0L
) {
    val isExpired: Boolean
        get() = expiresAtEpochMs?.let { it <= Instant.now().toEpochMilli() } ?: false
}

data class OutgoingTransferProgress(
    val transferId: String,
    val chatId: Long,
    val messageId: Long,
    val fileName: String,
    val sentBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSec: Long,
    val etaSeconds: Long?,
    val isComplete: Boolean,
    val awaitingApproval: Boolean,
    val cancelRequested: Boolean,
    val failedReason: String?,
    val confirmationsReceived: Int,
    val confirmationsExpected: Int
) {
    val awaitingConfirmations: Boolean
        get() = isComplete && failedReason == null && confirmationsExpected > confirmationsReceived

    val isCanceled: Boolean
        get() = cancelRequested && !isComplete
}

data class VerificationRecord(
    val id: Long,
    val contactId: Long,
    val observedSafetyPhrase: String,
    val expectedSafetyPhrase: String,
    val verified: Boolean,
    val verifiedAtEpochMs: Long,
    val dismissedAtEpochMs: Long?
)

data class IdentityProfile(
    val profileId: String,
    val displayName: String,
    val publicKey: String,
    val privateKey: String,
    val safetyPhrase: String
)

data class SignalPreKeyBundleData(
    val registrationId: Int,
    val deviceId: Int,
    val preKeyId: Int,
    val preKeyPublicKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublicKey: String,
    val signedPreKeySignature: String
)

data class DiscoveredPeer(
    val profileId: String,
    val displayName: String,
    val publicKey: String,
    val safetyPhrase: String,
    val signalBundle: SignalPreKeyBundleData?,
    val host: String,
    val port: Int,
    val viaInterface: String,
    val discoveredAtEpochMs: Long
)

data class OutboundEnvelope(
    val chatId: Long,
    val senderProfileId: String,
    val senderDeviceId: Int,
    val recipientProfileId: String,
    val groupId: String? = null,
    val groupTitle: String? = null,
    val groupMemberProfileIds: List<String>? = null,
    val messageType: MessageType,
    val signalCipherType: Int?,
    val ciphertext: String,
    val transferId: String? = null,
    val transferName: String? = null,
    val transferMimeType: String? = null,
    val transferTotalBytes: Long? = null,
    val transferChunkIndex: Int? = null,
    val transferChunkCount: Int? = null,
    val transferComplete: Boolean? = null,
    val sentAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null
)

data class UnlockState(
    val configured: Boolean,
    val lockType: LockType?,
    val biometricEnabled: Boolean,
    val unlocked: Boolean
)

data class ActiveCallState(
    val peerProfileId: String,
    val displayName: String,
    val audioOnly: Boolean,
    val direction: CallDirection,
    val phase: CallPhase,
    val statusText: String,
    val microphoneEnabled: Boolean = true,
    val cameraEnabled: Boolean = false,
    val localVideoAvailable: Boolean = false,
    val remoteVideoAvailable: Boolean = false
)
