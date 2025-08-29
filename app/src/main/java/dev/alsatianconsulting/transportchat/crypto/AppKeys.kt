package dev.alsatianconsulting.transportchat.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object AppKeys {
    private const val PREFS = "lanchat_prefs"
    private const val KEY_PRIV = "app_ec_priv_b64"
    private const val KEY_PUB = "app_ec_pub_b64"

    @Volatile private var cached: KeyPair? = null

    fun loadOrCreate(context: Context): KeyPair {
        cached?.let { return it }
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pubB64 = sp.getString(KEY_PUB, null)
        val privB64 = sp.getString(KEY_PRIV, null)
        if (pubB64 != null && privB64 != null) {
            val pub = decodePublic(pubB64)
            val priv = decodePrivate(privB64)
            return KeyPair(pub, priv).also { cached = it }
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        sp.edit()
            .putString(KEY_PUB, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        cached = kp
        return kp
    }

    fun publicB64(context: Context): String =
        Base64.encodeToString(loadOrCreate(context).public.encoded, Base64.NO_WRAP)

    fun privateKey(context: Context): PrivateKey = loadOrCreate(context).private
    fun publicKey(context: Context): PublicKey = loadOrCreate(context).public

    private fun decodePublic(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }
    private fun decodePrivate(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val spec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePrivate(spec)
    }
}
