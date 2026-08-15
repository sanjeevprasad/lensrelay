package com.atanx.lensrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class PairingChallengeTest {
    @Test
    fun `challenge encoding is stable for desktop verification`() {
        val payload = PairingPayload(
            receiverId = "rid",
            receiverName = "Desk",
            publicKey = "key",
            nonce = "nonce",
            fingerprint = "fingerprint",
            expiresAt = 1_100,
            host = "192.168.1.20",
            port = 53_417,
        )

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(PairingChallenge.encode(payload, "Test phone"))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(
            "c3c6d3896826db822553a8004b286f14c8491333d8608b0acc8186cd7a7c44a1",
            digest,
        )
    }
}
