package tv.own.owntv.features.login

import android.util.Log
import tv.own.owntv.brand.BrandConfig

/**
 * 8K PRO: at every launch, asks the panel whether this TV's account changed.
 *
 *  * New DNS or credentials → the playlists and guides are pointed at them and the session updated.
 *  * Disabled, expired or deleted on the panel → signed out (the login screen shows the code again).
 *  * Panel unreachable → nothing changes: an outage of the panel never stops anyone watching.
 *
 * Only for accounts that came from the panel; a manual login is left alone.
 */
class PanelSync(
    private val sessionStore: SessionStore,
    private val panel: PanelClient,
    private val account: BrandAccount,
    /** For the DNS servers the panel chooses (see [tv.own.owntv.brand.BrandDns]). */
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
    /** The panel's M3U playlists, added as providers next to the account. */
    private val playlists: BrandPlaylists,
) {
    @Volatile
    private var lastCheck = 0L

    /** On launch ([force]) and whenever the app comes back to the foreground, at most once a minute. */
    suspend fun refresh(force: Boolean = false) {
        if (!BrandConfig.activationEnabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < MIN_INTERVAL_MS) return
        lastCheck = now
        val session = sessionStore.current() ?: return
        if (!session.viaPanel) return
        val identity = sessionStore.identity()
        val status = panel.config(identity)
        // The panel's DNS list (saved by PanelClient): the first server that answers becomes the app's DNS.
        if (status != PanelStatus.Unreachable) tv.own.owntv.brand.BrandDns.apply(settings)
        when (status) {
            is PanelStatus.Active -> {
                // New DNS, credentials, M3U address or guide — or a switch between Xtream and M3U.
                val target = status.toSession()
                if (target != session && target.serverUrl.isNotBlank()) {
                    Log.i(TAG, "account changed on the panel, updating")
                    account.apply(target, session)
                    sessionStore.save(target)
                }
                playlists.sync(status.extraPlaylists)
            }
            is PanelStatus.Disabled, is PanelStatus.Expired, PanelStatus.Unknown -> {
                Log.i(TAG, "device no longer active on the panel, signing out")
                sessionStore.clear()
            }
            is PanelStatus.Pending -> {
                // Set back to pending on the panel: treat like disabled so the code shows again.
                sessionStore.clear()
            }
            PanelStatus.Unreachable -> Unit
        }
    }

    private companion object {
        const val TAG = "PanelSync"
        // Every time the app is opened or brought back (a few seconds apart at most): DNS, account and backup.
        const val MIN_INTERVAL_MS = 10_000L
    }
}
