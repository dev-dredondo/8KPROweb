package tv.own.owntv.features.login

import android.util.Log
import tv.own.owntv.brand.BrandConfig
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import java.net.URI
import java.net.URLEncoder

/**
 * 8K PRO: points everything already on the device at the account in use.
 *
 * When the account changes — another user signs in, or the panel hands this TV a new DNS or new
 * credentials — the playlists and guides the app created keep their catalogue, favourites and history;
 * only their address and credentials are rewritten. Playlists on unrelated servers are left alone.
 */
class BrandAccount(
    private val sourceDao: SourceDao,
    private val epgSources: EpgSourceStore,
    private val syncScheduler: tv.own.owntv.core.sync.work.CatalogSyncScheduler,
    private val sourceRepository: tv.own.owntv.core.repository.SourceRepository,
) {
    /**
     * Points the device at [target]: an Xtream account or (8K PRO) an M3U playlist from the panel.
     * [previous] is the account it had, when known; [previousServer] the server its playlists used.
     * A switch between Xtream and M3U replaces the brand's playlist with one of the other kind, linked
     * to the same profiles (the catalogue is a different one, so nothing of the old one can be kept).
     */
    suspend fun apply(target: Session, previous: Session?, previousServer: String? = previous?.serverUrl) {
        if (target.m3u) {
            applyM3u(target, previous)
        } else {
            apply(target.serverUrl, target.username, target.password, previousServer)
            if (previous?.m3u == true) {
                runCatching {
                    val olds = sourceDao.getAllOnce().filter { it.type == SourceType.M3U && it.url.trim() == previous.m3uUrl.trim() }
                    replace(
                        olds,
                        tv.own.owntv.core.database.entity.SourceEntity(
                            name = olds.firstOrNull()?.name ?: BRAND_PLAYLIST, type = SourceType.XTREAM,
                            url = target.serverUrl.trim().trimEnd('/'), username = target.username, password = target.password,
                        ),
                    )
                }.onFailure { Log.w(TAG, "playlist not switched to Xtream", it) }
            }
        }
    }

    private suspend fun applyM3u(target: Session, previous: Session?) = runCatching {
        val url = target.m3uUrl.trim()
        val epg = target.epgUrl.trim().takeIf { it.isNotEmpty() }
        val all = sourceDao.getAllOnce()
        val oldUrl = previous?.m3uUrl?.trim()?.takeIf { it.isNotEmpty() }
        val mine = all.filter { it.type == SourceType.M3U && (it.url.trim() == url || it.url.trim() == oldUrl) }
        if (mine.isNotEmpty()) {
            // Same playlist, new address or new guide: rewrite it in place and re-sync.
            mine.filter { it.url.trim() != url || it.epgUrl != epg }.forEach { old ->
                val updated = old.copy(url = url, epgUrl = epg)
                sourceDao.update(updated)
                if (old.lastSyncAt != null) {
                    syncScheduler.enqueueSync(
                        updated.id, reason = "brand_account_changed",
                        contentTypes = tv.own.owntv.core.sync.SyncContentTypes.enabledOf(updated),
                    )
                }
            }
            return@runCatching
        }
        // From Xtream to M3U: the brand's Xtream playlists give way to the M3U one.
        val hosts = listOfNotNull(hostOf(previous?.serverUrl), hostOf(BrandConfig.serverUrl)).map { it.lowercase() }.toSet()
        val olds = all.filter { it.type == SourceType.XTREAM && hostOf(it.url)?.lowercase() in hosts }
        replace(
            olds,
            tv.own.owntv.core.database.entity.SourceEntity(
                name = olds.firstOrNull()?.name ?: BRAND_PLAYLIST, type = SourceType.M3U, url = url, epgUrl = epg,
            ),
        )
    }.onFailure { Log.w(TAG, "M3U playlist not applied", it) }

    /** Adds [fresh] for every profile that had one of [olds], removes [olds] and imports [fresh]. */
    private suspend fun replace(olds: List<tv.own.owntv.core.database.entity.SourceEntity>, fresh: tv.own.owntv.core.database.entity.SourceEntity) {
        if (olds.isEmpty()) return // nothing on the device yet: the first-run setup imports it
        val profiles = olds.flatMap { sourceDao.profileIdsForSource(it.id) }.distinct()
        val id = sourceDao.insert(fresh)
        profiles.forEach { sourceDao.link(tv.own.owntv.core.database.entity.ProfileSourceCrossRef(profileId = it, sourceId = id)) }
        olds.forEach { sourceRepository.deleteSource(it) }
        val created = fresh.copy(id = id)
        syncScheduler.enqueueSync(
            id, reason = "brand_account_changed",
            contentTypes = tv.own.owntv.core.sync.SyncContentTypes.enabledOf(created),
        )
    }

    /**
     * Rewrites the brand's playlists and guide feeds to [serverUrl] with [username]/[password].
     * [previousServer] is the server they pointed at before (the old DNS), so they are still found.
     */
    suspend fun apply(serverUrl: String, username: String, password: String, previousServer: String?) {
        val newBase = serverUrl.trim().trimEnd('/')
        val oldHosts = listOfNotNull(hostOf(previousServer), hostOf(BrandConfig.serverUrl), hostOf(newBase))
            .map { it.lowercase() }.toSet()
        fun ours(url: String?) = hostOf(url)?.lowercase() in oldHosts

        runCatching {
            sourceDao.getAllOnce()
                .filter { it.type == SourceType.XTREAM && ours(it.url) }
                .filter { it.url.trimEnd('/') != newBase || it.username != username || it.password != password }
                .forEach { old ->
                    val updated = old.copy(url = newBase, username = username, password = password)
                    sourceDao.update(updated)
                    // Every channel/film/episode keeps its full stream address (server + credentials in
                    // it), so a re-sync is what makes playback use the new ones. Same as Re-sync in
                    // Settings: favourites, history and positions survive it.
                    if (old.lastSyncAt != null) {
                        syncScheduler.enqueueSync(
                            updated.id,
                            reason = "brand_account_changed",
                            contentTypes = tv.own.owntv.core.sync.SyncContentTypes.enabledOf(updated),
                        )
                    }
                }
        }.onFailure { Log.w(TAG, "playlists not updated", it) }

        runCatching {
            epgSources.getAll()
                .filter { ours(it.url) }
                .forEach { feed ->
                    val rewritten = rewrite(feed.url, newBase, username, password)
                    if (rewritten != feed.url) epgSources.update(feed.copy(url = rewritten))
                }
        }.onFailure { Log.w(TAG, "guide feeds not updated", it) }
    }

    private fun hostOf(url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { runCatching { URI(it.trim()).host }.getOrNull() }

    /** Same path, new scheme/host/port, and the account's username/password in the query. */
    private fun rewrite(url: String, newBase: String, username: String, password: String): String = runCatching {
        val old = URI(url.trim())
        val query = old.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.joinToString("&") { part ->
            when (part.substringBefore('=')) {
                "username" -> "username=" + URLEncoder.encode(username, "UTF-8")
                "password" -> "password=" + URLEncoder.encode(password, "UTF-8")
                else -> part
            }
        }
        newBase + (old.rawPath ?: "") + (query?.let { "?$it" } ?: "")
    }.getOrDefault(url)

    private companion object {
        const val TAG = "BrandAccount"
        /** Name of a playlist created here when the device had none to take it from. */
        const val BRAND_PLAYLIST = "8K PRO"
    }
}
