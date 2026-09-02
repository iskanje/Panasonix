package com.hz35remote.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hz35remote.app.data.ComfortCloudSession
import com.hz35remote.app.data.PendingAuthorization
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun saveSession(session: ComfortCloudSession) {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresAtEpochSeconds", session.expiresAtEpochSeconds)
            .put("scope", session.scope)
            .put("clientId", session.clientId)
            .put("appVersion", session.appVersion)
        putEncrypted(SESSION_KEY, json.toString())
    }

    fun loadSession(): ComfortCloudSession? = getEncrypted(SESSION_KEY)?.let { encoded ->
        runCatching {
            val json = JSONObject(encoded)
            ComfortCloudSession(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                expiresAtEpochSeconds = json.getLong("expiresAtEpochSeconds"),
                scope = json.optString("scope"),
                clientId = json.getString("clientId"),
                appVersion = json.getString("appVersion"),
            )
        }.getOrNull()
    }

    fun savePendingAuthorization(pending: PendingAuthorization) {
        val json = JSONObject()
            .put("codeVerifier", pending.codeVerifier)
            .put("state", pending.state)
        putEncrypted(PENDING_AUTHORIZATION_KEY, json.toString())
    }

    fun consumePendingAuthorization(): PendingAuthorization? {
        val pending = getEncrypted(PENDING_AUTHORIZATION_KEY)?.let { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                PendingAuthorization(
                    codeVerifier = json.getString("codeVerifier"),
                    state = json.getString("state"),
                )
            }.getOrNull()
        }
        preferences.edit().remove(PENDING_AUTHORIZATION_KEY).apply()
        return pending
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun putEncrypted(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(key, payload).apply()
    }

    private fun getEncrypted(key: String): String? {
        val payload = preferences.getString(key, null) ?: return null
        return runCatching {
            val parts = payload.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_comfort_cloud_session"
        const val SESSION_KEY = "session"
        const val PENDING_AUTHORIZATION_KEY = "pending_authorization"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "comfort_cloud_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

