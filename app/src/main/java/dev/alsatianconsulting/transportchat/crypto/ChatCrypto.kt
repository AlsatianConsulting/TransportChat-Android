package dev.alsatianconsulting.transportchat.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ephemeral app-lifetime ECDH + AES-GCM utilities. */
object ChatCrypto {
    private const val CURVE = "secp256r1"
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val KEY_LEN = 32
    private val rnd = SecureRandom()

    private var pair: KeyPair? = null
    val publicB64: String
        get() {
            ensureKeys()
            return Base64.encodeToString(pair!!.public.encoded, Base64.NO_WRAP)
        }

    private fun ensureKeys() {
        if (pair == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(CURVE), rnd)
            pair = kpg.generateKeyPair()
        }
    }

    data class Session(val key: SecretKey)

    /** Derive a session key from our private and peer public (Base64 X.509). */
    fun deriveSession(peerPublicB64: String): Session {
        ensureKeys()
        val kf = KeyFactory.getInstance("EC")
        val peerPub = kf.generatePublic(
            X509EncodedKeySpec(Base64.decode(peerPublicB64, Base64.NO_WRAP))
        )

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(pair!!.private)
        ka.doPhase(peerPub, true)
        val shared = ka.generateSecret()

        val ours = pair!!.public.encoded
        val theirs = peerPub.encoded
        val info = if (Base64.encodeToString(ours, Base64.NO_WRAP) < Base64.encodeToString(theirs, Base64.NO_WRAP))
            ours + theirs else theirs + ours

        val prk = hkdfExtract("TransportChat-HKDF".toByteArray(), shared)
        val okm = hkdfExpand(prk, info, KEY_LEN)
        return Session(SecretKeySpec(okm, "AES"))
    }

    fun encryptToB64(session: Session, plain: ByteArray): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance(AES_TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, session.key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = c.doFinal(plain)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decryptFromB64(session: Session, b64: String): ByteArray {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance(AES_TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, session.key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return c.doFinal(ct)
    }

    /** HKDF-SHA256 */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        val out = ArrayList<Byte>()
        var counter = 1
        while (out.size < len) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.addAll(t.toList())
            counter++
        }
        return out.take(len).toByteArray()
    }
}
