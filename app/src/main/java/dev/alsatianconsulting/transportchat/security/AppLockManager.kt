package dev.alsatianconsulting.transportchat.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.alsatianconsulting.transportchat.data.model.LockType
import dev.alsatianconsulting.transportchat.data.model.UnlockState
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AppLockManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getState(unlocked: Boolean): UnlockState {
        return UnlockState(
            configured = isConfigured(),
            lockType = getLockType(),
            biometricEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false),
            unlocked = unlocked
        )
    }

    fun isConfigured(): Boolean = prefs.contains(KEY_CREDENTIAL_HASH)

    fun getLockType(): LockType? {
        val raw = prefs.getString(KEY_LOCK_TYPE, null) ?: return null
        return runCatching { LockType.valueOf(raw) }.getOrNull()
    }

    fun setupLock(lockType: LockType, credential: CharArray, biometricEnabled: Boolean): ByteArray {
        require(credential.isNotEmpty()) { "Credential cannot be empty" }

        val credentialSalt = CryptoUtils.randomBytes(16)
        val derived = CryptoUtils.deriveKey(credential, credentialSalt)
        val credentialHash = CryptoUtils.sha256(derived)

        val dbKey = CryptoUtils.randomBytes(32)
        val wrappedDbKey = CryptoUtils.encryptAesGcm(dbKey, derived)

        val editor = prefs.edit()
        editor.putString(KEY_LOCK_TYPE, lockType.name)
        editor.putString(KEY_CREDENTIAL_SALT, CryptoUtils.b64(credentialSalt))
        editor.putString(KEY_CREDENTIAL_HASH, CryptoUtils.b64(credentialHash))
        editor.putString(KEY_DB_KEY_WRAPPED, CryptoUtils.b64(wrappedDbKey))
        editor.putBoolean(KEY_BIOMETRIC_ENABLED, biometricEnabled)

        if (biometricEnabled) {
            val biometricEncrypted = encryptWithKeystore(dbKey)
            editor.putString(KEY_BIO_IV, CryptoUtils.b64(biometricEncrypted.iv))
            editor.putString(KEY_BIO_DB_KEY, CryptoUtils.b64(biometricEncrypted.ciphertext))
        } else {
            editor.remove(KEY_BIO_IV)
            editor.remove(KEY_BIO_DB_KEY)
        }
        editor.apply()

        return dbKey
    }

    fun unlockWithCredential(credential: CharArray): ByteArray? {
        val salt = prefs.getString(KEY_CREDENTIAL_SALT, null)?.let(CryptoUtils::b64Decode) ?: return null
        val expected = prefs.getString(KEY_CREDENTIAL_HASH, null)?.let(CryptoUtils::b64Decode) ?: return null
        val wrappedDbKey = prefs.getString(KEY_DB_KEY_WRAPPED, null)?.let(CryptoUtils::b64Decode) ?: return null

        val derived = CryptoUtils.deriveKey(credential, salt)
        val actual = CryptoUtils.sha256(derived)
        if (!expected.contentEquals(actual)) {
            return null
        }

        return CryptoUtils.decryptAesGcm(wrappedDbKey, derived)
    }

    fun canUseBiometric(): Boolean {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false) &&
            prefs.contains(KEY_BIO_IV) &&
            prefs.contains(KEY_BIO_DB_KEY)
    }

    fun unlockWithBiometricStore(): ByteArray? {
        if (!canUseBiometric()) return null
        val iv = prefs.getString(KEY_BIO_IV, null)?.let(CryptoUtils::b64Decode) ?: return null
        val encrypted = prefs.getString(KEY_BIO_DB_KEY, null)?.let(CryptoUtils::b64Decode) ?: return null
        return decryptWithKeystore(iv, encrypted)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun initEncryptCipher(): Cipher {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    fun initDecryptCipher(iv: ByteArray): Cipher {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher
    }

    fun encryptWithCipher(data: ByteArray, cipher: Cipher): CipherPayload {
        return CipherPayload(cipher.iv, cipher.doFinal(data))
    }

    fun decryptWithCipher(data: ByteArray, cipher: Cipher): ByteArray {
        return cipher.doFinal(data)
    }

    private fun encryptWithKeystore(data: ByteArray): CipherPayload {
        return encryptWithCipher(data, initEncryptCipher())
    }

    private fun decryptWithKeystore(iv: ByteArray, data: ByteArray): ByteArray {
        return decryptWithCipher(data, initDecryptCipher(iv))
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            specBuilder.setUserAuthenticationParameters(
                BIOMETRIC_AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(BIOMETRIC_AUTH_VALIDITY_SECONDS)
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    fun ensureCharArrayWiped(chars: CharArray) {
        for (i in chars.indices) {
            chars[i] = '\u0000'
        }
    }

    fun credentialToChars(value: String): CharArray {
        return value.toByteArray(StandardCharsets.UTF_8).decodeToString().toCharArray()
    }

    data class CipherPayload(val iv: ByteArray, val ciphertext: ByteArray)

    companion object {
        private const val PREFS = "app_lock"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "transportchat.biometric.dbkey"

        private const val BIOMETRIC_AUTH_VALIDITY_SECONDS = 30

        private const val KEY_LOCK_TYPE = "lock_type"
        private const val KEY_CREDENTIAL_SALT = "credential_salt"
        private const val KEY_CREDENTIAL_HASH = "credential_hash"
        private const val KEY_DB_KEY_WRAPPED = "db_key_wrapped"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_BIO_IV = "bio_iv"
        private const val KEY_BIO_DB_KEY = "bio_db_key"
    }
}
