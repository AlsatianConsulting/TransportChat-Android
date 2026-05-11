package dev.alsatianconsulting.transportchat.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.alsatianconsulting.transportchat.data.model.IdentityProfile
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.util.KeyHelper
import java.util.UUID

class IdentityManager(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun loadOrCreate(displayName: String): IdentityProfile {
        val existingProfileId = prefs.getString(KEY_PROFILE_ID, null)
        val existingPublicKey = prefs.getString(KEY_PUBLIC_KEY, null)
        val existingPrivateKey = prefs.getString(KEY_PRIVATE_KEY, null)
        val existingSafetyPhrase = prefs.getString(KEY_SAFETY_PHRASE, null)

        if (existingProfileId != null && existingPublicKey != null && existingPrivateKey != null && existingSafetyPhrase != null) {
            return IdentityProfile(
                profileId = existingProfileId,
                displayName = prefs.getString(KEY_DISPLAY_NAME, displayName) ?: displayName,
                publicKey = existingPublicKey,
                privateKey = existingPrivateKey,
                safetyPhrase = existingSafetyPhrase
            )
        }

        val pair = KeyHelper.generateIdentityKeyPair()
        val profile = IdentityProfile(
            profileId = UUID.randomUUID().toString(),
            displayName = displayName,
            publicKey = CryptoUtils.b64(pair.publicKey.serialize()),
            privateKey = CryptoUtils.b64(pair.privateKey.serialize()),
            safetyPhrase = CryptoUtils.safetyPhraseFromPublicKey(pair.publicKey.serialize())
        )

        prefs.edit()
            .putString(KEY_PROFILE_ID, profile.profileId)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .putString(KEY_PUBLIC_KEY, profile.publicKey)
            .putString(KEY_PRIVATE_KEY, profile.privateKey)
            .putString(KEY_SAFETY_PHRASE, profile.safetyPhrase)
            .apply()

        return profile
    }

    fun getIdentityKeyPair(): IdentityKeyPair? {
        val publicRaw = prefs.getString(KEY_PUBLIC_KEY, null)?.let(CryptoUtils::b64Decode) ?: return null
        val privateRaw = prefs.getString(KEY_PRIVATE_KEY, null)?.let(CryptoUtils::b64Decode) ?: return null
        return IdentityKeyPair(IdentityKey(publicRaw, 0), org.whispersystems.libsignal.ecc.Curve.decodePrivatePoint(privateRaw))
    }

    fun getPublicKeyB64(): String? = prefs.getString(KEY_PUBLIC_KEY, null)

    fun getSafetyPhrase(): String? = prefs.getString(KEY_SAFETY_PHRASE, null)

    fun updateDisplayName(displayName: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, displayName.trim()).apply()
    }

    fun getDisplayName(defaultName: String = "User"): String {
        return prefs.getString(KEY_DISPLAY_NAME, defaultName) ?: defaultName
    }

    fun getProfileId(): String? = prefs.getString(KEY_PROFILE_ID, null)

    companion object {
        private const val PREFS = "identity_secure"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_SAFETY_PHRASE = "safety_phrase"
    }
}
