package com.atanx.lensrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class ControlProtocolTest {
    @Test
    fun phoneChallengeMatchesDesktopEncoding() {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            ControlProtocol.phoneChallenge("rid", "pid", "nonce", 1_100),
        )
        assertEquals(
            "b21a5a90d556da636f53e6f7238078d8e9710e240057c7a0783339acc0d56a8d",
            digest.joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
    }
}
