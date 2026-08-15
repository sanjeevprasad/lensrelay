package com.atanx.lensrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class UnpairProtocolTest {
    @Test
    fun `unpair challenge encoding matches desktop`() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(UnpairProtocol.phoneChallenge("rid", "pid", "nonce", 1_100))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(
            "e38c7a3779a795948d54838a3322986fdf17203645d4551bf7eb2ee5a30c720b",
            digest,
        )
    }
}
