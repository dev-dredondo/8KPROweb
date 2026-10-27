package tv.own.owntv.core.live

/**
 * How many streams the probe will ever open. Four is Multiview's largest grid, so a fifth would cost
 * a connection and a quarter of a minute to learn a number nothing in the app can act on.
 */
const val MAX_PROBE_STREAMS = 4

/** How many *different channels* one slot may be tried with before its failure is believed. */
private const val ATTEMPTS_PER_SLOT = 2

/** What happened when the probe opened one stream. */
enum class StreamOutcome {
    /** It opened and delivered data, and every stream already open kept delivering too. */
    FLOWING,

    /** It never delivered anything — a refusal, or simply a channel that does not work. */
    NEVER_STARTED,

    /**
     * It opened, but a stream that was already running stopped. The provider's quietest refusal, and
     * the one a real portal was measured doing: both requests answer `200`, and the older connection
     * is closed about seven seconds later without an error of any kind.
     */
    EARLIER_STREAM_DIED,
}

/** What the caller should do next. */
sealed interface ProbeStep {
    /** Open one more stream. [attempt] is 1 or 2 — the second must use a *different* channel. */
    data class OpenStream(val slot: Int, val attempt: Int) : ProbeStep

    data class Finished(val result: ConnectionProbeResult) : ProbeStep
}

sealed interface ConnectionProbeResult {
    /**
     * The provider allows [streams] at once. [atLeast] means the probe stopped at its own ceiling
     * rather than at the provider's, so the real number may be higher.
     */
    data class Limit(val streams: Int, val atLeast: Boolean) : ConnectionProbeResult

    /** Nothing could be concluded. Stored as "not known", never as zero streams. */
    data object Unknown : ConnectionProbeResult
}

/**
 * The probe's decisions, separated from the streams so they can be tested without a provider.
 *
 * **The problem this solves.** Some providers publish how many streams an account may run; several
 * do not, and one measured portal reports 162 profile fields without a single one of them being a
 * connection limit. It hands out a second stream link with `error: ""`, answers `200` to both
 * requests, and then closes the *first* connection seven seconds later. Nothing in the protocol ever
 * says no. The only way to know the number is to try.
 *
 * **Why a state machine.** A stream that delivers nothing is either the provider refusing or a
 * channel that is broken, and from that stream alone the two are identical. So a slot is tried with
 * a *second, different channel* before its failure is believed, and a limit is concluded only when
 * two different channels fail at the same slot. One dead channel can therefore never cap a playlist
 * — which matters, because a wrong low number refuses tiles the user has paid for, a worse failure
 * than not knowing at all.
 *
 * Not thread-safe, and not meant to be: one run, one coroutine.
 */
class ConnectionProbeRun(private val maxStreams: Int = MAX_PROBE_STREAMS) {

    /** How many streams are confirmed to run at the same time. */
    private var flowing = 0

    /** Which channel of the current slot is being tried: 1, then 2. */
    private var attempt = 1

    private var result: ConnectionProbeResult? = null

    /** The next thing to do. Safe to call repeatedly; it changes nothing. */
    fun next(): ProbeStep =
        result?.let { ProbeStep.Finished(it) } ?: ProbeStep.OpenStream(slot = flowing + 1, attempt = attempt)

    /** Report what happened to the stream [next] asked for. */
    fun record(outcome: StreamOutcome) {
        if (result != null) return
        when (outcome) {
            StreamOutcome.FLOWING -> {
                flowing++
                attempt = 1
                // The ceiling is the probe's own, not the provider's, so the answer says "at least".
                if (flowing >= maxStreams) result = ConnectionProbeResult.Limit(flowing, atLeast = true)
            }
            StreamOutcome.NEVER_STARTED, StreamOutcome.EARLIER_STREAM_DIED -> {
                if (attempt < ATTEMPTS_PER_SLOT) {
                    // Another channel, same slot. A broken channel gets exactly one chance to be
                    // exposed as broken rather than mistaken for the provider's answer.
                    attempt++
                } else {
                    result = if (flowing == 0) {
                        // Nothing ever played. The provider's limit is not what this measured.
                        ConnectionProbeResult.Unknown
                    } else {
                        ConnectionProbeResult.Limit(flowing, atLeast = false)
                    }
                }
            }
        }
    }
}
