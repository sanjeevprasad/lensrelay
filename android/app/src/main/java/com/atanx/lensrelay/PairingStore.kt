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
    val pairedAt: Long,
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
                        pairedAt = item.getLong("pairedAt"),
                    ),
                )
            }
        }
    }

    fun save(payload: PairingPayload): PairedDesktop {
        val pairedDesktop = PairedDesktop(
            receiverId = payload.receiverId,
            receiverName = payload.receiverName,
            publicKey = payload.publicKey,
            fingerprint = payload.fingerprint,
            pairedAt = System.currentTimeMillis(),
        )
        val pairings = load()
            .filterNot { it.receiverId == pairedDesktop.receiverId }
            .plus(pairedDesktop)
        val array = JSONArray()
        pairings.forEach { desktop ->
            array.put(
                JSONObject()
                    .put("receiverId", desktop.receiverId)
                    .put("receiverName", desktop.receiverName)
                    .put("publicKey", desktop.publicKey)
                    .put("fingerprint", desktop.fingerprint)
                    .put("pairedAt", desktop.pairedAt),
            )
        }
        preferences.edit().putString(PAIRINGS_KEY, encrypt(array.toString())).apply()
        return pairedDesktop
    }

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
    }
}
