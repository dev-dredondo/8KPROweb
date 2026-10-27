package tv.own.owntv.features.login

import android.content.Context
import tv.own.owntv.brand.BrandConfig

/** The backup provider the panel assigned to this device (its own DNS, username and password). */
data class BackupAccount(val serverUrl: String, val username: String, val password: String)

/**
 * 8K PRO: keeps the backup provider between launches. The password is sealed with the same Keystore
 * key as the main account's. Written by [PanelClient] every time the panel answers, so a change on the
 * panel (or removing the backup) reaches the device the next time the app is opened.
 */
class BackupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("brand_backup", Context.MODE_PRIVATE)

    @Volatile
    private var cached: BackupAccount? = null
    @Volatile
    private var loaded = false

    fun current(): BackupAccount? {
        if (!loaded) {
            cached = runCatching {
                val url = prefs.getString(KEY_URL, null)
                val user = prefs.getString(KEY_USER, null)
                val sealed = prefs.getString(KEY_PASS, null)
                if (url.isNullOrBlank() || user.isNullOrBlank() || sealed.isNullOrBlank()) null
                else BackupAccount(url, user, KeystoreCipher.decrypt(sealed))
            }.getOrNull()
            loaded = true
            BrandConfig.backupServerUrl = cached?.serverUrl
        }
        return cached
    }

    fun save(account: BackupAccount?) {
        if (account == current()) return
        if (account == null) {
            prefs.edit().clear().apply()
        } else {
            prefs.edit()
                .putString(KEY_URL, account.serverUrl)
                .putString(KEY_USER, account.username)
                .putString(KEY_PASS, KeystoreCipher.encrypt(account.password))
                .apply()
        }
        cached = account
        loaded = true
        BrandConfig.backupServerUrl = account?.serverUrl
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
    }
}
