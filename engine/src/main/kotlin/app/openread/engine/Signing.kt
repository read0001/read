package app.openread.engine

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The subscription is fetched through public GitHub mirrors, any of which could serve altered content. The checker signs
 * the published file (ECDSA P-256 / SHA-256) and the app only accepts a copy whose signature verifies, so a mirror cannot
 * slip book sources, and the scripts inside them, into the app.
 */
object Signing {
    private const val ALGORITHM = "SHA256withECDSA"

    class KeyPair(val publicKey: String, val privateKey: String)

    fun generate(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val b64 = Base64.getEncoder()
        return KeyPair(b64.encodeToString(g.public.encoded), b64.encodeToString(g.private.encoded))
    }

    fun sign(privateKeyBase64: String, data: ByteArray): String {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.trim())))
        val sig = Signature.getInstance(ALGORITHM).apply { initSign(key); update(data) }.sign()
        return Base64.getEncoder().encodeToString(sig)
    }

    fun verify(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
        Signature.getInstance(ALGORITHM).apply { initVerify(key); update(data) }.verify(Base64.getMimeDecoder().decode(signatureBase64.trim()))
    }.getOrDefault(false)
}
