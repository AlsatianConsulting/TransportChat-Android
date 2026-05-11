package dev.alsatianconsulting.transportchat.transport.messaging

import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import org.json.JSONObject

data class EndpointPeerProfile(
    val profileId: String,
    val displayName: String,
    val publicKey: String,
    val safetyPhrase: String,
    val signalBundle: SignalPreKeyBundleData,
    val messagePort: Int
)

object PeerProfileProtocol {
    fun encodeRequest(): ByteArray {
        return JSONObject()
            .put("kind", PROFILE_REQUEST_KIND)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeRequest(payload: ByteArray): Boolean {
        val json = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return false
        return json.optString("kind") == PROFILE_REQUEST_KIND
    }

    fun encodeResponse(profile: EndpointPeerProfile): ByteArray {
        return JSONObject()
            .put("kind", PROFILE_RESPONSE_KIND)
            .put("profileId", profile.profileId)
            .put("displayName", profile.displayName)
            .put("publicKey", profile.publicKey)
            .put("safetyPhrase", profile.safetyPhrase)
            .put("signalRegistrationId", profile.signalBundle.registrationId)
            .put("signalDeviceId", profile.signalBundle.deviceId)
            .put("signalPreKeyId", profile.signalBundle.preKeyId)
            .put("signalPreKeyPublicKey", profile.signalBundle.preKeyPublicKey)
            .put("signalSignedPreKeyId", profile.signalBundle.signedPreKeyId)
            .put("signalSignedPreKeyPublicKey", profile.signalBundle.signedPreKeyPublicKey)
            .put("signalSignedPreKeySignature", profile.signalBundle.signedPreKeySignature)
            .put("messagePort", profile.messagePort)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeResponse(payload: ByteArray): EndpointPeerProfile? {
        return runCatching {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            if (json.optString("kind") != PROFILE_RESPONSE_KIND) return null

            val profileId = json.optString("profileId").trim()
            val displayName = json.optString("displayName").trim()
            val publicKey = json.optString("publicKey").trim()
            val safetyPhrase = json.optString("safetyPhrase").trim()
            val registrationId = json.optInt("signalRegistrationId", 0)
            val deviceId = json.optInt("signalDeviceId", 0)
            val preKeyId = json.optInt("signalPreKeyId", 0)
            val preKeyPublicKey = json.optString("signalPreKeyPublicKey").trim()
            val signedPreKeyId = json.optInt("signalSignedPreKeyId", 0)
            val signedPreKeyPublicKey = json.optString("signalSignedPreKeyPublicKey").trim()
            val signedPreKeySignature = json.optString("signalSignedPreKeySignature").trim()
            val messagePort = json.optInt("messagePort", 0)

            if (
                profileId.isBlank() ||
                displayName.isBlank() ||
                publicKey.isBlank() ||
                registrationId <= 0 ||
                deviceId <= 0 ||
                preKeyId <= 0 ||
                preKeyPublicKey.isBlank() ||
                signedPreKeyId <= 0 ||
                signedPreKeyPublicKey.isBlank() ||
                signedPreKeySignature.isBlank() ||
                messagePort <= 0
            ) {
                return null
            }

            EndpointPeerProfile(
                profileId = profileId,
                displayName = displayName,
                publicKey = publicKey,
                safetyPhrase = safetyPhrase,
                signalBundle = SignalPreKeyBundleData(
                    registrationId = registrationId,
                    deviceId = deviceId,
                    preKeyId = preKeyId,
                    preKeyPublicKey = preKeyPublicKey,
                    signedPreKeyId = signedPreKeyId,
                    signedPreKeyPublicKey = signedPreKeyPublicKey,
                    signedPreKeySignature = signedPreKeySignature
                ),
                messagePort = messagePort
            )
        }.getOrNull()
    }

    private const val PROFILE_REQUEST_KIND = "peer.profile.request.v1"
    private const val PROFILE_RESPONSE_KIND = "peer.profile.response.v1"
}
