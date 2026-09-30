package com.atanx.lensrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class DesktopAcksTest {
    @Test
    fun `pairing acknowledgement transcript matches desktop`() {
        assertEquals(
            "393b8247f001a9002b93a75891822cd865c8cd3279517e1499b218eaf108f76b",
            sha256Hex(
                DesktopAcks.transcript(
                    DesktopAcks.PAIRING_ACK_DOMAIN,
                    "rid",
                    "pid",
                    "nonce",
                    "tokhash",
                ),
            ),
        )
    }

    @Test
    fun `control acknowledgement transcript matches desktop`() {
        assertEquals(
            "0c0e468b7651ca556a48e15e85f166742523fbe41cf3c15b59a61adf7bbfe7ae",
            sha256Hex(
                DesktopAcks.transcript(
                    DesktopAcks.CONTROL_ACK_DOMAIN,
                    "rid",
                    "pid",
                    "nonce",
                    "tokhash",
                ),
            ),
        )
    }

    @Test
    fun `token hash is unpadded base64url sha of the token text`() {
        assertEquals(
            "JW0E205eSsMIdR7QiFtyK3WGMFZ8U6cSXtn70Gjlw_Y",
            DesktopAcks.tokenHash("header.payload.signature"),
        )
    }

    @Test
    fun `acknowledgement is accepted only from the paired desktop key`() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val desktop = generator.generateKeyPair()
        val impostor = generator.generateKeyPair()
        val transcript = DesktopAcks.transcript(
            DesktopAcks.CONTROL_ACK_DOMAIN,
            "rid",
            "pid",
            "nonce",
            DesktopAcks.tokenHash("header.payload.signature"),
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(desktop.private)
            update(transcript)
            sign()
        }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        DesktopAcks.verify(rawPublicKey(desktop), transcript, signature)

        assertThrows(IllegalStateException::class.java) {
            DesktopAcks.verify(rawPublicKey(impostor), transcript, signature)
        }
    }

    private fun rawPublicKey(keyPair: KeyPair): String {
        val encoded = keyPair.public.encoded
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(encoded.copyOfRange(X509_HEADER_LENGTH, encoded.size))
    }

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val X509_HEADER_LENGTH = 12
    }
}
