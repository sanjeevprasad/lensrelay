package com.atanx.lensrelay

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

object PairingClient {
    private const val PROTOCOL_VERSION = 1
    private const val TIMEOUT_MILLIS = 5_000
    private const val MAX_RESPONSE_LENGTH = 16 * 1024

    fun pair(
        payload: PairingPayload,
        proof: PhonePairingProof,
        phoneName: String,
    ) {
        val request = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("receiverId", payload.receiverId)
            .put("nonce", payload.nonce)
            .put("expiresAt", payload.expiresAt)
            .put("algorithm", proof.identity.algorithm)
            .put("phoneId", proof.identity.phoneId)
            .put("phoneName", phoneName.take(80))
            .put("publicKey", proof.identity.publicKey)
            .put("signature", proof.signature)
            .toString()

        val response = exchange(payload.host, payload.port, request)
        check(response.optBoolean("ok")) {
            response.optString("message", "The desktop rejected pairing.")
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
        val response = exchange(desktop.host, desktop.port, request)
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

    private fun exchange(host: String, port: Int, request: String): JSONObject {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MILLIS)
            socket.soTimeout = TIMEOUT_MILLIS
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
            return JSONObject(responseLine)
        }
    }
}
