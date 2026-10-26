package com.tsonglew.quodex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tsonglew.quodex.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import com.tsonglew.quodex.update.AppUpdateScheduler

data class LoginState(val challenge: DeviceChallenge? = null, val error: String? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuodexApplication
    val state = app.repository.state
    val updates = app.updates.state
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    fun dismissError() { mutableError.value = null }
    private fun reportError() { app.scope.launch { app.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.UI_ERROR) }; mutableError.value = "操作未完成，请重试；若持续失败，请检查设备可用存储空间" }
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
            app.reconcileSync()
        }
    }
    fun refresh() { launchOperation { withContext(Dispatchers.IO) { app.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.MANUAL_REFRESH) }; app.repository.refresh(true); app.updateWidgets(); loadTokenTrend(force = true) } }
    private fun loadTokenTrend(force: Boolean = false) { launchOperation { app.repository.refreshTokenUsage(force); app.updateWidgets() } }
    fun refreshTokenUsage() { loadTokenTrend(force = true) }
    fun demo() { launchOperation { cancelLogin(); app.repository.demo(); app.reconcileSync(); app.updateWidgets() } }
    fun logout() { cancelLogin(); app.scope.launch { runUiOperation(::reportError) { app.repository.logout(); app.reconcileSync(); app.updateWidgets() } } }
    fun compactTokens(value: Boolean) { launchOperation { app.settings.compactTokens(value); app.updateWidgets() } }
    fun showRemaining(value: Boolean) { launchOperation { app.settings.showRemaining(value); app.updateWidgets() } }
    fun theme(value: String) { launchOperation { app.settings.theme(value); app.updateWidgets() } }
    fun refreshMinutes(value: Long) { launchOperation { app.settings.refreshMinutes(value); app.reconcileSync() } }
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
                mutableLogin.update { it?.copy(error = "授权已超时，请重新获取登录码") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: ApiFailure) { mutableLogin.update { it?.copy(error = failure.kind.userMessage()) } }
            catch (_: Exception) { mutableLogin.update { it?.copy(error = "连接未完成，请重试") } }
        }
    }
    fun cancelLogin() {
        loginJob?.cancel(); loginJob = null
        app.repository.cancelPendingConnection()
        mutableLogin.value = null
    }
}
