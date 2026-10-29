package tv.own.owntv.features.login

import android.content.Context
import android.util.Log
import org.json.JSONObject
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * 8K PRO: the M3U playlists the panel adds to a device as extra providers, next to its Xtream account.
 *
 * Each one becomes a playlist of its own on the device, in every profile, with its guide when the
 * panel gives one. The panel's id of each list is remembered with the playlist it became, so a list
 * renamed or pointed at a new address on the panel updates that same playlist (favourites and history
 * stay), and a list removed on the panel is removed from the device. Playlists the user added by hand
 * are never touched.
 */
class BrandPlaylists(
    context: Context,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val sourceRepository: SourceRepository,
    private val syncScheduler: CatalogSyncScheduler,
    private val settings: SettingsRepository,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Makes the device's panel playlists exactly [lists]. Nothing happens before the first profile exists. */
    suspend fun sync(lists: List<BrandPlaylist>) = runCatching {
        val profiles = profileDao.getAllOnce().map { it.id }
        if (profiles.isEmpty()) return@runCatching // first run: they are added once onboarding has made a profile
        val known = load()
        val links = sourceDao.allLinks().groupBy({ it.sourceId }, { it.profileId })
        val wanted = lists.associateBy { it.id }

        // Removed on the panel.
        known.filterKeys { it !in wanted }.forEach { (panelId, sourceId) ->
            sourceDao.getById(sourceId)?.let { sourceRepository.deleteSource(it) }
            known.remove(panelId)
        }

        lists.forEach { list ->
            val epg = list.epgUrl.takeIf { it.isNotBlank() }
            val name = list.name.ifBlank { DEFAULT_NAME }
            val existing = known[list.id]?.let { sourceDao.getById(it) }
            if (existing == null) {
                val id = sourceDao.insert(SourceEntity(name = name, type = SourceType.M3U, url = list.url, epgUrl = epg))
                profiles.forEach { sourceDao.link(ProfileSourceCrossRef(profileId = it, sourceId = id)) }
                known[list.id] = id
                runCatching { settings.setPlaylistAutoRefresh(id, tv.own.owntv.brand.BrandDefaults.PLAYLIST_REFRESH) }
                sourceDao.getById(id)?.let { syncScheduler.enqueueSync(id, reason = "brand_playlist_added", contentTypes = SyncContentTypes.enabledOf(it)) }
                Log.i(TAG, "panel playlist added")
            } else {
                val moved = existing.url != list.url || existing.epgUrl != epg
                if (moved || existing.name != name) {
                    val updated = existing.copy(name = name, url = list.url, epgUrl = epg)
                    sourceDao.update(updated)
                    if (moved) syncScheduler.enqueueSync(existing.id, reason = "brand_playlist_changed", contentTypes = SyncContentTypes.enabledOf(updated))
                }
                // A profile added since: it gets the panel's playlists too.
                val linked = links[existing.id].orEmpty()
                profiles.filter { it !in linked }.forEach { sourceDao.link(ProfileSourceCrossRef(profileId = it, sourceId = existing.id)) }
            }
        }
        save(known)
    }.onFailure { Log.w(TAG, "panel playlists not applied", it) }

    private fun load(): MutableMap<Long, Long> = runCatching {
        val o = JSONObject(prefs.getString(KEY_MAP, null) ?: "{}")
        o.keys().asSequence().associate { it.toLong() to o.getLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun save(map: Map<Long, Long>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k.toString(), v) }
        prefs.edit().putString(KEY_MAP, o.toString()).apply()
    }

    private companion object {
        const val TAG = "BrandPlaylists"
        const val PREFS = "brand_playlists"
        const val KEY_MAP = "panel_to_source"
        const val DEFAULT_NAME = "M3U"
    }
}
