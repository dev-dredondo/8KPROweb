package tv.own.owntv.brand

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.update.UpdateManager.Failure
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 8K PRO: automatic updates from our own release channel.
 *
 * Each release publishes `version.json` next to the APK (branch `apk` of this app's repository):
 * `{"versionCode": 1010002, "versionName": "1.0.2", "apk": "https://…/8KPROweb.apk", "sha256": "…", "notes": "…"}`.
 * On every launch (and on coming back after a while) the app reads it; when [BuildConfig.VERSION_CODE] is
 * lower it downloads the APK straight away, checks it (size, SHA-256, same package, newer version) and
 * hands it to the system installer. Android always asks the user to confirm an app install — a silent
 * install is only possible for system/device-owner apps — so "automatic" means: no menus, the prompt
 * appears by itself.
 *
 * The install goes through a [PackageInstaller] session, like core's own updater, so the refusal reason
 * reaches us instead of a bare "App not installed".
 */
class BrandUpdater(private val context: Context) {

    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String?, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        /** A newer version exists but is not being downloaded (the user cancelled, or the install prompt was declined). */
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Handed to the system installer; its confirmation is on screen. */
        data class Installing(val release: Release) : State
        data class Failed(val failure: Failure, val release: Release?) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var lastCheck = 0L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val enabled: Boolean get() = BuildConfig.BRAND_UPDATE_URL.isNotBlank()

    /**
     * Looks for a newer version and, if there is one, downloads and installs it. Called on every app start;
     * calls closer than [MIN_INTERVAL_MS] apart (the app going to the background and back) are skipped.
     */
    fun checkAndUpdate(force: Boolean = false) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < MIN_INTERVAL_MS) return
        when (_state.value) {
            is State.Downloading, is State.Installing -> return
            else -> Unit
        }
        lastCheck = now
        job?.cancel()
        job = scope.launch {
            val release = runCatching { fetchRelease() }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "check failed: ${it.javaClass.simpleName}")
                null // a failed check is silent: try again next time
            }
            if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) {
                if (_state.value !is State.Failed) _state.value = State.Idle
                return@launch
            }
            download(release)
        }
    }

    /** "Update now" from the dialog: download (again) the release on show. */
    fun start() {
        val release = when (val s = _state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> null
        }
        job?.cancel()
        if (release == null) {
            checkAndUpdate(force = true)
            return
        }
        job = scope.launch { download(release) }
    }

    /** "Cancel" / "Later": stop downloading and hide the dialog until the next launch. */
    fun dismiss() {
        job?.cancel()
        _state.value = State.Idle
    }

    private fun fetchRelease(): Release? {
        val url = BuildConfig.BRAND_UPDATE_URL + "?t=" + System.currentTimeMillis() / 60_000
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body.string())
            val apk = o.optString("apk").takeIf { it.startsWith("https://") } ?: return null
            return Release(
                versionCode = o.optLong("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = apk,
                sha256 = o.optString("sha256").lowercase().takeIf { it.length == 64 },
                size = o.optLong("size"),
                notes = o.optString("notes").take(4_000),
            )
        }
    }

    private suspend fun download(release: Release) {
        _state.value = State.Downloading(release, 0)
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        try {
            if (release.size > 0 && dir.usableSpace < release.size * 2 + 20L * 1024 * 1024) {
                _state.value = State.Failed(Failure.NotEnoughSpace(release.size * 2), release)
                return
            }
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _state.value = State.Failed(Failure.DownloadHttp(resp.code), release)
                    return
                }
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                var done = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = State.Downloading(release, percent)
                            }
                        }
                    }
                }
            }
            if (out.length() == 0L) {
                _state.value = State.Failed(Failure.EmptyDownload, release)
                return
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && sha != release.sha256) {
                Log.w(TAG, "sha256 mismatch")
                _state.value = State.Failed(Failure.DamagedDownload, release)
                return
            }
            val info = context.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
            val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
            if (info == null || info.packageName != context.packageName || code == null || code <= BuildConfig.VERSION_CODE) {
                _state.value = State.Failed(Failure.DamagedDownload, release)
                return
            }
            _state.value = State.Installing(release)
            install(out)
        } catch (e: CancellationException) {
            out.delete()
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
            out.delete()
            _state.value = State.Failed(Failure.DownloadNetwork, release)
        } catch (e: Exception) {
            Log.w(TAG, "install failed: ${e.javaClass.simpleName}")
            out.delete()
            _state.value = State.Failed(Failure.Install, release)
        }
    }

    private fun install(apk: File) {
        registerReceiver()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("8kpro-update.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(ACTION_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, 7_001, intent, flags).intentSender)
        }
        apk.delete()
    }

    private var receiverRegistered = false

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        ContextCompat.registerReceiver(context, statusReceiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val release = (_state.value as? State.Installing)?.release
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { confirm?.let { context.startActivity(it) } }
                        .onFailure { _state.value = State.Failed(Failure.Install, release) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
                // Declined the system prompt: offer it again from the dialog.
                PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { State.Available(it) } ?: State.Idle
                else -> {
                    Log.w(TAG, "install refused status=$status")
                    _state.value = State.Failed(Failure.InstallRejected(message?.takeIf { it.isNotBlank() }), release)
                }
            }
        }
    }

    private companion object {
        const val TAG = "BrandUpdater"
        const val ACTION_STATUS = "tv.own.owntv.brand.UPDATE_STATUS"
        /** Back from the background within this time: no new check. */
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
    }
}
