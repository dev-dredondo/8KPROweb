package tv.own.owntv.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.brand.BackupCatalog
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** Continuous trouble (no picture, reconnecting) before the backup is offered. */
private const val STALL_AFTER_MS = 20_000L
/** An engine that has given up and says so: offer sooner. */
private const val ERROR_AFTER_MS = 5_000L
/** A channel that cut out this many times within [STUTTER_WINDOW_MS] is offered the backup too. */
private const val STUTTER_CUTS = 4
private const val STUTTER_WINDOW_MS = 120_000L
/** Seconds before the best backup match plays by itself when the user has not picked one. */
private const val AUTO_PLAY_SECS = 10

/**
 * 8K PRO: when a live channel stops and does not come back — still loading or reconnecting after
 * [STALL_AFTER_MS], failed, or cutting out again and again — offer the channels of the backup provider
 * whose name looks like it, as a side panel over the picture driven by the remote. The backup's channels
 * exist nowhere else in the app; picking one plays it in place ([LiveViewModel.playBackup]). With no
 * choice made, the best match plays by itself after [AUTO_PLAY_SECS]; a backup that fails too goes back
 * to the real channel, and round again. Back ("Keep waiting") hides it for this channel.
 */
@Composable
fun BackupOffer(liveVm: LiveViewModel, engine: PlaybackEngine, channel: ChannelEntity?) {
    val catalog: BackupCatalog = koinInject()
    if (channel == null || !catalog.configured) return
    var dismissedFor by remember { mutableStateOf<Long?>(null) }
    var offerFor by remember { mutableStateOf<Long?>(null) }
    // Backup streams that failed while this player was open: tried last the next time round the cycle.
    var failedUrls by remember { mutableStateOf(emptySet<String>()) }
    // True when the offer is up because the channel keeps cutting out, not because it stopped.
    var stutter by remember { mutableStateOf(false) }
    val onBackup = liveVm.playingBackup
    LaunchedEffect(channel.id, engine) {
        offerFor = null
        stutter = false
        // When each cut (buffering after the picture had started) began. Collected, not polled: most
        // cuts are shorter than a second.
        val cuts = ArrayDeque<Long>()
        launch {
            var played = false
            var wasBuffering = false
            kotlinx.coroutines.flow.combine(engine.isPlaying, engine.buffering) { p, b -> p to b }.collect { (playing, buffering) ->
                if (playing && !buffering) played = true
                if (buffering && !wasBuffering && played) cuts.addLast(SystemClock.elapsedRealtime())
                wasBuffering = buffering
            }
        }
        var troubleSince: Long? = null
        while (true) {
            delay(1_000)
            val failed = engine.error.value != null
            val trouble = failed || engine.buffering.value
            val now = SystemClock.elapsedRealtime()
            troubleSince = if (trouble) troubleSince ?: now else null
            val since = troubleSince
            val tooLong = since != null && now - since >= (if (failed) ERROR_AFTER_MS else STALL_AFTER_MS)
            while (cuts.isNotEmpty() && now - cuts.first() > STUTTER_WINDOW_MS) cuts.removeFirst()
            val stuttering = cuts.size >= STUTTER_CUTS
            when {
                // The backup channel failed too: back to the real one. If that fails again, the offer (and
                // its countdown) starts over — round and round until one of them plays.
                onBackup && tooLong -> {
                    failedUrls = failedUrls + channel.streamUrl
                    liveVm.returnFromBackup()
                    return@LaunchedEffect
                }
                onBackup -> Unit
                tooLong && dismissedFor != channel.id -> { offerFor = channel.id; stutter = false }
                stuttering && dismissedFor != channel.id && offerFor != channel.id -> { offerFor = channel.id; stutter = true }
                since == null && !stutter -> offerFor = null // the channel came back by itself
            }
        }
    }
    if (onBackup || offerFor != channel.id) return

    val suffix = stringResource(R.string.backup_suffix)
    // null = still looking; Unavailable = the backup server did not answer.
    val result by produceState<Offer?>(initialValue = null, channel.id) {
        value = catalog.alternatives(channel.name)?.let { Offer.Found(it) } ?: Offer.Unavailable
    }
    // The user's own search, for when the names are too different to be matched.
    var query by remember(channel.id) { mutableStateOf("") }
    var typing by remember(channel.id) { mutableStateOf(false) }
    val ranked = (result as? Offer.Found)?.alternatives.orEmpty().sortedBy { it.url in failedUrls }
    val autoPick = ranked.firstOrNull()
    var remaining by remember(channel.id) { mutableStateOf(AUTO_PLAY_SECS) }
    val play: (BackupCatalog.Alternative) -> Unit = { alt -> liveVm.playBackup("${alt.name} · $suffix", alt.url, alt.logoUrl) }
    LaunchedEffect(channel.id, autoPick?.url, stutter) {
        // A channel that still plays, only badly, is the user's call: no countdown for it.
        if (stutter) return@LaunchedEffect
        val pick = autoPick ?: return@LaunchedEffect
        remaining = AUTO_PLAY_SECS
        while (remaining > 0) {
            delay(1_000)
            // Paused while the user is searching.
            if (query.isBlank() && !typing) remaining--
        }
        play(pick)
    }
    val searched by produceState<List<BackupCatalog.Alternative>?>(initialValue = null, query) {
        value = if (query.isBlank()) null else catalog.search(query) ?: emptyList()
    }
    val dismiss = { dismissedFor = channel.id; offerFor = null }
    val first = remember { FocusRequester() }
    val shown = if (query.isNotBlank()) searched.orEmpty() else ranked.takeIf { result is Offer.Found }.orEmpty()
    LaunchedEffect(shown.firstOrNull()?.url, result != null) { delay(60); runCatching { first.requestFocus() } }

    OwnTVPopup(onDismissRequest = dismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(660.mpx)
                    .fillMaxHeight()
                    .background(Color(0xF00A0F12))
                    .padding(horizontal = 36.mpx, vertical = 42.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                Text(
                    stringResource(if (stutter) R.string.backup_offer_title_stutter else R.string.backup_offer_title),
                    style = stageText(28, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(channel.name, style = stageText(19, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.mpx))
                val sub = when {
                    result == null -> stringResource(R.string.backup_offer_searching)
                    result == Offer.Unavailable -> stringResource(R.string.backup_offer_unreachable)
                    query.isNotBlank() && searched != null && shown.isEmpty() -> stringResource(R.string.backup_offer_none)
                    query.isBlank() && ranked.isEmpty() -> stringResource(R.string.backup_offer_none)
                    else -> stringResource(R.string.backup_offer_text)
                }
                Text(sub, style = stageText(19, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 15.mpx))
                if (autoPick != null && query.isBlank() && !typing && !stutter) {
                    Text(
                        stringResource(R.string.backup_offer_auto, autoPick.name, remaining),
                        style = stageText(19, 700), color = stageAccent.accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 9.mpx),
                    )
                }
                LazyColumn(Modifier.weight(1f).padding(top = 15.mpx)) {
                    itemsIndexed(shown, key = { _, alt -> alt.url }) { i, alt ->
                        StageMenuItem(
                            text = alt.name,
                            onClick = { play(alt) },
                            icon = OwnTVIcon.PLAY,
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                if (result is Offer.Found) {
                    StageButton(
                        text = if (query.isBlank()) stringResource(R.string.backup_offer_search) else "“$query”",
                        onClick = { typing = true },
                        icon = OwnTVIcon.SEARCH,
                        height = 56.mpx,
                        textSize = 19,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.mpx)
                            .then(if (shown.isEmpty()) Modifier.focusRequester(first) else Modifier),
                    )
                }
                StageButton(
                    text = stringResource(R.string.backup_offer_wait),
                    onClick = dismiss,
                    height = 56.mpx,
                    textSize = 19,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.mpx)
                        .then(if (result !is Offer.Found) Modifier.focusRequester(first) else Modifier),
                )
                StageKeyHints(
                    listOf(
                        stringResource(R.string.common_ok) to stringResource(R.string.backup_offer_play),
                        stringResource(R.string.common_back) to stringResource(R.string.backup_offer_wait),
                    ),
                    Modifier.padding(top = 18.mpx),
                    textSize = 18,
                )
            }
        }
    }
    if (typing) {
        TextInputDialog(
            title = stringResource(R.string.backup_offer_search),
            initial = query,
            label = stringResource(R.string.backup_offer_search),
            confirmLabel = stringResource(R.string.backup_offer_search_go),
            onConfirm = { query = it.trim(); typing = false },
            onDismiss = { typing = false },
        )
    }
}

/** First wait before a channel the engines gave up on is opened again; each retry waits longer, up to [RETRY_MAX_MS]. */
private const val RETRY_FIRST_MS = 5_000L
private const val RETRY_MAX_MS = 30_000L

/**
 * 8K PRO: a device with no backup provider never gives up on a live channel. When the engines have
 * given up (ExoPlayer, then mpv, and an error on screen), the channel is opened again from the start
 * after [RETRY_FIRST_MS], then a little later each time, until it plays — so a channel that comes back
 * after a long outage is playing again without anyone touching the remote.
 */
@Composable
fun KeepTrying(liveVm: LiveViewModel, engine: PlaybackEngine, channel: ChannelEntity?) {
    val catalog: BackupCatalog = koinInject()
    if (channel == null || catalog.configured || liveVm.playingBackup) return
    LaunchedEffect(channel.id, engine) {
        var failedSince: Long? = null
        var attempts = 0
        while (true) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            if (engine.error.value != null) {
                val since = failedSince ?: now.also { failedSince = it }
                if (now - since >= (RETRY_FIRST_MS * (attempts + 1)).coerceAtMost(RETRY_MAX_MS)) {
                    attempts++
                    failedSince = null
                    liveVm.retryLive()
                }
            } else {
                failedSince = null
                if (engine.isPlaying.value && !engine.buffering.value) attempts = 0
            }
        }
    }
}

private sealed interface Offer {
    data class Found(val alternatives: List<BackupCatalog.Alternative>) : Offer
    data object Unavailable : Offer
}
