package tv.own.owntv.features.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.brand.BrandConfig
import tv.own.owntv.features.settings.SettingHelp
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.setup.WizardAction
import tv.own.owntv.features.setup.WizardFrame
import tv.own.owntv.features.setup.WizardStep
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * 8K PRO sign-in, built for the remote control.
 *
 * With an activation panel configured it opens on the device's activation code (the admin activates
 * it on the panel and the TV signs in by itself); "Sign in manually" switches to the username and
 * password form, which signs in against the default server. Without a panel only the form exists.
 *
 * The form reuses the setup wizard's frame and the settings form rows: each field is a focusable row
 * (D-pad ▲▼ between Username, Password and Login); OK opens the on-screen keyboard for that row only,
 * and its Done returns focus to the row, so the D-pad is never trapped inside a text field.
 */
@Composable
fun LoginScreen(modifier: Modifier = Modifier) {
    val vm: LoginViewModel = koinViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onShown() }
    if (ui.manual) ManualLogin(vm, ui) else ActivationCode(vm, ui)
}

@Composable
private fun ActivationCode(vm: LoginViewModel, ui: LoginUi) {
    val manualFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { manualFocus.requestFocus() }
    }
    val state = ui.activation
    val code = when (state) {
        is ActivationUi.Waiting -> state.code
        is ActivationUi.Signing -> state.code
        is ActivationUi.Disabled -> state.code
        is ActivationUi.Expired -> state.code
        is ActivationUi.BadAccount -> state.code
        ActivationUi.Registering, ActivationUi.Offline -> null
    }
    val status = when (state) {
        ActivationUi.Registering -> stringResource(R.string.activation_registering)
        is ActivationUi.Waiting -> stringResource(R.string.activation_waiting)
        is ActivationUi.Signing -> stringResource(R.string.login_checking)
        is ActivationUi.Disabled -> stringResource(R.string.activation_disabled)
        is ActivationUi.Expired -> stringResource(R.string.login_error_expired)
        is ActivationUi.BadAccount -> when (state.error) {
            LoginError.Network -> stringResource(R.string.login_error_network)
            LoginError.Expired -> stringResource(R.string.login_error_expired)
            is LoginError.Inactive -> stringResource(R.string.login_error_status, state.error.status)
            else -> stringResource(R.string.activation_bad_account)
        }
        ActivationUi.Offline -> stringResource(R.string.activation_offline)
    }
    val warn = state is ActivationUi.Disabled || state is ActivationUi.Expired ||
        state is ActivationUi.BadAccount || state is ActivationUi.Offline

    WizardFrame(
        step = WizardStep.PROFILE,
        title = stringResource(R.string.activation_title),
        sub = stringResource(R.string.activation_subtitle),
        back = null,
        next = WizardAction(stringResource(R.string.activation_manual), { vm.showManual(true) }, manualFocus),
        onBack = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 10.mpx), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .background(StageColors.Ink.copy(alpha = 0.06f), RoundedCornerShape(22.mpx))
                    .padding(horizontal = 48.mpx, vertical = 22.mpx),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = code?.let { it.chunked(3).joinToString(" ") } ?: "· · ·",
                    style = stageText(72, 800),
                    fontFamily = FontFamily.Monospace,
                    color = stageAccent.accent,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = status,
                style = stageText(20, 600),
                color = if (warn) StageColors.Danger else StageColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.mpx),
            )
        }
    }
}

@Composable
private fun ManualLogin(vm: LoginViewModel, ui: LoginUi) {
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { userFocus.requestFocus() }
    }
    // A refused attempt puts focus back where the fix is: the username when it is empty or wrong.
    LaunchedEffect(ui.error) {
        when (ui.error) {
            LoginError.Empty -> runCatching { (if (ui.username.isEmpty()) userFocus else passFocus).requestFocus() }
            LoginError.BadCredentials -> runCatching { userFocus.requestFocus() }
            else -> Unit
        }
    }

    val userLabel = stringResource(R.string.login_username)
    val passLabel = stringResource(R.string.login_password)
    val help = stringResource(R.string.login_field_help)
    val sub = when (val e = ui.error) {
        null -> if (ui.busy) stringResource(R.string.login_checking) else stringResource(R.string.login_subtitle)
        LoginError.Empty -> stringResource(R.string.login_error_empty)
        LoginError.BadCredentials -> stringResource(R.string.login_error_credentials)
        LoginError.Expired -> stringResource(R.string.login_error_expired)
        is LoginError.Inactive -> stringResource(R.string.login_error_status, e.status)
        LoginError.Network -> stringResource(R.string.login_error_network)
    }
    val backToCode = if (BrandConfig.activationEnabled) {
        WizardAction(stringResource(R.string.activation_use_code), { vm.showManual(false) })
    } else {
        null
    }

    WizardFrame(
        step = WizardStep.PROFILE,
        title = stringResource(R.string.login_title),
        sub = sub,
        back = backToCode,
        next = WizardAction(stringResource(R.string.login_button), vm::login, loginFocus, enabled = !ui.busy),
        // Back on the remote returns to the code when there is one; otherwise nothing is behind this.
        onBack = backToCode?.onClick,
    ) {
        StageFieldRow(
            icon = OwnTVIcon.PERSON,
            label = userLabel,
            value = ui.username,
            onValueChange = vm::setUsername,
            help = SettingHelp(userLabel, help),
            placeholder = stringResource(R.string.login_username_hint),
            keyboardType = KeyboardType.Email, // no autocorrect/capitalisation on most TV keyboards
            onDone = { runCatching { passFocus.requestFocus() } },
            modifier = Modifier.focusRequester(userFocus),
        )
        StageFieldRow(
            icon = OwnTVIcon.PENCIL,
            label = passLabel,
            value = ui.password,
            onValueChange = vm::setPassword,
            help = SettingHelp(passLabel, help),
            placeholder = stringResource(R.string.login_password_hint),
            password = true,
            keyboardType = KeyboardType.Password,
            // Done on the password keyboard signs in straight away — the usual TV flow.
            onDone = { runCatching { loginFocus.requestFocus() }; vm.login() },
            modifier = Modifier.focusRequester(passFocus),
        )
    }
}
