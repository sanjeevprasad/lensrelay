package com.atanx.lensrelay

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object TlsPinning {
    fun createSocket(
        host: String,
        port: Int,
        certificateFingerprintLowerHex: String,
        connectTimeoutMillis: Int,
    ): SSLSocket {
        val expectedFingerprint = certificateFingerprintLowerHex.lowercase()
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "Desktop did not present a TLS certificate" }
                chain[0].checkValidity()
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                require(actual == expectedFingerprint) {
                    "Desktop TLS certificate does not match pairing"
                }
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        val transport = Socket().apply {
            connect(InetSocketAddress(host, port), connectTimeoutMillis)
        }
        return context.socketFactory.createSocket(transport, host, port, true) as SSLSocket
    }
}
