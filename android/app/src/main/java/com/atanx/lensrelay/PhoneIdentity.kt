package com.atanx.lensrelay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

data class PhonePublicIdentity(
    val algorithm: String,
    val phoneId: String,
    val publicKey: String,
    val fingerprint: String,
)

data class PhonePairingProof(
    val identity: PhonePublicIdentity,
    val signature: String,
)

data class PhoneUnpairProof(
    val identity: PhonePublicIdentity,
    val issuedAt: Long,
    val nonce: String,
    val signature: String,
)

class PhoneIdentity {
    fun publicIdentity(): PhonePublicIdentity {
        ensureKeyExists()
        val publicKey = keyStore().getCertificate(KEY_ALIAS).publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return PhonePublicIdentity(
            algorithm = ALGORITHM_ID,
            phoneId = encode(digest.copyOfRange(0, 16)),
            publicKey = encode(publicKey),
            fingerprint = digest.toFingerprint(),
        )
    }

    fun createPairingProof(payload: PairingPayload, phoneName: String): PhonePairingProof {
        val identity = publicIdentity()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey())
            update(PairingChallenge.encode(payload, phoneName))
            sign()
        }
        return PhonePairingProof(identity = identity, signature = encode(signature))
    }

    fun createUnpairProof(receiverId: String): PhoneUnpairProof {
        val identity = publicIdentity()
        val issuedAt = System.currentTimeMillis() / 1_000
        val nonce = ByteArray(24).also(SecureRandom()::nextBytes).let(::encode)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey())
            update(UnpairProtocol.phoneChallenge(receiverId, identity.phoneId, nonce, issuedAt))
            sign()
        }
        return PhoneUnpairProof(identity, issuedAt, nonce, encode(signature))
    }

    fun createControlProof(receiverId: String): PhoneControlProof {
        val identity = publicIdentity()
        val issuedAt = System.currentTimeMillis() / 1_000
        val nonce = ByteArray(24).also(SecureRandom()::nextBytes).let(::encode)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey())
            update(ControlProtocol.phoneChallenge(receiverId, identity.phoneId, nonce, issuedAt))
            sign()
        }
        return PhoneControlProof(identity, issuedAt, nonce, encode(signature))
    }

    private fun ensureKeyExists() {
        if (keyStore().containsAlias(KEY_ALIAS)) return

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE_NAME))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private fun privateKey(): PrivateKey {
        ensureKeyExists()
        return keyStore().getKey(KEY_ALIAS, null) as PrivateKey
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun ByteArray.toFingerprint(): String = asList()
        .chunked(4)
        .joinToString(" ") { chunk ->
            chunk.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "lensrelay_phone_identity_v1"
        private const val CURVE_NAME = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val ALGORITHM_ID = "ES256"
    }
}
