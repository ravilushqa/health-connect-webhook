package com.ravil.healthconnect.nutrition.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Stores the X-Nutrition-Token in Android Keystore-backed EncryptedSharedPreferences.
 * Token is never logged, never placed in URLs or query params.
 * The encrypted prefs file is excluded from device backup (see backup_rules.xml).
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences? = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        null
    }

    fun getToken(): String? = prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setToken(token: String) {
        prefs?.edit()?.putString(KEY_TOKEN, token)?.apply()
    }

    fun clearToken() {
        prefs?.edit()?.remove(KEY_TOKEN)?.apply()
    }

    companion object {
        private const val PREFS_NAME = "nutrition_encrypted_prefs"
        private const val KEY_TOKEN = "nutrition_token"
    }
}
