package com.example.surakshanetra

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Encrypted local token storage for the authenticated backend integration. */
class SecureTokenStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "surakshanetra_auth",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun readAccessToken(): String? = preferences.getString(KEY_ACCESS, null)
    fun saveAccessToken(accessToken: String) = preferences.edit().putString(KEY_ACCESS, accessToken).apply()
    fun readAccessTokenDraft(): String? = preferences.getString(KEY_ACCESS_DRAFT, null)
    fun saveAccessTokenDraft(accessToken: String) = preferences.edit().putString(KEY_ACCESS_DRAFT, accessToken).apply()
    fun clearAccessTokenDraft() = preferences.edit().remove(KEY_ACCESS_DRAFT).apply()
    fun saveTokens(accessToken: String, refreshToken: String) = preferences.edit()
        .putString(KEY_ACCESS, accessToken).putString(KEY_REFRESH, refreshToken).apply()
    fun readRefreshToken(): String? = preferences.getString(KEY_REFRESH, null)
    /** A persistent install identifier; it never uses a hardware serial number. */
    fun deviceId(): String {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }
    fun saveFcmToken(token: String) = preferences.edit().putString(KEY_FCM_TOKEN, token).apply()
    fun readFcmToken(): String? = preferences.getString(KEY_FCM_TOKEN, null)
    fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_ACCESS_DRAFT = "access_token_draft"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_FCM_TOKEN = "fcm_token"
    }
}
