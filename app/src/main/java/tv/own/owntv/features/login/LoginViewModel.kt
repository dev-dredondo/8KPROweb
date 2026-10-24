package tv.own.owntv.features.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.brand.BrandConfig

/** Why the last attempt was refused; null while there is nothing to report. */
sealed interface LoginError {
    data object Empty : LoginError
    data object BadCredentials : LoginError
    data object Expired : LoginError
    data class Inactive(val status: String) : LoginError
    data object Network : LoginError
}

/** The activation-code side of the login screen. */
sealed interface ActivationUi {
    /** Asking the panel for this device's code. */
    data object Registering : ActivationUi
    /** Code on screen, waiting for the admin to activate it. */
    data class Waiting(val code: String) : ActivationUi
    /** Activated: checking the account against the Xtream server. */
    data class Signing(val code: String) : ActivationUi
    data class Disabled(val code: String) : ActivationUi
    data class Expired(val code: String) : ActivationUi
    /** The panel handed over an account the Xtream server refused. */
    data class BadAccount(val code: String, val error: LoginError) : ActivationUi
    /** The panel cannot be reached; retried automatically. */
    data object Offline : ActivationUi
}

data class LoginUi(
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: LoginError? = null,
    /** Manual form on screen instead of the activation code. Always true without a panel. */
    val manual: Boolean = !BrandConfig.activationEnabled,
    val activation: ActivationUi = ActivationUi.Registering,
)

/**
 * 8K PRO login: activation by code through the panel, or (fallback) username and password typed on
 * the TV against the default server. A successful sign-in is saved to [SessionStore]; MainActivity
 * observes the store and moves on by itself.
 */
class LoginViewModel(
    private val authenticator: XtreamAuthenticator,
    private val sessionStore: SessionStore,
    private val account: BrandAccount,
    private val panel: PanelClient,
) : ViewModel() {

    private val _ui = MutableStateFlow(LoginUi())
    val ui: StateFlow<LoginUi> = _ui.asStateFlow()

    private var polling: Job? = null

    /**
     * Called whenever the login screen appears. The ViewModel lives as long as the activity, so after
     * a sign-out (or the panel disabling this TV) the screen comes back to a finished poll: restart it.
     */
    fun onShown() {
        if (BrandConfig.activationEnabled && polling?.isActive != true) {
            _ui.update { it.copy(activation = ActivationUi.Registering, busy = false) }
            startActivation()
        }
    }

    // ------------------------------------------------------------- manual login

    fun setUsername(value: String) = _ui.update { it.copy(username = value.trim(), error = null) }

    fun setPassword(value: String) = _ui.update { it.copy(password = value, error = null) }

    fun showManual(manual: Boolean) {
        if (!BrandConfig.activationEnabled) return
        _ui.update { it.copy(manual = manual, error = null) }
    }

    fun login() {
        val current = _ui.value
        if (current.busy) return
        val user = current.username.trim()
        val pass = current.password
        if (user.isEmpty() || pass.isEmpty()) {
            _ui.update { it.copy(error = LoginError.Empty) }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val server = BrandConfig.serverUrl
            val error = signIn(server, user, pass, viaPanel = false)
            _ui.update { it.copy(busy = false, error = error, password = if (error == null) "" else it.password) }
        }
    }

    // ------------------------------------------------------------- activation by code

    /** Registers with the panel, then checks every few seconds until the admin activates this TV. */
    private fun startActivation() {
        polling?.cancel()
        polling = viewModelScope.launch {
            var identity = sessionStore.identity()
            var code: String? = null
            while (isActive) {
                if (code == null) {
                    when (val r = panel.register(identity)) {
                        is PanelClient.RegisterResult.Ok -> code = r.code
                        PanelClient.RegisterResult.IdentityTaken -> { identity = sessionStore.resetIdentity(); continue }
                        PanelClient.RegisterResult.Failed -> setActivation(ActivationUi.Offline)
                    }
                    if (code == null) { delay(RETRY_MS); continue }
                    setActivation(ActivationUi.Waiting(code))
                }
                when (val status = panel.config(identity)) {
                    is PanelStatus.Pending -> setActivation(ActivationUi.Waiting(status.code))
                    is PanelStatus.Disabled -> setActivation(ActivationUi.Disabled(status.code))
                    is PanelStatus.Expired -> setActivation(ActivationUi.Expired(status.code))
                    PanelStatus.Unknown -> { code = null; continue } // deleted on the panel: new code
                    PanelStatus.Unreachable -> setActivation(ActivationUi.Offline)
                    is PanelStatus.Active -> {
                        setActivation(ActivationUi.Signing(status.code))
                        val error = signIn(status.serverUrl, status.username, status.password, viaPanel = true)
                        if (error == null) return@launch // signed in: MainActivity leaves this screen
                        setActivation(ActivationUi.BadAccount(status.code, error))
                        delay(BAD_ACCOUNT_RETRY_MS) // the admin may fix the account on the panel
                        continue
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private fun setActivation(state: ActivationUi) = _ui.update { it.copy(activation = state) }

    // ------------------------------------------------------------- shared

    /** Checks the account on [server]; on success points the device at it and saves the session. */
    private suspend fun signIn(server: String, user: String, pass: String, viaPanel: Boolean): LoginError? =
        when (val result = authenticator.login(user, pass, server)) {
            is LoginResult.Ok -> {
                val previous = sessionStore.lastServer() ?: BrandConfig.sessionServerUrl
                account.apply(server, user, pass, previous)
                sessionStore.save(Session(user, pass, server, viaPanel))
                polling?.takeIf { !viaPanel }?.cancel()
                null
            }
            LoginResult.BadCredentials -> LoginError.BadCredentials
            LoginResult.Expired -> LoginError.Expired
            is LoginResult.Inactive -> LoginError.Inactive(result.status)
            LoginResult.Unreachable -> LoginError.Network
        }

    private companion object {
        const val POLL_MS = 5_000L
        const val RETRY_MS = 10_000L
        const val BAD_ACCOUNT_RETRY_MS = 30_000L
    }
}
