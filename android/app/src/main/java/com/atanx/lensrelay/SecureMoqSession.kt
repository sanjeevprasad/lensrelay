package com.atanx.lensrelay

import com.swmansion.moqkit.publish.Publisher
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer
import uniffi.moq.MoqSession

class SecureMoqSession(
    private val url: String,
    private val certificateFingerprint: String,
) {
    private val publishOrigin = MoqOriginProducer()
    private val consumeOrigin = MoqOriginProducer()
    private val client = MoqClient()
    private var session: MoqSession? = null

    suspend fun connect() {
        check(session == null) { "Media session is already connected." }
        client.setTlsSystemRoots(false)
        client.setTlsFingerprints(listOf(certificateFingerprint))
        client.setConsume(consumeOrigin)
        client.setPublish(publishOrigin)
        session = client.connect(url)
    }

    fun publish(path: String, publisher: Publisher) {
        check(session != null) { "Media session must connect before publishing." }
        publishOrigin.publish(path, MoqPublisherBridge.broadcast(publisher))
    }

    fun close() {
        session?.shutdown()
        session = null
        client.cancel()
        client.close()
        publishOrigin.close()
        consumeOrigin.close()
    }
}
