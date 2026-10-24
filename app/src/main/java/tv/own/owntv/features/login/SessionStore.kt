package tv.own.owntv.features.login

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The signed-in Xtream account. [serverUrl] is the server it belongs to (the panel can change it);
 * [viaPanel] marks an account handed over by the activation panel, which the app re-checks at launch.
 */
data class Session(
    val username: String,
    val password: String,
    val serverUrl: String = tv.own.owntv.brand.BrandConfig.serverUrl,
    val viaPanel: Boolean = false,
)

/** This install's identity on the activation panel: a random id and a secret only this device knows. */
data class DeviceIdentity(val deviceId: String, val token: String)

/** What MainActivity routes on: still reading, nobody signed in, or signed in. */
sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val session: Session) : SessionState
}

private val Context.sessionDataStore by preferencesDataStore(name = "brand_session")

/**
 * 8K PRO: keeps the login between launches.
 *
 * Stored in a Preferences DataStore. The username is plain text; the password is encrypted with
 * AES-256-GCM under a key that lives in the Android Keystore (hardware-backed where the device has it)
 * and never leaves it, so the DataStore file on its own does not reveal the password.
 *
 * If the key is gone (app data restored onto another device, Keystore reset) the password cannot be
 * decrypted: the session then reads as logged out and the user simply signs in again.
 */
class SessionStore(context: Context) {
    private val store = context.applicationContext.sessionDataStore

    val state: Flow<SessionState> = store.data.map { prefs ->
        val user = prefs[KEY_USERNAME]
        val sealed = prefs[KEY_PASSWORD]
        if (user.isNullOrEmpty() || sealed.isNullOrEmpty()) return@map SessionState.LoggedOut
        val password = runCatching { KeystoreCipher.decrypt(sealed) }
            .onFailure { Log.w(TAG, "stored password unreadable, signing out", it) }
            .getOrNull()
            ?: return@map SessionState.LoggedOut
        val server = prefs[KEY_SERVER]?.takeIf { it.isNotBlank() } ?: tv.own.owntv.brand.BrandConfig.serverUrl
        tv.own.owntv.brand.BrandConfig.sessionServerUrl = server
        SessionState.LoggedIn(Session(user, password, server, prefs[KEY_VIA_PANEL] == "1"))
    }

    suspend fun current(): Session? = (state.first() as? SessionState.LoggedIn)?.session

    suspend fun save(session: Session) {
        val sealed = KeystoreCipher.encrypt(session.password)
        store.edit {
            it[KEY_USERNAME] = session.username
            it[KEY_PASSWORD] = sealed
            it[KEY_SERVER] = session.serverUrl
            it[KEY_VIA_PANEL] = if (session.viaPanel) "1" else "0"
        }
        tv.own.owntv.brand.BrandConfig.sessionServerUrl = session.serverUrl
    }

    /**
     * Signs out. The device identity is kept, so the TV keeps the same activation code, and so is the
     * last server: the next sign-in still finds the playlists that pointed at it.
     */
    suspend fun clear() {
        store.edit {
            it.remove(KEY_USERNAME); it.remove(KEY_PASSWORD); it.remove(KEY_VIA_PANEL)
        }
    }

    /** The server of the last account, signed in or not. */
    suspend fun lastServer(): String? = store.data.first()[KEY_SERVER]?.takeIf { it.isNotBlank() }

    /** The panel identity, created on first use. */
    suspend fun identity(): DeviceIdentity {
        val prefs = store.data.first()
        val id = prefs[KEY_DEVICE_ID]
        val token = prefs[KEY_DEVICE_TOKEN]?.let { runCatching { KeystoreCipher.decrypt(it) }.getOrNull() }
        if (!id.isNullOrEmpty() && !token.isNullOrEmpty()) return DeviceIdentity(id, token)
        return resetIdentity()
    }

    /** A brand-new identity (the panel forgot this device): a fresh id, token and, from it, code. */
    suspend fun resetIdentity(): DeviceIdentity {
        val rnd = java.security.SecureRandom()
        fun randomB64(bytes: Int) = ByteArray(bytes).also(rnd::nextBytes)
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
        val identity = DeviceIdentity(deviceId = randomB64(18), token = randomB64(32))
        val sealed = KeystoreCipher.encrypt(identity.token)
        store.edit {
            it[KEY_DEVICE_ID] = identity.deviceId
            it[KEY_DEVICE_TOKEN] = sealed
        }
        return identity
    }

    private companion object {
        const val TAG = "SessionStore"
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password_enc")
        val KEY_SERVER = stringPreferencesKey("server_url")
        val KEY_VIA_PANEL = stringPreferencesKey("via_panel")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token_enc")
    }
}

/** AES/GCM with a non-exportable Android Keystore key. Output: Base64(iv ‖ ciphertext+tag). */
private object KeystoreCipher {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "brand_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }
}
