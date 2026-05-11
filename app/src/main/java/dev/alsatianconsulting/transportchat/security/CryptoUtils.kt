package dev.alsatianconsulting.transportchat.security

import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 210_000
    private const val PBKDF2_KEY_BITS = 256

    fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    fun deriveKey(credential: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(credential, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun encryptAesGcm(plaintext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = randomBytes(12)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decryptAesGcm(blob: ByteArray, key: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(blob)
        val ivLength = buffer.int
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptAesGcmWithIv(
        plaintext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(plaintext)
    }

    fun decryptAesGcmWithIv(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertext)
    }

    fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun b64Decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    fun safetyPhraseFromPublicKey(publicKey: ByteArray): String {
        val digest = sha256(publicKey)
        val words = buildList {
            for (i in 0 until 5) {
                val offset = i * 2
                val part = ((digest[offset].toInt() and 0xFF) shl 8) or (digest[offset + 1].toInt() and 0xFF)
                add(WORD_LIST[part % WORD_LIST.size])
            }
        }
        return words.joinToString(" ")
    }

    private val WORD_LIST = listOf(
        "amber", "anchor", "apex", "arc", "aspen", "beacon", "blaze", "brisk", "cedar", "cinder",
        "cobalt", "comet", "crisp", "delta", "dune", "ember", "falcon", "fable", "flare", "frost",
        "glint", "granite", "harbor", "helium", "indigo", "iris", "jaguar", "juno", "kepler", "kinetic",
        "lumen", "matrix", "mesa", "meteor", "nebula", "onyx", "orbit", "plasma", "prism", "quartz",
        "radar", "raven", "saffron", "solstice", "tango", "topaz", "umbra", "vortex", "walnut", "zenith"
    )
}
