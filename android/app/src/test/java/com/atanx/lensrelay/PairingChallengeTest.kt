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
            controlPort = 53_419,
            mediaCertificateFingerprint = "ab".repeat(32),
        )

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(PairingChallenge.encode(payload, "Test phone"))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(
            "4761d4b9a22753187e0f39fcf10b3f3dec4e3e70762d493d39b59ce218479b5c",
            digest,
        )
    }
}
