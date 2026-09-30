package com.atanx.lensrelay

import org.json.JSONException
import org.json.JSONObject
import javax.net.ssl.SSLSocket

object PairingClient {
    private const val PROTOCOL_VERSION = 1
    private const val TIMEOUT_MILLIS = 5_000
    private const val MAX_RESPONSE_LENGTH = 16 * 1024

    fun pair(
        payload: PairingPayload,
        proof: PhonePairingProof,
        phoneName: String,
    ): String {
        val request = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("receiverId", payload.receiverId)
            .put("nonce", payload.nonce)
            .put("expiresAt", payload.expiresAt)
            .put("algorithm", proof.identity.algorithm)
            .put("phoneId", proof.identity.phoneId)
            .put("phoneName", phoneName)
            .put("publicKey", proof.identity.publicKey)
            .put("signature", proof.signature)
            .toString()

        val response = exchange(
            payload.host,
            payload.port,
            payload.mediaCertificateFingerprint,
            request,
        )
        check(response.optBoolean("ok")) {
            response.optString("message", "The desktop rejected pairing.")
        }
        check(response.optString("receiverId") == payload.receiverId) {
            "The pairing acknowledgement came from a different desktop."
        }
        check(response.optString("phoneId") == proof.identity.phoneId) {
            "The pairing acknowledgement is for a different phone."
        }
        check(response.optString("nonce") == payload.nonce) {
            "The pairing acknowledgement is stale."
        }
        return response.getString("mediaToken").also { token ->
            require(token.length in 32..8192 && token.count { it == '.' } == 2) {
                "The desktop returned an invalid media authorization."
            }
            DesktopAcks.verify(
                payload.publicKey,
                DesktopAcks.transcript(
                    DesktopAcks.PAIRING_ACK_DOMAIN,
                    payload.receiverId,
                    proof.identity.phoneId,
                    payload.nonce,
                    DesktopAcks.tokenHash(token),
                ),
                response.getString("signature"),
            )
        }
    }

    fun unpair(desktop: PairedDesktop, proof: PhoneUnpairProof) {
        val request = JSONObject()
            .put("type", "unpair")
            .put("version", PROTOCOL_VERSION)
            .put("receiverId", desktop.receiverId)
            .put("algorithm", proof.identity.algorithm)
            .put("phoneId", proof.identity.phoneId)
            .put("issuedAt", proof.issuedAt)
            .put("nonce", proof.nonce)
            .put("signature", proof.signature)
            .toString()
        val response = exchange(
            desktop.host,
            desktop.port,
            desktop.mediaCertificateFingerprint,
            request,
        )
        check(response.optBoolean("ok")) {
            response.optString("message", "The desktop rejected the unpair request.")
        }
        check(response.optString("receiverId") == desktop.receiverId) {
            "The unpair acknowledgement came from a different desktop."
        }
        check(response.optString("phoneId") == proof.identity.phoneId) {
            "The unpair acknowledgement is for a different phone."
        }
        check(response.optString("nonce") == proof.nonce) {
            "The unpair acknowledgement is stale."
        }
        UnpairProtocol.verifyDesktopAcknowledgement(
            desktop,
            proof.identity.phoneId,
            proof.nonce,
            response.getString("signature"),
        )
    }

    private fun exchange(
        host: String,
        port: Int,
        certificateFingerprint: String,
        request: String,
    ): JSONObject {
        createSocket(host, port, certificateFingerprint).use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            socket.startHandshake()
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writer.write(request)
            writer.newLine()
            writer.flush()

            val responseLine = socket.getInputStream()
                .bufferedReader(Charsets.UTF_8)
                .readLine()
                ?: throw IllegalStateException("The desktop closed the pairing connection.")
            require(responseLine.length <= MAX_RESPONSE_LENGTH) {
                "The desktop sent an invalid pairing response."
            }
            return try {
                JSONObject(responseLine)
            } catch (_: JSONException) {
                throw IllegalStateException("The desktop sent an invalid pairing response.")
            }
        }
    }

    private fun createSocket(host: String, port: Int, fingerprint: String): SSLSocket =
        TlsPinning.createSocket(host, port, fingerprint, TIMEOUT_MILLIS)
}
