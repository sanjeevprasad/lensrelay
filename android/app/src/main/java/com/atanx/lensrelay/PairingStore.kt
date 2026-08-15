package com.atanx.lensrelay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedDesktop(
    val receiverId: String,
    val receiverName: String,
    val publicKey: String,
    val fingerprint: String,
    val host: String,
    val port: Int,
    val controlPort: Int,
    val mediaCertificateFingerprint: String,
    val mediaToken: String,
    val pairedAt: Long,
    val preferredCamera: CameraLens,
    val allowRemoteStart: Boolean,
)

class PairingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<PairedDesktop> {
        val encrypted = preferences.getString(PAIRINGS_KEY, null) ?: return emptyList()
        val plainText = decrypt(encrypted)
        val array = JSONArray(plainText)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    PairedDesktop(
                        receiverId = item.getString("receiverId"),
                        receiverName = item.getString("receiverName"),
                        publicKey = item.getString("publicKey"),
                        fingerprint = item.getString("fingerprint"),
                        host = item.optString("host"),
                        port = item.optInt("port"),
                        controlPort = item.optInt("controlPort", DEFAULT_CONTROL_PORT),
                        mediaCertificateFingerprint = item.optString("mediaCertificateFingerprint"),
                        mediaToken = item.optString("mediaToken"),
                        pairedAt = item.getLong("pairedAt"),
                        preferredCamera = runCatching {
                            CameraLens.valueOf(item.optString("preferredCamera"))
                        }.getOrDefault(CameraLens.Back),
                        allowRemoteStart = item.optBoolean("allowRemoteStart", false),
                    ),
                )
            }
        }
    }

    fun save(payload: PairingPayload): PairedDesktop {
        val pairings = load()
        val pairedDesktop = PairedDesktop(
            receiverId = payload.receiverId,
            receiverName = payload.receiverName,
            publicKey = payload.publicKey,
            fingerprint = payload.fingerprint,
            host = payload.host,
            port = payload.port,
            controlPort = payload.controlPort,
            mediaCertificateFingerprint = payload.mediaCertificateFingerprint,
            mediaToken = payload.mediaToken,
            pairedAt = System.currentTimeMillis(),
            preferredCamera = pairings
                .firstOrNull { it.receiverId == payload.receiverId }
                ?.preferredCamera
                ?: CameraLens.Back,
            allowRemoteStart = pairings
                .firstOrNull { it.receiverId == payload.receiverId }
                ?.allowRemoteStart
                ?: false,
        )
        write(pairings.filterNot { it.receiverId == pairedDesktop.receiverId } + pairedDesktop)
        return pairedDesktop
    }

    fun forget(receiverId: String) {
        val remaining = load().filterNot { it.receiverId == receiverId }
        write(remaining)
    }

    fun setPreferredCamera(receiverId: String, camera: CameraLens): PairedDesktop? {
        var updated: PairedDesktop? = null
        val pairings = load().map { desktop ->
            if (desktop.receiverId == receiverId) {
                desktop.copy(preferredCamera = camera).also { updated = it }
            } else {
                desktop
            }
        }
        if (updated != null) write(pairings)
        return updated
    }

    fun setAllowRemoteStart(receiverId: String, allowed: Boolean): PairedDesktop? =
        update(receiverId) { it.copy(allowRemoteStart = allowed) }

    fun setMediaToken(receiverId: String, token: String): PairedDesktop? =
        update(receiverId) { it.copy(mediaToken = token) }

    private fun update(receiverId: String, transform: (PairedDesktop) -> PairedDesktop): PairedDesktop? {
        var updated: PairedDesktop? = null
        val pairings = load().map { desktop ->
            if (desktop.receiverId == receiverId) transform(desktop).also { updated = it } else desktop
        }
        if (updated != null) write(pairings)
        return updated
    }

    private fun write(pairings: List<PairedDesktop>) {
        if (pairings.isEmpty()) {
            preferences.edit().remove(PAIRINGS_KEY).apply()
            return
        }
        val array = JSONArray()
        pairings.forEach { desktop -> array.put(desktop.toJson()) }
        preferences.edit().putString(PAIRINGS_KEY, encrypt(array.toString())).apply()
    }

    private fun PairedDesktop.toJson(): JSONObject = JSONObject()
        .put("receiverId", receiverId)
        .put("receiverName", receiverName)
        .put("publicKey", publicKey)
        .put("fingerprint", fingerprint)
        .put("host", host)
        .put("port", port)
        .put("controlPort", controlPort)
        .put("mediaCertificateFingerprint", mediaCertificateFingerprint)
        .put("mediaToken", mediaToken)
        .put("pairedAt", pairedAt)
        .put("preferredCamera", preferredCamera.name)
        .put("allowRemoteStart", allowRemoteStart)

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = JSONObject()
            .put("iv", encode(cipher.iv))
            .put("ciphertext", encode(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))))
        return payload.toString()
    }

    private fun decrypt(payload: String): String {
        val json = JSONObject(payload)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, decode(json.getString("iv"))),
        )
        return cipher.doFinal(decode(json.getString("ciphertext"))).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "lensrelay_pairings_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFERENCES_NAME = "lensrelay_secure_pairings"
        private const val PAIRINGS_KEY = "paired_desktops"
        private const val DEFAULT_CONTROL_PORT = 53_419
    }
}
