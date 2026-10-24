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
) {
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
    }
}
