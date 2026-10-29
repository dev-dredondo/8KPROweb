package tv.own.owntv.features.login

import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import tv.own.owntv.brand.BrandConfig
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** 8K PRO: an M3U playlist the panel assigned to this device ([id] is the panel's, stable across edits). */
data class BrandPlaylist(val id: Long, val name: String, val url: String, val epgUrl: String)

/** What the activation panel says about this device. */
sealed interface PanelStatus {
    /** Waiting for activation; [code] is what the TV shows. */
    data class Pending(val code: String) : PanelStatus
    data class Active(
        val code: String,
        val serverUrl: String,
        val username: String,
        val password: String,
        /**
         * 8K PRO: the M3U playlists the panel gives this device, as providers next to the Xtream account
         * (or on their own). With no Xtream account the first one is the device's account.
         */
        val playlists: List<BrandPlaylist> = emptyList(),
    ) : PanelStatus {
        /** No Xtream account: the device signs in with its first M3U playlist. */
        val isM3u: Boolean get() = (serverUrl.isBlank() || username.isBlank()) && playlists.isNotEmpty()
        val m3uUrl: String get() = if (isM3u) playlists.first().url else ""
        val epgUrl: String get() = if (isM3u) playlists.first().epgUrl else ""

        /** The playlists added next to the account (all of them, or all but the first when that is the account). */
        val extraPlaylists: List<BrandPlaylist> get() = if (isM3u) playlists.drop(1) else playlists

        /** The session this answer signs the device into. */
        fun toSession(): Session =
            if (isM3u) Session.forM3u(code, m3uUrl, epgUrl) else Session(username, password, serverUrl, viaPanel = true)
    }
    data class Disabled(val code: String) : PanelStatus
    data class Expired(val code: String) : PanelStatus
    /** The panel does not know this device (deleted from the panel): register again for a new code. */
    data object Unknown : PanelStatus
    /** No answer (network, panel down). Never a reason to sign anyone out. */
    data object Unreachable : PanelStatus
}

/**
 * 8K PRO: client for the activation panel (panel/app/panel.py).
 *
 * The panel serves HTTPS with a certificate it generated for its own IP, so no public authority
 * vouches for it. Instead of trusting "any certificate", this client trusts exactly one public key —
 * the one whose SHA-256 is [BrandConfig.panelPin] — and refuses every other, including a valid public
 * certificate for the same address. A man in the middle therefore cannot read the credentials.
 */
class PanelClient(
    /** Where the backup provider the panel sends is kept; null in tests. */
    private val backupStore: BackupStore? = null,
) {

    private val client: OkHttpClient by lazy {
        val trust = PinnedTrustManager(BrandConfig.panelPin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // The certificate names the IP (subjectAltName IP:…), so standard hostname checks still run.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Registers (or re-identifies) this device; returns its activation code, or null on failure. */
    suspend fun register(identity: DeviceIdentity): RegisterResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_id", identity.deviceId)
            .put("token", identity.token)
            .put("hardware_id", identity.hardwareId)
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("app_version", BuildConfig.VERSION_NAME)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder().url("${BrandConfig.panelUrl}/api/v1/register").post(body).build()
        try {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> RegisterResult.Ok(JSONObject(resp.body.string()).getString("code"))
                    409 -> RegisterResult.IdentityTaken
                    else -> RegisterResult.Failed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "register failed: ${e.javaClass.simpleName}")
            RegisterResult.Failed
        }
    }

    suspend fun config(identity: DeviceIdentity): PanelStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BrandConfig.panelUrl}/api/v1/config")
            .header("X-Device-Id", identity.deviceId)
            .header("X-Device-Token", identity.token)
            .header("X-Hardware-Id", identity.hardwareId)
            .header("X-App-Version", BuildConfig.VERSION_NAME)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) return@use PanelStatus.Unknown
                if (resp.code != 200) return@use PanelStatus.Unreachable
                val o = JSONObject(resp.body.string())
                val code = o.optString("code")
                val status = o.optString("status")
                // The DNS servers the app resolves with, chosen on the panel (applied by PanelSync).
                runCatching {
                    o.optJSONArray("dns")?.let { arr -> tv.own.owntv.brand.BrandDns.saveServers((0 until arr.length()).map { arr.optString(it) }) }
                }
                // The backup provider travels with every answer; no backup (or not active) = none on the device.
                runCatching {
                    val b = o.optJSONObject("backup")?.takeIf { status == "active" }
                    backupStore?.save(
                        b?.let {
                            BackupAccount(it.optString("server_url").trim().trimEnd('/'), it.optString("username"), it.optString("password"))
                        }?.takeIf { it.serverUrl.isNotBlank() && it.username.isNotBlank() },
                    )
                }
                when (status) {
                    "active" -> PanelStatus.Active(
                        code = code,
                        serverUrl = o.optString("server_url").trim().trimEnd('/'),
                        username = o.optString("username"),
                        password = o.optString("password"),
                        playlists = o.optJSONArray("playlists")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                                val url = p.optString("url").trim()
                                if (!url.startsWith("http")) return@mapNotNull null
                                BrandPlaylist(p.optLong("id"), p.optString("name").trim(), url, p.optString("epg_url").trim())
                            }
                        }.orEmpty(),
                    )
                    "disabled" -> PanelStatus.Disabled(code)
                    "expired" -> PanelStatus.Expired(code)
                    "unknown" -> PanelStatus.Unknown
                    else -> PanelStatus.Pending(code)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "config failed: ${e.javaClass.simpleName}")
            PanelStatus.Unreachable
        }
    }

    /**
     * Sends what this device is playing (see [PlaybackStats]). Returns false when the panel does not
     * know the device (401) — no point sending more until the next launch.
     */
    suspend fun stats(identity: DeviceIdentity, payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BrandConfig.panelUrl}/api/v1/stats")
            .header("X-Device-Id", identity.deviceId)
            .header("X-Device-Token", identity.token)
            .header("X-Hardware-Id", identity.hardwareId)
            .header("X-App-Version", BuildConfig.VERSION_NAME)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { resp -> resp.code != 401 }
        } catch (e: Exception) {
            Log.w(TAG, "stats failed: ${e.javaClass.simpleName}")
            true
        }
    }

    sealed interface RegisterResult {
        data class Ok(val code: String) : RegisterResult
        /** Another install already uses this id (vanishingly rare): make a new identity. */
        data object IdentityTaken : RegisterResult
        data object Failed : RegisterResult
    }

    private companion object {
        const val TAG = "PanelClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Trusts a server certificate only if its public key hashes (SHA-256, base64) to [pin]. */
private class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        leaf.checkValidity()
        val hash = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        val actual = Base64.encodeToString(hash, Base64.NO_WRAP)
        if (pin.isEmpty() || !MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) {
            throw CertificateException("panel certificate does not match the pinned key")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
