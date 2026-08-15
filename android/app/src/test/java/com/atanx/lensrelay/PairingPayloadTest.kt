package com.atanx.lensrelay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PairingPayloadTest {
    @Test
    fun `valid desktop identity is accepted`() {
        val payload = PairingPayload.parse(payload(expiresAt = 1_100), nowSeconds = 1_000)

        assertEquals("Desk", payload.receiverName)
        assertEquals(receiverId(), payload.receiverId)
        assertEquals("192.168.1.20", payload.host)
        assertEquals(53_417, payload.port)
    }

    @Test
    fun `expired pairing code is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse(payload(expiresAt = 999), nowSeconds = 1_000)
        }
    }

    @Test
    fun `receiver id must match public key`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse(
                payload(expiresAt = 1_100, receiverId = "forged"),
                nowSeconds = 1_000,
            )
        }
    }

    private fun payload(expiresAt: Long, receiverId: String = receiverId()): String {
        val json = JSONObject()
            .put("version", 1)
            .put("receiverId", receiverId)
            .put("receiverName", "Desk")
            .put("publicKey", encode(PUBLIC_KEY))
            .put("nonce", encode(ByteArray(24) { 9 }))
            .put("expiresAt", expiresAt)
            .put("host", "192.168.1.20")
            .put("port", 53_417)
            .toString()
        return "lensrelay:pair:${encode(json.toByteArray())}"
    }

    private fun receiverId(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(PUBLIC_KEY)
        return encode(digest.copyOfRange(0, 16))
    }

    private fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    companion object {
        private val PUBLIC_KEY = ByteArray(32) { it.toByte() }
    }
}
