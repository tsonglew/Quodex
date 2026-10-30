package com.tsonglew.quodex

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.launcher.PinIconResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import com.tsonglew.quodex.update.AppUpdateScheduler

data class LoginState(val challenge: DeviceChallenge? = null, val error: UiText? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuodexApplication
    val state = app.repository.state
    val updates = app.updates.state
    val desktopIconAvailability = app.desktopIcon.availability
    private val mutableIconMessage = MutableStateFlow<UiText?>(null)
    val desktopIconMessage = mutableIconMessage.asStateFlow()
    private val mutableHeatmapLaunch = MutableStateFlow<String?>(null)
    val heatmapLaunch = mutableHeatmapLaunch.asStateFlow()
    fun openTokenHeatmap() { mutableHeatmapLaunch.value = java.util.UUID.randomUUID().toString() }
    private val mutableError = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = mutableError
    fun dismissError() { mutableError.value = null }
    private fun reportError() { app.scope.launch { app.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.UI_ERROR) }; mutableError.value = UiText(R.string.the_operation_could_not_be_completed_try) }
    private fun launchOperation(block: suspend () -> Unit) = viewModelScope.launch {
        runUiOperation(::reportError, block)
    }
    val preferences = app.settings.flow.retrySettings(::reportError).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesState())
    private val mutableLogin = MutableStateFlow<LoginState?>(null)
    val login: StateFlow<LoginState?> = mutableLogin
    private var loginJob: Job? = null

    init { onForeground() }
    fun onForeground() {
        viewModelScope.launch { app.updates.check() }
        launchOperation {
            withContext(Dispatchers.IO) { app.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.FOREGROUND) }
            app.repository.initialize()
            if (state.value.connected) { app.repository.refresh(); app.updateWidgets(); loadTokenTrend() }
            app.desktopIcon.update()
            app.reconcileSync()
        }
    }
    fun refresh() { launchOperation { withContext(Dispatchers.IO) { app.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.MANUAL_REFRESH) }; app.repository.refresh(true); app.updateWidgets(); loadTokenTrend(force = true) } }
    private fun loadTokenTrend(force: Boolean = false) { launchOperation { app.repository.refreshTokenUsage(force); app.updateWidgets() } }
    fun refreshTokenUsage() { loadTokenTrend(force = true) }
    fun demo() { launchOperation { cancelLogin(); app.repository.demo(); app.reconcileSync(); app.updateWidgets() } }
    fun logout() { cancelLogin(); app.scope.launch { runUiOperation(::reportError) { app.repository.logout(); app.reconcileSync(); app.updateWidgets() } } }
    fun compactTokens(value: Boolean) { launchOperation { app.settings.compactTokens(value); app.updateWidgets() } }
    fun tokenChartStyle(value: TokenChartStyle) { launchOperation { app.settings.tokenChartStyle(value) } }
    fun showRemaining(value: Boolean) { launchOperation { app.settings.showRemaining(value); app.updateWidgets() } }
    fun theme(value: String) { launchOperation { app.settings.theme(value); app.updateWidgets() } }
    fun refreshMinutes(value: Long) { launchOperation { app.settings.refreshMinutes(value); app.reconcileSync() } }
    fun desktopIconEnabled(value: Boolean) {
        launchOperation { app.settings.desktopIconEnabled(value); app.desktopIcon.update(); app.reconcileSync() }
    }
    fun desktopIconWindow(value: String) {
        launchOperation { app.settings.desktopIconWindow(value); app.desktopIcon.update() }
    }
    fun addDesktopIcon() {
        launchOperation {
            mutableIconMessage.value = when (app.desktopIcon.requestPin()) {
                PinIconResult.REQUESTED -> UiText(R.string.confirm_adding_the_icon_in_the_home)
                PinIconResult.UPDATED -> UiText(R.string.home_screen_quota_icon_updated)
                PinIconResult.UNSUPPORTED -> UiText(R.string.your_launcher_does_not_support_this_icon)
                PinIconResult.FAILED -> UiText(R.string.the_home_screen_icon_could_not_be)
            }
            app.reconcileSync()
        }
    }
    fun checkForUpdates() { viewModelScope.launch { app.updates.check(force = true) } }
    fun updateNotificationPermissionChanged() { viewModelScope.launch { app.updates.check() } }
    fun automaticUpdates(enabled: Boolean) { launchOperation {
        app.updates.setEnabled(enabled)
        AppUpdateScheduler.reconcile(app, enabled)
        if (enabled) app.updates.check()
    } }

    fun startLogin() {
        cancelLogin()
        mutableLogin.value = LoginState()
        loginJob = viewModelScope.launch {
            try {
                val challenge = app.api.challenge()
                mutableLogin.value = LoginState(challenge)
                while (Instant.now().epochSecond < challenge.expiresAt) {
                    delay(challenge.intervalSeconds * 1000)
                    val grant = app.api.poll(challenge) ?: continue
                    val session = app.api.exchange(grant)
                    app.repository.connect(session)
                    app.reconcileSync(); app.updateWidgets()
                    mutableLogin.value = null
                    loadTokenTrend(force = true)
                    return@launch
                }
                mutableLogin.update { it?.copy(error = UiText(R.string.authorization_timed_out_get_a_new_sign)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: ApiFailure) { mutableLogin.update { it?.copy(error = failure.kind.userMessage()) } }
            catch (_: Exception) { mutableLogin.update { it?.copy(error = UiText(R.string.connection_was_not_completed_try_again)) } }
        }
    }
    fun cancelLogin() {
        loginJob?.cancel(); loginJob = null
        app.repository.cancelPendingConnection()
        mutableLogin.value = null
    }
}
