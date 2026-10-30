package tv.own.owntv.brand

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * 8K PRO: the settings this brand ships with, written over core's defaults.
 *
 * Core keeps its defaults in code (a missing preference reads as OwnTV's default), so the brand's own
 * are written once into the same DataStore. Each set of defaults has a version: an install applies the
 * version it has not seen yet, once, and the user's later changes in Settings are never overwritten.
 * Bump [VERSION] (and add to [apply]) to ship a new default to existing installs.
 *
 * The TMDB key is different: it is a build input, re-applied whenever the stored key is blank, so
 * metadata works out of the box without the user typing anything.
 */
object BrandDefaults {
    private const val TAG = "BrandDefaults"
    private const val PREFS = "brand_defaults"
    private const val KEY_VERSION = "applied_version"
    private const val VERSION = 5

    const val ACCENT_HEX = "#FFB95C"
    /** TMDB language code as offered in Settings › Metadata (plain "es" = Spanish, Spain). */
    const val METADATA_LANGUAGE = "es"
    /** Every playlist on the account's server re-syncs (channels, films, series) at least every 3 days. */
    val PLAYLIST_REFRESH = tv.own.owntv.core.settings.PlaylistRefresh(tv.own.owntv.core.settings.PlaylistAutoRefresh.MANUAL, 3)

    /** Fire TV build: whether this device can load mpv's native libraries (Android 7.1 is below what it targets). */
    private fun mpvLoads(): Boolean = runCatching {
        System.loadLibrary("mpv")
        System.loadLibrary("player")
        true
    }.onFailure { Log.w(TAG, "mpv cannot load on this device: ${it.javaClass.simpleName}") }.getOrDefault(false)

    private const val KEY_FIRETV_MPV = "firetv_mpv_applied"

    fun apply(
        context: Context,
        scope: CoroutineScope,
        settings: SettingsRepository,
        metadata: MetadataRepository,
        sourceDao: tv.own.owntv.core.database.dao.SourceDao,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_VERSION, 0)
        BrandDns.init(context)
        scope.launch {
            runCatching {
                if (applied < 1) {
                    settings.setTunneledPlayback(true)
                    settings.setCustomAccent(ACCENT_HEX)
                    settings.setMetadataLanguage(METADATA_LANGUAGE)
                    // Rows cached under the old language would otherwise keep showing until they expire.
                    runCatching { metadata.clearCacheForLanguageChange() }
                }
                if (applied < 2) {
                    settings.setLivePreviewEnabled(false)
                }
                if (applied < 3) {
                    // Live TV › "Give up on a channel after": Never.
                    settings.setLiveTuneTimeoutSecs(0)
                    // Playlists added before this version: refresh every 3 days (new ones get it at import).
                    sourceDao.getAllOnce()
                        .filter { it.type == tv.own.owntv.core.model.SourceType.XTREAM && BrandConfig.isOnBrandServer(it.url) }
                        .forEach { settings.setPlaylistAutoRefresh(it.id, PLAYLIST_REFRESH) }
                }
                if (applied < 4) {
                    // 1.0.3: the brand's own DNS servers switched on (Settings › Network › DNS).
                    BrandDns.enableDefault(settings)
                }
                if (applied < 5) {
                    // 1.0.7: live TV on mpv first (steadier buffer, no dropped frames), ExoPlayer as its fallback.
                    settings.setLiveEnginePreference(tv.own.owntv.core.player.EnginePreference.MPV_FIRST)
                    settings.setVodEnginePreference(tv.own.owntv.core.player.EnginePreference.MPV_FIRST)
                }
                if (applied < VERSION) prefs.edit().putInt(KEY_VERSION, VERSION).apply()

                val key = BrandConfig.tmdbApiKey
                if (key.isNotEmpty() && settings.currentTmdbApiKey().isBlank()) {
                    settings.setTmdbApiKey(key)
                }
                // Older Fire TV (Android 7.1, the Fire TV build): mpv is built for Android 8. When its native
                // libraries load on this device it is used like everywhere else (mpv first, ExoPlayer as the
                // fallback); when they do not, everything plays on ExoPlayer only, checked at every launch.
                if (android.os.Build.VERSION.SDK_INT < 26) {
                    val engine = if (mpvLoads()) tv.own.owntv.core.player.EnginePreference.MPV_FIRST
                    else tv.own.owntv.core.player.EnginePreference.EXO_ONLY
                    if (engine == tv.own.owntv.core.player.EnginePreference.EXO_ONLY || !prefs.getBoolean(KEY_FIRETV_MPV, false)) {
                        settings.setLiveEnginePreference(engine)
                        settings.setVodEnginePreference(engine)
                        prefs.edit().putBoolean(KEY_FIRETV_MPV, engine == tv.own.owntv.core.player.EnginePreference.MPV_FIRST).apply()
                    }
                }
                // Every launch: the first DNS of the panel's list that answers (see PanelSync for the panel's own check).
                BrandDns.apply(settings)
            }.onFailure { Log.w(TAG, "brand defaults not applied", it) }
        }
    }
}
