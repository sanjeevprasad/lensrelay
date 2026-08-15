package com.atanx.lensrelay

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.SocketTimeoutException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class ControlClient(
    private val desktop: PairedDesktop,
    private val identity: PhoneIdentity,
    private val onConnected: (Boolean) -> Unit,
    private val onCommand: (String, JSONObject, Responder) -> Unit,
) {
    fun interface Responder {
        fun respond(ok: Boolean, payload: JSONObject?, error: String?)
    }

    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var capabilities = JSONObject()
    @Volatile private var state = JSONObject()
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::connectionLoop, "lensrelay-control-client").also { it.start() }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        synchronized(writeLock) {
            runCatching { writer?.close() }
            writer = null
        }
        worker = null
        onConnected(false)
    }

    fun updateCapabilities(value: JSONObject) {
        capabilities = value
        send(JSONObject().put("type", "capabilities").put("payload", value))
    }

    fun updateState(value: JSONObject) {
        state = value
        send(JSONObject().put("type", "state").put("payload", value))
    }

    private fun connectionLoop() {
        while (running.get()) {
            runCatching { connectAndRead() }
                .onFailure { error ->
                    if (running.get()) Log.w(TAG, "Control connection lost", error)
                }
            synchronized(writeLock) { writer = null }
            onConnected(false)
            if (running.get()) runCatching { Thread.sleep(RECONNECT_DELAY_MS) }
        }
    }

    private fun connectAndRead() {
        val socket = createSocket()
        socket.use { activeSocket ->
            activeSocket.soTimeout = READ_TICK_MS
            activeSocket.startHandshake()
            val input = BufferedReader(InputStreamReader(activeSocket.inputStream, Charsets.UTF_8))
            val output = BufferedWriter(OutputStreamWriter(activeSocket.outputStream, Charsets.UTF_8))
            synchronized(writeLock) { writer = output }

            val proof = identity.createControlProof(desktop.receiverId)
            write(
                output,
                JSONObject()
                    .put("type", "hello")
                    .put("version", 1)
                    .put("receiverId", desktop.receiverId)
                    .put("algorithm", proof.identity.algorithm)
                    .put("phoneId", proof.identity.phoneId)
                    .put("issuedAt", proof.issuedAt)
                    .put("nonce", proof.nonce)
                    .put("signature", proof.signature),
            )
            val acknowledgement = JSONObject(input.readLine() ?: error("Desktop closed during authentication"))
            check(acknowledgement.optString("type") == "helloAck") { "Desktop rejected control authentication" }
            check(acknowledgement.optString("receiverId") == desktop.receiverId) { "Desktop identity changed" }

            onConnected(true)
            write(output, JSONObject().put("type", "capabilities").put("payload", capabilities))
            write(output, JSONObject().put("type", "state").put("payload", state))
            var lastHeartbeat = 0L
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    write(output, JSONObject().put("type", "heartbeat"))
                    lastHeartbeat = now
                }
                try {
                    val line = input.readLine() ?: return
                    handleMessage(JSONObject(line))
                } catch (_: SocketTimeoutException) {
                    // Wake periodically for heartbeats and stop requests.
                }
            }
        }
    }

    private fun handleMessage(message: JSONObject) {
        if (message.optString("type") != "command") return
        val id = message.optString("id")
        val command = message.optString("command")
        val parameters = message.optJSONObject("parameters") ?: JSONObject()
        if (id.isEmpty() || command.isEmpty()) return
        val responded = AtomicBoolean(false)
        onCommand(command, parameters) { ok, payload, error ->
            if (!responded.compareAndSet(false, true)) return@onCommand
            val response = JSONObject().put("type", "response").put("id", id).put("ok", ok)
            if (ok) response.put("payload", payload ?: JSONObject())
            else response.put("error", error ?: "Command rejected")
            send(response)
        }
    }

    private fun createSocket(): SSLSocket {
        val expectedFingerprint = desktop.mediaCertificateFingerprint.lowercase()
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "Desktop did not present a TLS certificate" }
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                require(actual == expectedFingerprint) { "Desktop TLS certificate does not match pairing" }
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        val transport = Socket().apply {
            connect(InetSocketAddress(desktop.host, desktop.controlPort), CONNECT_TIMEOUT_MS)
        }
        return context.socketFactory.createSocket(
            transport,
            desktop.host,
            desktop.controlPort,
            true,
        ) as SSLSocket
    }

    private fun send(message: JSONObject) {
        val activeWriter = writer ?: return
        runCatching { write(activeWriter, message) }
            .onFailure { Log.w(TAG, "Could not send control message", it) }
    }

    private fun write(output: BufferedWriter, message: JSONObject) = synchronized(writeLock) {
        output.write(message.toString())
        output.newLine()
        output.flush()
    }

    companion object {
        private const val TAG = "LensRelayControl"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val HEARTBEAT_MS = 3_000L
        private const val READ_TICK_MS = 1_000
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
