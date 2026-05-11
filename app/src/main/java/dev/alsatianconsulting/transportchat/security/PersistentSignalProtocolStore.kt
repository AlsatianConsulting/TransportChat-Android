package dev.alsatianconsulting.transportchat.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.alsatianconsulting.transportchat.data.model.SignalPreKeyBundleData
import org.json.JSONObject
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyException
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.IdentityKeyStore
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.util.KeyHelper

class PersistentSignalProtocolStore(
    context: Context,
    private val identityManager: IdentityManager
) : SignalProtocolStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val lock = Any()

    private val localIdentityKeyPair: IdentityKeyPair by lazy {
        identityManager.getIdentityKeyPair()
            ?: run {
                identityManager.loadOrCreate(identityManager.getDisplayName("User"))
                identityManager.getIdentityKeyPair() ?: error("Identity key pair unavailable")
            }
    }

    init {
        ensureInitialized()
    }

    fun localBundle(identityPublicKeyB64: String): SignalPreKeyBundleData {
        synchronized(lock) {
            ensureInitialized()
            val preKey = currentPreKeyRecord()
            val signedPreKey = currentSignedPreKeyRecord()
            return SignalPreKeyBundleData(
                registrationId = getLocalRegistrationId(),
                deviceId = DEFAULT_DEVICE_ID,
                preKeyId = preKey.id,
                preKeyPublicKey = CryptoUtils.b64(preKey.keyPair.publicKey.serialize()),
                signedPreKeyId = signedPreKey.id,
                signedPreKeyPublicKey = CryptoUtils.b64(signedPreKey.keyPair.publicKey.serialize()),
                signedPreKeySignature = CryptoUtils.b64(signedPreKey.signature)
            )
        }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = localIdentityKeyPair

    override fun getLocalRegistrationId(): Int {
        synchronized(lock) {
            val existing = prefs.getInt(KEY_REGISTRATION_ID, -1)
            if (existing > 0) return existing

            val generated = KeyHelper.generateRegistrationId(false)
            prefs.edit().putInt(KEY_REGISTRATION_ID, generated).commit()
            return generated
        }
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        synchronized(lock) {
            val key = address.storageKey()
            val identities = loadStringMap(KEY_IDENTITIES)
            val existing = identities[key]
            val encoded = CryptoUtils.b64(identityKey.serialize())
            identities[key] = encoded
            saveStringMap(KEY_IDENTITIES, identities)
            return existing != encoded
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        synchronized(lock) {
            val existing = getIdentity(address)
            return existing == null || existing == identityKey
        }
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        synchronized(lock) {
            val encoded = loadStringMap(KEY_IDENTITIES)[address.storageKey()] ?: return null
            return try {
                IdentityKey(CryptoUtils.b64Decode(encoded), 0)
            } catch (_: InvalidKeyException) {
                null
            }
        }
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        synchronized(lock) {
            val encoded = loadStringMap(KEY_PREKEYS)[preKeyId.toString()]
                ?: throw InvalidKeyIdException("No pre-key for id=$preKeyId")
            return try {
                PreKeyRecord(CryptoUtils.b64Decode(encoded))
            } catch (t: Throwable) {
                throw InvalidKeyIdException(t)
            }
        }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        synchronized(lock) {
            val preKeys = loadStringMap(KEY_PREKEYS)
            preKeys[preKeyId.toString()] = CryptoUtils.b64(record.serialize())
            saveStringMap(KEY_PREKEYS, preKeys)
            val current = prefs.getInt(KEY_CURRENT_PREKEY_ID, -1)
            if (current == -1) {
                prefs.edit().putInt(KEY_CURRENT_PREKEY_ID, preKeyId).commit()
            }
        }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        synchronized(lock) {
            return loadStringMap(KEY_PREKEYS).containsKey(preKeyId.toString())
        }
    }

    override fun removePreKey(preKeyId: Int) {
        synchronized(lock) {
            val preKeys = loadStringMap(KEY_PREKEYS)
            preKeys.remove(preKeyId.toString())
            saveStringMap(KEY_PREKEYS, preKeys)

            val currentId = prefs.getInt(KEY_CURRENT_PREKEY_ID, -1)
            if (currentId == preKeyId) {
                rotateCurrentPreKeyLocked()
            }
        }
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        synchronized(lock) {
            val encoded = loadStringMap(KEY_SIGNED_PREKEYS)[signedPreKeyId.toString()]
                ?: throw InvalidKeyIdException("No signed pre-key for id=$signedPreKeyId")
            return try {
                SignedPreKeyRecord(CryptoUtils.b64Decode(encoded))
            } catch (t: Throwable) {
                throw InvalidKeyIdException(t)
            }
        }
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        synchronized(lock) {
            return loadStringMap(KEY_SIGNED_PREKEYS).values.mapNotNull {
                runCatching { SignedPreKeyRecord(CryptoUtils.b64Decode(it)) }.getOrNull()
            }
        }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        synchronized(lock) {
            val signed = loadStringMap(KEY_SIGNED_PREKEYS)
            signed[signedPreKeyId.toString()] = CryptoUtils.b64(record.serialize())
            saveStringMap(KEY_SIGNED_PREKEYS, signed)
            prefs.edit().putInt(KEY_CURRENT_SIGNED_PREKEY_ID, signedPreKeyId).commit()
        }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        synchronized(lock) {
            return loadStringMap(KEY_SIGNED_PREKEYS).containsKey(signedPreKeyId.toString())
        }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        synchronized(lock) {
            val signed = loadStringMap(KEY_SIGNED_PREKEYS)
            signed.remove(signedPreKeyId.toString())
            saveStringMap(KEY_SIGNED_PREKEYS, signed)
        }
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        synchronized(lock) {
            val sessions = loadStringMap(KEY_SESSIONS)
            val encoded = sessions[address.storageKey()]
            if (encoded == null) return SessionRecord()
            return runCatching { SessionRecord(CryptoUtils.b64Decode(encoded)) }
                .getOrDefault(SessionRecord())
        }
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        synchronized(lock) {
            return loadStringMap(KEY_SESSIONS)
                .keys
                .mapNotNull { key ->
                    val parts = key.split(ADDRESS_SEPARATOR)
                    if (parts.size != 2 || parts[0] != name) return@mapNotNull null
                    parts[1].toIntOrNull()
                }
                .filter { it != DEFAULT_DEVICE_ID }
                .distinct()
        }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        synchronized(lock) {
            val sessions = loadStringMap(KEY_SESSIONS)
            sessions[address.storageKey()] = CryptoUtils.b64(record.serialize())
            saveStringMap(KEY_SESSIONS, sessions)
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        synchronized(lock) {
            return loadStringMap(KEY_SESSIONS).containsKey(address.storageKey())
        }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        synchronized(lock) {
            val sessions = loadStringMap(KEY_SESSIONS)
            sessions.remove(address.storageKey())
            saveStringMap(KEY_SESSIONS, sessions)
        }
    }

    override fun deleteAllSessions(name: String) {
        synchronized(lock) {
            val sessions = loadStringMap(KEY_SESSIONS)
            val filtered = sessions.filterKeys { key ->
                !key.startsWith("$name$ADDRESS_SEPARATOR")
            }
            saveStringMap(KEY_SESSIONS, filtered.toMutableMap())
        }
    }

    private fun ensureInitialized() {
        synchronized(lock) {
            getLocalRegistrationId()
            ensurePreKeysLocked()
            ensureSignedPreKeyLocked()
        }
    }

    private fun currentPreKeyRecord(): PreKeyRecord {
        ensurePreKeysLocked()
        val currentId = prefs.getInt(KEY_CURRENT_PREKEY_ID, -1)
        if (currentId != -1 && containsPreKey(currentId)) {
            return loadPreKey(currentId)
        }
        rotateCurrentPreKeyLocked()
        val rotatedId = prefs.getInt(KEY_CURRENT_PREKEY_ID, -1)
        return loadPreKey(rotatedId)
    }

    private fun currentSignedPreKeyRecord(): SignedPreKeyRecord {
        ensureSignedPreKeyLocked()
        val id = prefs.getInt(KEY_CURRENT_SIGNED_PREKEY_ID, -1)
        if (id != -1 && containsSignedPreKey(id)) return loadSignedPreKey(id)

        val generatedId = KeyHelper.getRandomSequence(16_000)
        val generated = KeyHelper.generateSignedPreKey(localIdentityKeyPair, generatedId)
        storeSignedPreKey(generated.id, generated)
        return generated
    }

    private fun ensurePreKeysLocked() {
        val preKeys = loadStringMap(KEY_PREKEYS)
        if (preKeys.size >= MIN_PREKEY_POOL) {
            if (prefs.getInt(KEY_CURRENT_PREKEY_ID, -1) == -1) {
                val first = preKeys.keys.firstOrNull()?.toIntOrNull() ?: return
                prefs.edit().putInt(KEY_CURRENT_PREKEY_ID, first).commit()
            }
            return
        }

        val nextId = prefs.getInt(KEY_NEXT_PREKEY_ID, KeyHelper.getRandomSequence(MAX_PREKEY_ID))
        val needed = MIN_PREKEY_POOL - preKeys.size
        val generated = KeyHelper.generatePreKeys(nextId, needed)
        generated.forEach { record ->
            preKeys[record.id.toString()] = CryptoUtils.b64(record.serialize())
        }
        saveStringMap(KEY_PREKEYS, preKeys)

        val editor = prefs.edit()
        if (prefs.getInt(KEY_CURRENT_PREKEY_ID, -1) == -1 && generated.isNotEmpty()) {
            editor.putInt(KEY_CURRENT_PREKEY_ID, generated.first().id)
        }
        editor.putInt(KEY_NEXT_PREKEY_ID, generated.lastOrNull()?.id?.plus(1) ?: nextId)
        editor.commit()
    }

    private fun rotateCurrentPreKeyLocked() {
        val preKeys = loadStringMap(KEY_PREKEYS)
        val current = preKeys.keys
            .mapNotNull { it.toIntOrNull() }
            .sorted()
            .firstOrNull()

        if (current != null) {
            prefs.edit().putInt(KEY_CURRENT_PREKEY_ID, current).commit()
            return
        }

        ensurePreKeysLocked()
        val refreshed = loadStringMap(KEY_PREKEYS)
        val refreshedCurrent = refreshed.keys.mapNotNull { it.toIntOrNull() }.sorted().firstOrNull()
            ?: KeyHelper.generatePreKeys(KeyHelper.getRandomSequence(MAX_PREKEY_ID), 1).first().id

        if (!refreshed.containsKey(refreshedCurrent.toString())) {
            val generated = KeyHelper.generatePreKeys(refreshedCurrent, 1).first()
            refreshed[generated.id.toString()] = CryptoUtils.b64(generated.serialize())
            saveStringMap(KEY_PREKEYS, refreshed)
            prefs.edit().putInt(KEY_NEXT_PREKEY_ID, generated.id + 1).commit()
            prefs.edit().putInt(KEY_CURRENT_PREKEY_ID, generated.id).commit()
        } else {
            prefs.edit().putInt(KEY_CURRENT_PREKEY_ID, refreshedCurrent).commit()
        }
    }

    private fun ensureSignedPreKeyLocked() {
        val signed = loadStringMap(KEY_SIGNED_PREKEYS)
        if (signed.isNotEmpty() && prefs.getInt(KEY_CURRENT_SIGNED_PREKEY_ID, -1) != -1) {
            return
        }

        val id = KeyHelper.getRandomSequence(MAX_SIGNED_PREKEY_ID)
        val generated = KeyHelper.generateSignedPreKey(localIdentityKeyPair, id)
        signed[id.toString()] = CryptoUtils.b64(generated.serialize())
        saveStringMap(KEY_SIGNED_PREKEYS, signed)
        prefs.edit().putInt(KEY_CURRENT_SIGNED_PREKEY_ID, generated.id).commit()
    }

    private fun loadStringMap(key: String): MutableMap<String, String> {
        val raw = prefs.getString(key, null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val iterator = json.keys()
            while (iterator.hasNext()) {
                val item = iterator.next()
                map[item] = json.getString(item)
            }
            map
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    private fun saveStringMap(key: String, map: MutableMap<String, String>) {
        val json = JSONObject()
        map.forEach { (mapKey, value) -> json.put(mapKey, value) }
        prefs.edit().putString(key, json.toString()).commit()
    }

    private fun SignalProtocolAddress.storageKey(): String {
        return "$name$ADDRESS_SEPARATOR$deviceId"
    }

    companion object {
        private const val PREFS = "signal_store"
        private const val KEY_REGISTRATION_ID = "registration_id"
        private const val KEY_PREKEYS = "prekeys"
        private const val KEY_CURRENT_PREKEY_ID = "current_prekey_id"
        private const val KEY_NEXT_PREKEY_ID = "next_prekey_id"
        private const val KEY_SIGNED_PREKEYS = "signed_prekeys"
        private const val KEY_CURRENT_SIGNED_PREKEY_ID = "current_signed_prekey_id"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_IDENTITIES = "identities"

        private const val ADDRESS_SEPARATOR = "|"
        private const val DEFAULT_DEVICE_ID = 1
        private const val MIN_PREKEY_POOL = 64
        private const val MAX_PREKEY_ID = 65_000
        private const val MAX_SIGNED_PREKEY_ID = 16_000
    }
}
