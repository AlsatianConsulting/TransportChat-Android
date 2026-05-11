package dev.alsatianconsulting.transportchat.security

import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.SessionBuilder
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.protocol.CiphertextMessage
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.PreKeyBundle

data class SignalCipherPayload(
    val ciphertextB64: String,
    val cipherType: Int
)

class SignalSessionManager(
    private val identityManager: IdentityManager,
    private val store: PersistentSignalProtocolStore
) {
    fun localPreKeyBundle(): SignalPreKeyBundleData {
        val identityPublicKey = identityManager.getPublicKeyB64()
            ?: error("Identity public key unavailable")
        return store.localBundle(identityPublicKey)
    }

    fun encryptForPeer(
        peerProfileId: String,
        peerIdentityPublicKeyB64: String,
        peerBundle: SignalPreKeyBundleData?,
        plaintext: String
    ): SignalCipherPayload {
        return encryptBytesForPeer(
            peerProfileId = peerProfileId,
            peerIdentityPublicKeyB64 = peerIdentityPublicKeyB64,
            peerBundle = peerBundle,
            payload = plaintext.toByteArray(Charsets.UTF_8)
        )
    }

    fun encryptBytesForPeer(
        peerProfileId: String,
        peerIdentityPublicKeyB64: String,
        peerBundle: SignalPreKeyBundleData?,
        payload: ByteArray
    ): SignalCipherPayload {
        var address = SignalProtocolAddress(peerProfileId, peerBundle?.deviceId ?: DEFAULT_DEVICE_ID)
        if (!store.containsSession(address) && peerBundle == null) {
            val knownDevice = store.getSubDeviceSessions(peerProfileId).firstOrNull()
            if (knownDevice != null) {
                address = SignalProtocolAddress(peerProfileId, knownDevice)
            }
        }
        if (!store.containsSession(address)) {
            val bundle = peerBundle
                ?: error("Missing peer pre-key bundle and no existing Signal session for $peerProfileId")
            SessionBuilder(store, address).process(bundle.toPreKeyBundle(peerIdentityPublicKeyB64))
        }

        val cipher = SessionCipher(store, address)
        val encrypted = cipher.encrypt(payload)
        return SignalCipherPayload(
            ciphertextB64 = CryptoUtils.b64(encrypted.serialize()),
            cipherType = encrypted.type
        )
    }

    fun decryptFromPeer(
        peerProfileId: String,
        peerDeviceId: Int,
        cipherType: Int,
        ciphertextB64: String
    ): String {
        return decryptBytesFromPeer(peerProfileId, peerDeviceId, cipherType, ciphertextB64)
            .toString(Charsets.UTF_8)
    }

    fun decryptBytesFromPeer(
        peerProfileId: String,
        peerDeviceId: Int,
        cipherType: Int,
        ciphertextB64: String
    ): ByteArray {
        val address = SignalProtocolAddress(peerProfileId, peerDeviceId)
        val cipher = SessionCipher(store, address)
        val raw = CryptoUtils.b64Decode(ciphertextB64)
        return when (cipherType) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(raw))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(raw))
            else -> error("Unsupported Signal ciphertext type: $cipherType")
        }
    }

    private fun SignalPreKeyBundleData.toPreKeyBundle(identityPublicKeyB64: String): PreKeyBundle {
        val identity = IdentityKey(CryptoUtils.b64Decode(identityPublicKeyB64), 0)
        val preKey = Curve.decodePoint(CryptoUtils.b64Decode(preKeyPublicKey), 0)
        val signedPreKey = Curve.decodePoint(CryptoUtils.b64Decode(signedPreKeyPublicKey), 0)
        val signature = CryptoUtils.b64Decode(signedPreKeySignature)

        return PreKeyBundle(
            registrationId,
            deviceId,
            preKeyId,
            preKey,
            signedPreKeyId,
            signedPreKey,
            signature,
            identity
        )
    }

    companion object {
        private const val DEFAULT_DEVICE_ID = 1
    }
}
