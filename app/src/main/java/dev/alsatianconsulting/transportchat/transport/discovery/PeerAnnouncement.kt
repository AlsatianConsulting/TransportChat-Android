package dev.alsatianconsulting.transportchat.transport.discovery

data class PeerAnnouncement(
    val profileId: String,
    val displayName: String,
    val publicKey: String,
    val safetyPhrase: String,
    val signalRegistrationId: Int,
    val signalDeviceId: Int,
    val signalPreKeyId: Int,
    val signalPreKeyPublicKey: String,
    val signalSignedPreKeyId: Int,
    val signalSignedPreKeyPublicKey: String,
    val signalSignedPreKeySignature: String,
    val messagePort: Int
)
