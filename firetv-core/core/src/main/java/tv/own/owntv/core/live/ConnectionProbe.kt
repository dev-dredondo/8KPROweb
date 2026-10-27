package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.own.owntv.core.network.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** True when this URL is an HLS playlist rather than a continuous stream. */
private fun looksLikeHls(url: String): Boolean =
    url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
        url.substringBefore('?').endsWith(".m3u", ignoreCase = true)

/** How far the probe has got, for a screen that must not look like it has hung. */
data class ProbeProgress(val stream: Int, val attempt: Int, val maxStreams: Int)

/**
 * Supplies the probe with stream URLs, one distinct channel per call.
 *
 * A function rather than a list because a Stalker portal mints a link per play: the URL for the
 * third attempt does not exist until the third attempt asks for it. Returns null when the playlist
 * has run out of channels to offer.
 */
fun interface ProbeChannels {
    suspend fun urlAt(index: Int): String?
}

/**
 * Measures how many streams a provider will actually serve at once, by opening them.
 *
 * **Why this exists.** Xtream panels publish the number. Stalker portals and plain M3U playlists
 * usually do not, and the one measured portal never refuses in any visible way — two stream requests
 * both answer `200`, `create_link` reports `error: ""`, and the *first* connection is then closed
 * roughly seven seconds later with no error at all. There is nothing to read, so there is nothing to
 * do but try it and watch.
 *
 * **What it costs.** The bytes are thrown away — only the open connection matters — so the reads are
 * throttled to [TRICKLE_BYTES_PER_SEC] and a whole run moves a few megabytes rather than the ~45 MB
 * an unthrottled minute of two live streams would pull. That matters most on a phone.
 *
 * **When it may run.** Never behind the user's back while something is playing: on a
 * single-connection account the probe *is* the thing that cuts the channel off. Callers run it when
 * a playlist is first added — before any channel rows exist, so nothing can be playing — or when the
 * user explicitly asks and playback has been stopped first.
 *
 * The decisions live in [ConnectionProbeRun] and are unit-tested there; this class only opens
 * streams and reports what happened to them.
 */
class ConnectionProbe(private val http: HttpClient) {

    suspend fun measure(
        channels: ProbeChannels,
        userAgent: String? = null,
        maxStreams: Int = MAX_PROBE_STREAMS,
        onProgress: (ProbeProgress) -> Unit = {},
    ): ConnectionProbeResult = coroutineScope {
        runProbe(this, channels, userAgent, maxStreams, onProgress)
    }

    private suspend fun runProbe(
        scope: CoroutineScope,
        channels: ProbeChannels,
        userAgent: String?,
        maxStreams: Int,
        onProgress: (ProbeProgress) -> Unit,
    ): ConnectionProbeResult {
        val run = ConnectionProbeRun(maxStreams)
        val held = mutableListOf<HeldStream>()
        // One channel per attempt, never reused: trying the same dead channel twice would "confirm"
        // a refusal that was only ever a broken channel.
        var nextChannel = 0
        val deadline = System.currentTimeMillis() + PROBE_BUDGET_MS
        try {
            while (true) {
                val step = run.next()
                if (step is ProbeStep.Finished) return step.result
                val open = step as ProbeStep.OpenStream
                if (System.currentTimeMillis() >= deadline) {
                    Log.i(TAG, "budget spent after ${held.count { it.isFlowing }} stream(s)")
                    return confirmed(held.count { it.isFlowing })
                }
                onProgress(ProbeProgress(open.slot, open.attempt, maxStreams))
                val url = channels.urlAt(nextChannel++)
                if (url == null) {
                    // The playlist has no more channels to offer. Whatever is already flowing stands.
                    Log.i(TAG, "out of channels at stream ${open.slot}")
                    return confirmed(held.count { it.isFlowing })
                }
                val stream = HeldStream(scope, http, url, userAgent)
                stream.start()
                val outcome = watch(newcomer = stream, earlier = held.toList())
                Log.i(TAG, "stream ${open.slot} attempt ${open.attempt} -> $outcome")
                if (outcome == StreamOutcome.FLOWING) {
                    held += stream
                } else {
                    stream.stop()
                    // An earlier stream the provider killed is gone whatever we do; drop it so the
                    // next round counts only what is genuinely still running.
                    held.filterNot { it.isFlowing }.forEach { it.stop() }
                    held.removeAll { !it.isFlowing }
                }
                run.record(outcome)
            }
        } finally {
            held.forEach { it.stop() }
            // Release the connections before returning: a probe that left them open would be the
            // very thing it was measuring.
            held.forEach { it.await() }
        }
    }

