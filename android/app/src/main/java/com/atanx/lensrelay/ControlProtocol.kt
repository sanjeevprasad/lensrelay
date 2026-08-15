package com.atanx.lensrelay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class PhoneControlProof(
    val identity: PhonePublicIdentity,
    val issuedAt: Long,
    val nonce: String,
    val signature: String,
)

object ControlProtocol {
    private const val DOMAIN = "lensrelay-phone-control-v1"

    fun phoneChallenge(
        receiverId: String,
        phoneId: String,
        nonce: String,
        issuedAt: Long,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            listOf(DOMAIN, receiverId, phoneId, nonce).forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                data.writeInt(encoded.size)
                data.write(encoded)
            }
            data.writeLong(issuedAt)
        }
        output.toByteArray()
    }
}
