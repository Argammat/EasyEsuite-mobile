package com.easyesuite.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.easyesuite.core.auth.Session
import com.easyesuite.core.auth.TokenStore
import com.easyesuite.core.net.DefaultJson

/** Tokens live in EncryptedSharedPreferences (AES-256, keys in the Android Keystore). */
class SecureTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "easyesuite.session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Volatile private var cached: Session? = null

    override fun load(): Session? {
        cached?.let { return it }
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { DefaultJson.decodeFromString(Session.serializer(), raw) }.getOrNull().also { cached = it }
    }

    override fun save(session: Session) {
        cached = session
        prefs.edit().putString(KEY, DefaultJson.encodeToString(Session.serializer(), session)).apply()
    }

    override fun clear() {
        cached = null
        prefs.edit().remove(KEY).apply()
    }

    /** The last tenant used, so the login screen can prefill it after sign-out. */
    var lastTenant: String?
        get() = prefs.getString(KEY_TENANT, null)
        set(value) { prefs.edit().putString(KEY_TENANT, value).apply() }

    var lastEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) { prefs.edit().putString(KEY_EMAIL, value).apply() }

    private companion object {
        const val KEY = "session"
        const val KEY_TENANT = "last_tenant"
        const val KEY_EMAIL = "last_email"
    }
}