    /**
     * Wait for [newcomer] to prove itself, or for one of [earlier] to be cut off.
     *
     * Both refusals are watched for at once because providers use both: some never serve the new
     * stream, and some serve it and quietly drop the oldest.
     */
    private suspend fun watch(newcomer: HeldStream, earlier: List<HeldStream>): StreamOutcome {
        val startedBy = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < startedBy) {
            if (earlier.any { !it.isFlowing }) return StreamOutcome.EARLIER_STREAM_DIED
            if (newcomer.isFlowing) break
            if (newcomer.isFinished) return StreamOutcome.NEVER_STARTED
            delay(POLL_MS)
        }
        if (!newcomer.isFlowing) return StreamOutcome.NEVER_STARTED
        // It started. Now hold everything together long enough for a provider that refuses by
        // dropping the oldest connection — measured at about seven seconds on a real portal.
        val settleBy = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < settleBy) {
            if (earlier.any { !it.isFlowing }) return StreamOutcome.EARLIER_STREAM_DIED
            if (!newcomer.isFlowing) return StreamOutcome.EARLIER_STREAM_DIED
            delay(POLL_MS)
        }
        return StreamOutcome.FLOWING
    }

    private fun confirmed(flowing: Int): ConnectionProbeResult =
        if (flowing <= 0) ConnectionProbeResult.Unknown else ConnectionProbeResult.Limit(flowing, atLeast = true)

    /**
     * One stream, held open and read as slowly as the provider will tolerate.
     *
     * "Flowing" means bytes arrived recently — not merely that the socket is open. A provider that
     * accepts the connection and then sends nothing is refusing, and would otherwise look identical
     * to one that is working.
     */
    private class HeldStream(
        private val scope: CoroutineScope,
        private val http: HttpClient,
        private val url: String,
        private val userAgent: String?,
    ) {
        private val bytes = AtomicLong(0)
        private val lastByteAt = AtomicLong(0)
        private val finished = AtomicBoolean(false)
        private var job: Job? = null

        /** Data has arrived, and arrived recently enough that the connection is not being starved. */
        val isFlowing: Boolean
            get() = bytes.get() >= FLOWING_BYTES &&
                !finished.get() &&
                System.currentTimeMillis() - lastByteAt.get() < STARVED_MS

        val isFinished: Boolean get() = finished.get()

        fun start() {
            job = scope.launch(Dispatchers.IO) {
                runCatching { if (looksLikeHls(url)) pumpHls() else pumpDirect(url) }
                    .onFailure { Log.d(TAG, "stream ended: ${it.message}") }
                finished.set(true)
            }
        }

        /** A continuous stream — MPEG-TS from an Xtream panel or a Stalker portal. Read it slowly. */
        private suspend fun pumpDirect(target: String) {
            http.get(target, userAgent) { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (isRunning()) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    bytes.addAndGet(read.toLong())
                    lastByteAt.set(System.currentTimeMillis())
                    // Throttle: the connection is the measurement, the video is not.
                    delay(CHUNK_BYTES * 1000L / TRICKLE_BYTES_PER_SEC)
                }
            }
        }

        /**
         * HLS, which is most of what an M3U playlist contains.
         *
         * The URL in the playlist is not a stream at all — it is a few hundred bytes of text that
         * arrive and close immediately. Reading it the way a continuous stream is read makes every
         * channel look stillborn, which is exactly what happened: a real playlist of working channels
         * was measured as "could not tell" in under a second.
         *
         * So the playlist is followed to its segments and those are pulled, refreshing the playlist
         * as a live one grows. That is also what a player does, which is the point — the measurement
         * should cost the provider what watching costs it.
         */
        private suspend fun pumpHls() {
            var media = url
            var guard = 0
            // A master playlist points at variant playlists; follow one down to the media playlist.
            while (isRunning() && guard++ < MAX_VARIANT_HOPS) {
                val text = http.getText(media, userAgent)
                val variant = firstUri(text, media, wantVariant = true) ?: break
                media = variant
            }
            var lastPlayed: String? = null
            while (isRunning()) {
                val text = runCatching { http.getText(media, userAgent) }.getOrNull() ?: break
                val segments = segmentUrls(text, media)
                if (segments.isEmpty()) break
                // Newest first — a live playlist's tail is what a player would be fetching now.
                val fresh = segments.dropWhile { it != lastPlayed }.drop(1).ifEmpty { segments.takeLast(1) }
                for (segment in fresh) {
                    if (!isRunning()) return
                    runCatching { pumpDirect(segment) }
                    lastPlayed = segment
                }
                delay(SEGMENT_POLL_MS)
            }
        }

        /** Still wanted? Skip cancels the coroutine, and that is what closes these connections. */
        private suspend fun isRunning(): Boolean = kotlin.coroutines.coroutineContext.isActive

        /** The first variant playlist in a master, or null when [text] is already a media playlist. */
        private fun firstUri(text: String, base: String, wantVariant: Boolean): String? {
            if (wantVariant && !text.contains(STREAM_INF)) return null
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val index = lines.indexOfFirst { it.startsWith(STREAM_INF) }
            if (index < 0 || index + 1 >= lines.size) return null
            return resolve(base, lines[index + 1])
        }

        private fun segmentUrls(text: String, base: String): List<String> =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { resolve(base, it) }
                .toList()

        private fun resolve(base: String, relative: String): String? =
            base.toHttpUrlOrNull()?.resolve(relative)?.toString()

        fun stop() {
            finished.set(true)
            job?.cancel()
        }

        suspend fun await() {
            runCatching { withContext(Dispatchers.IO) { job?.cancelAndJoin() } }
        }
    }

    private companion object {
        const val TAG = "ConnectionProbe"

        /** Reading this slowly holds the connection without pulling the video down with it. */
        const val TRICKLE_BYTES_PER_SEC = 32 * 1024L
        const val CHUNK_BYTES = 8 * 1024

        /** Enough bytes to be certain the provider is really serving this stream. */
        const val FLOWING_BYTES = 32 * 1024L

        /** No data for this long means the provider has stopped feeding a connection it still holds. */
        const val STARVED_MS = 6_000L

        /** How long a new stream is given to deliver anything at all. */
        const val START_TIMEOUT_MS = 12_000L

        /** How long everything is held together afterwards, to catch a provider that drops the oldest. */
        const val SETTLE_MS = 10_000L

        const val POLL_MS = 250L

        /** How long to wait before re-reading a live HLS playlist for its next segments. */
        const val SEGMENT_POLL_MS = 2_000L

        /** Master → variant → media. Two hops is already more nesting than any real playlist uses. */
        const val MAX_VARIANT_HOPS = 2

        const val STREAM_INF = "#EXT-X-STREAM-INF"

        /**
         * The whole run's ceiling.
         *
         * Four streams, each with a second attempt, each attempt costing a start timeout plus a
         * settle window, comes to roughly three minutes at its absolute worst. This cuts it at two,
         * which is what the warning the user agrees to says — and a run stopped by the ceiling still
         * returns what it confirmed rather than nothing.
         */
        const val PROBE_BUDGET_MS = 120_000L
    }
}
