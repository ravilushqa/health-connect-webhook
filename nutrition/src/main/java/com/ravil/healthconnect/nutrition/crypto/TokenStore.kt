package com.ravil.healthconnect.nutrition.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stores the X-Nutrition-Token in Android Keystore-backed EncryptedSharedPreferences.
 * Token is never logged, never placed in URLs or query params.
 * The encrypted prefs file is excluded from device backup (see backup_rules.xml).
 *
 * Durability model
 * ----------------
 * Every mutation writes via editor.clear() + put/remove + commit() (never apply()).
 * The confirmed token is held in a shared registry keyed by [logicalKey] so that two
 * TokenStore wrappers constructed with the same logical key share state — necessary
 * because EncryptedSharedPreferences.create() may return a new wrapper object each call.
 *
 * The production constructor passes [PREFS_NAME] as the logical key so all wrappers over
 * the same encrypted file share state. Wrappers constructed with distinct logical keys
 * (e.g. injected fakes in tests) are fully isolated.
 *
 * On commit failure the registry is unchanged (reads still return the last confirmed
 * value) and a restore commit is issued so the phantom is expelled from SharedPreferences
 * in-process memory before any other writer can flush it to disk.
 */
class TokenStore internal constructor(
    private val prefs: SharedPreferences?,
    private val logicalKey: String = keyForInstance(prefs),
) : TokenStorePort {

    constructor(context: Context) : this(createEncryptedPrefs(context), PREFS_NAME)

    init {
        synchronized(registryLock) {
            // Only initialise from prefs if no prior wrapper has established registry state.
            // This preserves a token written by an earlier wrapper in the same process.
            // Null prefs (inaccessible encrypted file) initialise to null — fail-closed.
            if (!registry.containsKey(logicalKey)) {
                registry[logicalKey] = prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            }
        }
    }

    override fun getToken(): String? = synchronized(registryLock) { registry[logicalKey] }

    @Suppress("CommitPrefEdits")
    override fun setToken(token: String): Boolean {
        return synchronized(registryLock) {
            val old = registry[logicalKey]
            val ok = prefs?.edit()?.clear()?.putString(KEY_TOKEN, token)?.commit() == true
            if (ok) {
                registry[logicalKey] = token.takeIf { it.isNotBlank() }
            } else {
                // Restore memory; even if this restore commit's disk write fails,
                // Android's commit() always writes to memory first.
                restoreMemory(old)
            }
            ok
        }
    }

    @Suppress("CommitPrefEdits")
    override fun clearToken(): Boolean {
        return synchronized(registryLock) {
            val old = registry[logicalKey]
            val ok = prefs?.edit()?.clear()?.commit() == true
            if (ok) {
                registry[logicalKey] = null
            } else {
                restoreMemory(old)
            }
            ok
        }
    }

    private fun restoreMemory(old: String?) {
        if (old != null) {
            prefs?.edit()?.clear()?.putString(KEY_TOKEN, old)?.commit()
        } else {
            prefs?.edit()?.clear()?.commit()
        }
    }

    companion object {
        internal const val PREFS_NAME = "nutrition_encrypted_prefs"
        private const val KEY_TOKEN = "nutrition_token"

        private val registryLock = Any()

        // Keyed by logicalKey: two wrappers with the same logicalKey share state.
        // Uses explicit containsKey so a stored null (cleared token) is distinguishable
        // from an absent entry (not yet initialised this process).
        internal val registry = HashMap<String, String?>()

        // Maps each SharedPreferences instance to a stable unique key by reference identity (===),
        // not by hashCode — prevents collisions when two instances share the same identity hash.
        private val instanceKeyMap = IdentityHashMap<SharedPreferences, String>()
        private val instanceKeyCounter = AtomicInteger(0)

        internal fun keyForInstance(prefs: SharedPreferences?): String =
            if (prefs == null) "null#${instanceKeyCounter.getAndIncrement()}"
            else synchronized(registryLock) {
                instanceKeyMap.getOrPut(prefs) { "instance#${instanceKeyCounter.getAndIncrement()}" }
            }

        /** Clears all registry state. Call in test setUp to ensure isolation. */
        internal fun resetForTesting() = synchronized(registryLock) {
            registry.clear()
            instanceKeyMap.clear()
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences? = try {
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
    }
}
