package tv.own.owntv.features.login

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * 8K PRO: orders the activation panel sends to a device. Today one: "close" — the admin pressed
 * "Cerrar app" on the panel, and the app closes (playback stops, the app leaves the screen and its
 * process ends). The order reaches the device within seconds while the app is open ([watch]) or
 * playing (the playback statistics' answer), and expires on the panel if the device is not running.
 */
object BrandRemote {
    private const val TAG = "BrandRemote"
    /** How often an open app asks the panel whether there is an order for it. */
    private const val POLL_MS = 15_000L

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** While the app is on screen: ask the panel every [POLL_MS] whether to close. Cancelled with the caller. */
    suspend fun watch(panel: PanelClient, sessionStore: SessionStore) {
        if (!tv.own.owntv.brand.BrandConfig.activationEnabled) return
        while (true) {
            delay(POLL_MS)
            val who = runCatching { sessionStore.identity() }.getOrNull() ?: continue
            panel.command(who)?.let { handle(it) }
        }
    }

    fun handle(command: String) {
        when (command) {
            "close" -> close()
            else -> Log.w(TAG, "unknown panel command")
        }
    }

    private fun close() {
        val context = appContext ?: return
        Log.i(TAG, "closing the app at the panel's request")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { it.finishAndRemoveTask() }
        }
        // A playing stream, the media session or a background service must not outlive "close".
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 800)
    }
}
