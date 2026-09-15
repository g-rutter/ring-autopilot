package com.ringautopilot.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only encrypted Ring refresh tokens; the AES key never leaves Android Keystore. */
class EncryptedTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun readRefreshToken(): String? {
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.DEFAULT)),
            )
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.DEFAULT)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun writeRefreshToken(token: String) {
        require(token.isNotBlank()) { "Refresh token must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ring_autopilot_refresh_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val PREFERENCES_NAME = "ring_autopilot_tokens"
        const val KEY_CIPHERTEXT = "refresh_token_ciphertext"
        const val KEY_IV = "refresh_token_iv"
    }
}
