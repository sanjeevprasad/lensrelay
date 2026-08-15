package com.atanx.lensrelay

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

data class PairingPayload(
    val receiverId: String,
    val receiverName: String,
    val publicKey: String,
    val nonce: String,
    val fingerprint: String,
    val expiresAt: Long,
    val host: String,
    val port: Int,
    val controlPort: Int,
    val mediaCertificateFingerprint: String,
) {
    companion object {
        private const val PREFIX = "lensrelay:pair:"
        private const val VERSION = 1
        private const val MAX_CLOCK_WINDOW_SECONDS = 5 * 60

        fun parse(
            raw: String,
            nowSeconds: Long = System.currentTimeMillis() / 1_000,
        ): PairingPayload {
            require(raw.startsWith(PREFIX)) { "This is not a LensRelay pairing code." }
            val encoded = raw.removePrefix(PREFIX)
            val jsonBytes = decodeUrlSafe(encoded, "pairing payload")
            val json = JSONObject(jsonBytes.toString(Charsets.UTF_8))

            require(json.getInt("version") == VERSION) {
                "This pairing code uses an unsupported LensRelay version."
            }

            val receiverId = json.getString("receiverId")
            val receiverName = json.getString("receiverName").trim()
            val publicKey = json.getString("publicKey")
            val nonce = json.getString("nonce")
            val expiresAt = json.getLong("expiresAt")
            val host = json.getString("host").trim()
            val port = json.getInt("port")
            val controlPort = json.optInt("controlPort", DEFAULT_CONTROL_PORT)
            val mediaCertificateFingerprint = json.getString("mediaCertificateFingerprint")
            val publicKeyBytes = decodeUrlSafe(publicKey, "desktop public key")
            val nonceBytes = decodeUrlSafe(nonce, "pairing nonce")

            require(receiverName.isNotEmpty() && receiverName.length <= 80) {
                "The desktop name in this pairing code is invalid."
            }
            require(publicKeyBytes.size == 32) { "The desktop identity is invalid." }
            require(nonceBytes.size >= 16) { "The pairing nonce is invalid." }
            require(expiresAt >= nowSeconds) { "This pairing code has expired." }
            require(expiresAt <= nowSeconds + MAX_CLOCK_WINDOW_SECONDS) {
                "This pairing code has an invalid expiration time."
            }
            require(host.isNotEmpty() && host.length <= 255 && host.none(Char::isWhitespace)) {
                "The desktop network address is invalid."
            }
            require(port in 1..65535) { "The desktop network port is invalid." }
            require(controlPort in 1..65535) { "The desktop control port is invalid." }
            require(mediaCertificateFingerprint.matches(Regex("[0-9a-fA-F]{64}"))) {
                "The desktop media certificate fingerprint is invalid."
            }

            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            val expectedReceiverId = encodeUrlSafe(digest.copyOfRange(0, 16))
            require(receiverId == expectedReceiverId) { "The desktop identity does not match." }

            return PairingPayload(
                receiverId = receiverId,
                receiverName = receiverName,
                publicKey = publicKey,
                nonce = nonce,
                fingerprint = digest.toFingerprint(),
                expiresAt = expiresAt,
                host = host,
                port = port,
                controlPort = controlPort,
                mediaCertificateFingerprint = mediaCertificateFingerprint.lowercase(),
            )
        }

        private fun decodeUrlSafe(value: String, label: String): ByteArray = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The $label is not valid base64.")
        }

        private fun encodeUrlSafe(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private fun ByteArray.toFingerprint(): String = asList()
            .chunked(4)
            .joinToString(" ") { chunk ->
                chunk.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }

        private const val DEFAULT_CONTROL_PORT = 53_419
    }
}
