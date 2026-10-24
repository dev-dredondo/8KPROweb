package tv.own.owntv.brand

import tv.own.owntv.BuildConfig

/**
 * 8K PRO: everything that makes this build "8K PRO" rather than OwnTV, in one place.
 *
 * The values come from app/build.gradle.kts (BuildConfig), so a different server or update repository
 * is a Gradle property away (`-Pbrand.serverUrl=…`, `-Pbrand.updateRepo=owner/repo`) — no code edit.
 */
object BrandConfig {
    /**
     * The Xtream Codes server (the provider's "DNS") used by the manual login. A device activated from
     * the panel uses the server the panel hands it instead ([sessionServerUrl]).
     */
    val serverUrl: String = BuildConfig.BRAND_SERVER_URL.trimEnd('/')

    /** Activation panel base URL ("https://1.2.3.4"); blank = no activation, manual login only. */
    val panelUrl: String = BuildConfig.BRAND_PANEL_URL.trim().trimEnd('/')

    /**
     * SHA-256 of the panel certificate's public key (base64). The panel uses a certificate it made
     * itself, so the app trusts exactly that key and nothing else.
     */
    val panelPin: String = BuildConfig.BRAND_PANEL_PIN.trim()

    val activationEnabled: Boolean get() = panelUrl.isNotEmpty() && panelPin.isNotEmpty()

    /** GitHub `owner/repo` whose releases the in-app updater reads; blank turns the updater off. */
    val updateRepo: String = BuildConfig.BRAND_UPDATE_REPO.trim()

    val updatesEnabled: Boolean get() = updateRepo.isNotEmpty()

    /**
     * TMDB v3 API key for movie/series details and Trending. OwnTV's shared metadata server only
     * answers OwnTV's own signed builds, so this brand talks to api.themoviedb.org with its own key.
     */
    val tmdbApiKey: String = BuildConfig.BRAND_TMDB_API_KEY.trim()

    /**
     * The server of the signed-in account (set by SessionStore). With panel activation it can differ
     * from [serverUrl] and change at any launch, so every "is this our server?" check looks at both.
     */
    @Volatile
    var sessionServerUrl: String? = null

    /** The server the account in use talks to. */
    val activeServerUrl: String get() = sessionServerUrl?.takeIf { it.isNotBlank() } ?: serverUrl

    private fun hostOf(url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { runCatching { java.net.URI(it.trim()).host }.getOrNull() }

    /** Hosts never to show on screen: the default server and the account's server. */
    private val hiddenHosts: List<String>
        get() = listOfNotNull(hostOf(serverUrl), hostOf(sessionServerUrl)).distinct()

    /** Host of [serverUrl] ("vpn.example.com"), for spotting it inside longer addresses. */
    val serverHost: String = hostOf(serverUrl).orEmpty()

    /**
     * Whether [url] points at one of the brand's servers — any path or port on it (player_api.php,
     * xmltv.php, a stream). Used to keep the server's address off every screen.
     */
    fun isOnBrandServer(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return hiddenHosts.any { it.equals(host, ignoreCase = true) }
    }

    /** Whether [text] (an error message, a log line) mentions one of the brand's servers anywhere. */
    fun mentionsServer(text: String?): Boolean =
        !text.isNullOrEmpty() && hiddenHosts.any { text.contains(it, ignoreCase = true) }

    /** Whether [url] is exactly the default server (ignoring a trailing slash and letter case). */
    fun isBrandServer(url: String?): Boolean =
        url != null && url.trim().trimEnd('/').equals(serverUrl, ignoreCase = true)
}
