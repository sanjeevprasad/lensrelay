package com.atanx.lensrelay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the desktop-signed acknowledgements that authorize this phone for media and control.
 *
 * A transcript is, for each field in order, its unsigned big-endian four-byte UTF-8 byte length
 * followed by the UTF-8 bytes themselves.
 */
object DesktopAcks {
    const val PAIRING_ACK_DOMAIN = "lensrelay-desktop-pairing-ack-v1"
    const val CONTROL_ACK_DOMAIN = "lensrelay-desktop-control-ack-v1"

    private val ED25519_X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun transcript(
        domain: String,
        receiverId: String,
        phoneId: String,
        nonce: String,
        tokenHash: String,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            listOf(domain, receiverId, phoneId, nonce, tokenHash).forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        bytes.toByteArray()
    }

    fun tokenHash(mediaToken: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(mediaToken.toByteArray(Charsets.UTF_8)),
    )

    fun verify(desktopPublicKeyBase64Url: String, transcript: ByteArray, signature: String) {
        val rawKey = decode(desktopPublicKeyBase64Url, "desktop public key")
        require(rawKey.size == 32) { "The desktop identity is invalid." }
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(ED25519_X509_PREFIX + rawKey),
        )
        val valid = Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(transcript)
            verify(decode(signature, "desktop acknowledgement"))
        }
        check(valid) { "The desktop acknowledgement could not be verified." }
    }

    private fun decode(value: String, label: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("The $label is invalid.")
    }
}
