package com.atanx.lensrelay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object PairingChallenge {
    private const val DOMAIN = "lensrelay-phone-pairing-v1"

    fun encode(payload: PairingPayload, phoneName: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeField(DOMAIN)
            output.writeField(payload.receiverId)
            output.writeField(payload.publicKey)
            output.writeField(payload.nonce)
            output.writeLong(payload.expiresAt)
            output.writeField(phoneName)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeField(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }
}
