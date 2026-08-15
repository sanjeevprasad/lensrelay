package com.atanx.lensrelay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object UnpairProtocol {
    private const val PHONE_DOMAIN = "lensrelay-phone-unpair-v1"
    private const val DESKTOP_ACK_DOMAIN = "lensrelay-desktop-unpair-ack-v1"
    private val ED25519_X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun phoneChallenge(
        receiverId: String,
        phoneId: String,
        nonce: String,
        issuedAt: Long,
    ): ByteArray = encodeFields(PHONE_DOMAIN, receiverId, phoneId, nonce, trailingLong = issuedAt)

    fun verifyDesktopAcknowledgement(
        desktop: PairedDesktop,
        phoneId: String,
        nonce: String,
        signature: String,
    ) {
        val rawKey = decode(desktop.publicKey, "desktop public key")
        require(rawKey.size == 32) { "The desktop identity is invalid." }
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(ED25519_X509_PREFIX + rawKey),
        )
        val valid = Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(
                encodeFields(
                    DESKTOP_ACK_DOMAIN,
                    desktop.receiverId,
                    phoneId,
                    nonce,
                ),
            )
            verify(decode(signature, "desktop acknowledgement"))
        }
        check(valid) { "The desktop acknowledgement could not be verified." }
    }

    private fun encodeFields(
        vararg fields: String,
        trailingLong: Long? = null,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            fields.forEach { field -> output.writeField(field) }
            trailingLong?.let(output::writeLong)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeField(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun decode(value: String, label: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("The $label is invalid.")
    }
}
