package tv.own.owntv.features.login

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.brand.BrandConfig
import tv.own.owntv.player.PlaybackEngine
import java.util.UUID

/**
 * 8K PRO: tells the activation panel what this device is watching, so the admin sees it live.
 *
 * One "watch session" per item (channel, film or episode): what it is, how long it has been on, how many
 * times it stopped to buffer after it had started and for how long in total, and how long it took to
 * start. Sent when the item starts, as soon as it starts buffering, every [HEARTBEAT_MS] while it plays,
 * and once more ("stopped") when the player closes or switches to another item.
 *
 * The shell attaches whichever engine is playing — the same one it hands to the MediaSession — so
 * this sees fullscreen mpv, the ExoPlayer live engine and Multiview's audible tile alike. Nothing is
 * sent without an activation panel, and nothing that identifies the stream (no URL, no server).
 * Everything runs on the main thread; only the HTTP call leaves it.
 */
class PlaybackStats(
    private val sessionStore: SessionStore,
    private val panel: PanelClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: PlaybackEngine? = null
    private var engineJob: Job? = null
    private var identity: DeviceIdentity? = null
    /** False once the panel answered 401: it does not know this device, so stop until the next launch. */
    private var panelKnowsUs = true

    // ---- the current watch session
    private var sessionId: String? = null
    private var title: String = ""
    private var startedAt = 0L          // elapsedRealtime
    private var everPlayed = false
    private var startupMs: Long? = null
    private var bufferingCount = 0
    private var bufferingMs = 0L
    private var bufferingSince: Long? = null
    private var lastBufferingSend = 0L

    /** The engine now playing, or null when the player closed. Same calls the shell makes to the MediaSession. */
    fun attach(engine: PlaybackEngine?) {
        if (!BrandConfig.activationEnabled || engine === this.engine) return
        engineJob?.cancel()
        this.engine = engine
        if (engine == null) {
            endSession()
            return
        }
        engineJob = scope.launch {
            launch {
                engine.currentMeta.map { it.title?.trim().orEmpty() }.distinctUntilChanged().collect { t ->
                    if (t.isEmpty()) return@collect
                    // The same item handed to another engine (live preview promoted to fullscreen,
                    // Multiview's sound moving) is still the same viewing: keep counting.
                    if (sessionId != null && t == title) return@collect
                    endSession()
                    startSession(t)
                }
            }
            launch {
                engine.isPlaying.collect { playing ->
                    if (playing && sessionId != null && !everPlayed) {
                        everPlayed = true
                        startupMs = SystemClock.elapsedRealtime() - startedAt
                        bufferingSince = null
                    }
                }
            }
            launch { engine.buffering.collect { onBuffering(it) } }
            launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    if (sessionId != null) send(state())
                }
            }
        }
    }

    private fun startSession(t: String) {
        sessionId = UUID.randomUUID().toString()
        title = t
        startedAt = SystemClock.elapsedRealtime()
        everPlayed = false
        startupMs = null
        bufferingCount = 0
        bufferingMs = 0L
        bufferingSince = null
        send(state())
    }

    /** Closes the current session on the panel ("stopped"), if there is one. */
    private fun endSession() {
        if (sessionId == null) return
        closeBuffering()
        send("stopped")
        sessionId = null
    }

    private fun onBuffering(buffering: Boolean) {
        if (sessionId == null || !everPlayed) return // before the first frame it is start-up, not a cut
        val now = SystemClock.elapsedRealtime()
        if (buffering && bufferingSince == null) {
            bufferingSince = now
            bufferingCount++
            if (now - lastBufferingSend > BUFFERING_SEND_GAP_MS) {
                lastBufferingSend = now
                send("buffering")
            }
        } else if (!buffering) {
            closeBuffering()
        }
    }

    private fun closeBuffering() {
        bufferingSince?.let { bufferingMs += SystemClock.elapsedRealtime() - it }
        bufferingSince = null
    }

    private fun state(): String {
        val e = engine ?: return "stopped"
        return when {
            e.error.value != null -> "error"
            !everPlayed || e.buffering.value -> "buffering"
            e.isPlaying.value -> "playing"
            else -> "paused"
        }
    }

    private fun send(state: String) {
        val id = sessionId ?: return
        if (!panelKnowsUs) return
        val now = SystemClock.elapsedRealtime()
        val e = engine
        val payload = JSONObject()
            .put("session_id", id)
            .put("state", state)
            .put("title", title.take(160))
            .put("kind", if (e?.isLiveContent != false) "live" else "vod")
            .put("watch_s", (now - startedAt) / 1000)
            .put("buffering_count", bufferingCount)
            .put("buffering_s", (bufferingMs + (bufferingSince?.let { now - it } ?: 0L)) / 1000)
            .put("startup_ms", startupMs ?: JSONObject.NULL)
            .put("video", e?.videoRes?.value ?: JSONObject.NULL)
            .put("engine", e?.engineChip?.value ?: JSONObject.NULL)
            // Which server the stream comes from (scheme://host:port, never the credentials or the path).
            .put("server", streamServer(e) ?: JSONObject.NULL)
            .put("backup", e?.isLiveContent != false && LiveStreamServer.backup)
        scope.launch {
            runCatching {
                val who = identity ?: sessionStore.identity().also { identity = it }
                if (!panel.stats(who, payload)) panelKnowsUs = false
            }
        }
    }

    private fun streamServer(e: PlaybackEngine?): String? {
        val live = e?.isLiveContent != false
        return (if (live) LiveStreamServer.origin else null) ?: LiveStreamServer.originOf(BrandConfig.activeServerUrl)
    }

    private companion object {
        const val HEARTBEAT_MS = 30_000L
        /** A stream that stutters many times in a row is reported once per this gap; the counts carry the rest. */
        const val BUFFERING_SEND_GAP_MS = 10_000L
    }
}

/**
 * 8K PRO: the server of the live channel playing now, for the panel's live view. Set where a live
 * channel is opened (main account or backup provider); films and series always come from the account.
 */
object LiveStreamServer {
    @Volatile
    var origin: String? = null
        private set

    @Volatile
    var backup: Boolean = false
        private set

    fun note(url: String?, backup: Boolean = false) {
        origin = originOf(url)
        this.backup = backup
    }

    /** "http://host:port" of [url]: no credentials, no path. */
    fun originOf(url: String?): String? = url?.takeIf { it.isNotBlank() }?.let {
        runCatching {
            val u = java.net.URI(it.trim())
            val host = u.host ?: return@runCatching null
            "${u.scheme ?: "http"}://$host" + (if (u.port > 0) ":${u.port}" else "")
        }.getOrNull()
    }
}
