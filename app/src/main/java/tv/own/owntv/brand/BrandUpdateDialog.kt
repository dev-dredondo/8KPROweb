package tv.own.owntv.brand

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.update.updateFailureText
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageText

/**
 * 8K PRO: the automatic update, on top of whatever screen is showing. Nothing while there is no newer
 * version; otherwise the download progress, then the system installer takes over. "Cancel"/"Later" hides
 * it until the next launch.
 */
@Composable
fun BrandUpdateDialog() {
    val updater: BrandUpdater = koinInject()
    val state by updater.state.collectAsStateWithLifecycle()
    if (state is BrandUpdater.State.Idle || state is BrandUpdater.State.Installing) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(state::class) { runCatching { focus.requestFocus() } }
    val body = stageText(18, 400).copy(lineHeight = (18 * 1.45f).mpxSp)
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = updater::dismiss,
        title = stringResource(R.string.update_title),
        width = 900.mpx,
        scroll = false,
        buttons = {
            when (state) {
                is BrandUpdater.State.Downloading ->
                    StageButton(stringResource(R.string.common_cancel), onClick = updater::dismiss, height = 56.mpx, textSize = 19, modifier = Modifier.focusRequester(focus))
                is BrandUpdater.State.Available -> {
                    StageButton(stringResource(R.string.update_later), onClick = updater::dismiss, height = 56.mpx, textSize = 19)
                    StageButton(stringResource(R.string.update_now), onClick = updater::start, icon = OwnTVIcon.DOWNLOADS, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(focus))
                }
                is BrandUpdater.State.Failed -> {
                    StageButton(stringResource(R.string.settings_close), onClick = updater::dismiss, height = 56.mpx, textSize = 19)
                    StageButton(stringResource(R.string.update_try_again), onClick = updater::start, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(focus))
                }
                else -> Unit
            }
        },
    ) {
        when (val s = state) {
            is BrandUpdater.State.Downloading -> {
                Text(stringResource(R.string.update_available_version, s.release.versionName, updater.currentVersion), style = stageText(19, 600), color = StageColors.Text)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.mpx)) {
                    OwnTVSpinner(sizeDp = 28)
                    Spacer(Modifier.width(16.mpx))
                    Text(stringResource(R.string.update_downloading, s.percent), style = body, color = StageColors.Text)
                }
                Text(stringResource(R.string.update_installer), style = body, color = StageColors.Muted, modifier = Modifier.padding(top = 10.mpx))
            }
            is BrandUpdater.State.Available ->
                Text(stringResource(R.string.update_available_version, s.release.versionName, updater.currentVersion), style = stageText(19, 600), color = StageColors.Text)
            is BrandUpdater.State.Failed -> Text(updateFailureText(s.failure), style = body, color = StageColors.Muted)
            else -> Unit
        }
        val notes = when (val s = state) {
            is BrandUpdater.State.Downloading -> s.release.notes
            is BrandUpdater.State.Available -> s.release.notes
            else -> ""
        }
        if (notes.isNotBlank()) {
            Text(notes, style = stageText(17, 400).copy(lineHeight = (17 * 1.5f).mpxSp), color = StageColors.Muted, modifier = Modifier.padding(top = 18.mpx))
        }
    }
}
