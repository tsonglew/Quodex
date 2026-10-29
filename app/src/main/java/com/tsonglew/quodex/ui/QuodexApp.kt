package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.LoginState
import com.tsonglew.quodex.MainViewModel
import com.tsonglew.quodex.R
import com.tsonglew.quodex.quotaApp
import com.tsonglew.quodex.data.*
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.tsonglew.quodex.diagnostics.DiagnosticEvent

@Composable
fun QuodexApp(model: MainViewModel) {
    val texts = localTextResources()
    val timeRevision by LocalContext.current.quotaApp.wallClockRevision.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val login by model.login.collectAsStateWithLifecycle()
    val operationError by model.error.collectAsStateWithLifecycle()
    val updates by model.updates.collectAsStateWithLifecycle()
    val clock by produceState(Instant.now().epochSecond, timeRevision) {
        while (true) { value = Instant.now().epochSecond; delay(30_000) }
    }
    var tab by rememberSaveable { mutableStateOf("quota") }
    val diagnosticApp = LocalContext.current.quotaApp
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(tab, state, prefs, configuration) {
        withContext(Dispatchers.IO) {
            diagnosticApp.diagnostics.record(DiagnosticEvent.UI_STATE,
                listOf("quota", "widgets", "settings").indexOf(tab).toLong(),
                if (state.initialized) 1 else 0, if (state.connected) 1 else 0,
                if (state.syncing) 1 else 0, if (state.snapshot != null) 1 else 0,
                state.failure?.ordinal?.toLong() ?: -1,
                configuration.screenWidthDp.toLong(), configuration.screenHeightDp.toLong(),
                (configuration.fontScale * 100).toLong(), configuration.uiMode.toLong(),
                if (prefs.showRemaining) 1 else 0, listOf("system", "light", "dark").indexOf(prefs.theme).toLong())
        }
    }
    val pageStates = rememberSaveableStateHolder()
    val destinations = listOf(Triple("quota", texts(R.string.quota), Icons.Outlined.Dashboard),
        Triple("widgets", texts(R.string.widgets), Icons.Outlined.Widgets), Triple("settings", texts(R.string.settings), Icons.Outlined.Settings))
    QuotaTheme(prefs.theme) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp && maxHeight >= 480.dp
            var dockHeight by remember { mutableStateOf(100.dp) }
            val density = LocalDensity.current
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal), containerColor = MaterialTheme.colorScheme.background, topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .heightIn(min = 68.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Quodex", Modifier.weight(1f), fontSize = 24.sp, letterSpacing = (-0.6).sp, fontWeight = FontWeight.Bold)
                        if (tab == "quota" && state.snapshot != null) FilledTonalIconButton(onClick = model::refresh,
                            enabled = !state.syncing && !state.demo, modifier = Modifier.testTag("refresh-button")) {
                            if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = texts(R.string.refreshing_quota) }, strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Refresh, texts(R.string.refresh_quota))
                        }
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    Row(Modifier.fillMaxSize().padding(bottom = if (useRail) 0.dp else dockHeight)) {
                        if (useRail) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surface,
                            windowInsets = WindowInsets(0)) {
                            destinations.forEach { (id, label, icon) ->
                                NavigationRailItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, null) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                                    label = { Text(label) }, modifier = Modifier.testTag("tab-$id"))
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            // Keep each destination's render nodes separate as well as its saved state.
                            key(tab) { pageStates.SaveableStateProvider(tab) {
                                Column(Modifier.widthIn(max = 640.dp).fillMaxSize()
                                    .revealOnEnter(if (tab == "quota") state.snapshot != null else Unit).testTag("page-$tab")
                                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                                    Spacer(Modifier.height(16.dp))
                                    if (tab != "settings") updates.saved.available?.let { AvailableUpdateNotice(it) }
                                    when (tab) {
                                        "quota" -> if (!state.initialized) CircularProgressIndicator() else key(timeRevision) { Dashboard(state, prefs, model, clock) }
                                        "widgets" -> WidgetPage(state, prefs)
                                        else -> SettingsPage(state, prefs, model, clock)
                                    }
                                    Spacer(Modifier.height(28.dp))
                                }
                            } }
                        }
                    }
                    if (!useRail) FloatingNavigation(destinations, tab, { tab = it },
                        Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight = with(density) { it.height.toDp() } })
                }
            }
        }
        InAppUpdateDialog()
        operationError?.let { message ->
            AlertDialog(onDismissRequest = model::dismissError,
                title = { Text(texts(R.string.operation_incomplete)) }, text = { Text(message.resolve(texts)) },
                confirmButton = { TextButton(onClick = model::dismissError) { Text(texts(R.string.got_it)) } })
        }
        login?.let { LoginDialog(it, model::cancelLogin, model::startLogin) }
    }
}

@Composable
fun BrandMark(size: Int = 40) {
    Image(
        painter = painterResource(R.drawable.ic_quodex),
        contentDescription = null,
        modifier = Modifier.clearAndSetSemantics { }.size(size.dp),
    )
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
    Spacer(Modifier.height(24.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dashboard(state: AppState, prefs: PreferencesState, model: MainViewModel, now: Long) {
    val texts = localTextResources()
    if (state.snapshot == null) {
        PageTitle(texts(R.string.keep_track_of_your_quota), texts(R.string.see_your_remaining_codex_quota_and_reset))
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp)); Text("Codex", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(texts(R.string.connect_your_existing_account_to_view_subscription), lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.failure?.let { Text(it.userMessage().resolve(texts), color = MaterialTheme.colorScheme.error) }
                ConnectButton(if (state.connected) texts(R.string.reconnect_codex) else texts(R.string.connect_codex), model::startLogin,
                    modifier = Modifier.fillMaxWidth().testTag("connect-button"))
                TextButton(onClick = model::demo, modifier = Modifier.fillMaxWidth().testTag("demo-button")) { Text(texts(R.string.explore_a_demo)) }
            }
        }
        Spacer(Modifier.height(24.dp))
        DetailRow(Icons.Outlined.Widgets, texts(R.string.visible_on_your_home_screen), texts(R.string.compact_and_wide_layouts_that_adapt_to))
        DetailRow(Icons.Outlined.Lock, texts(R.string.credentials_stay_on_your_device), texts(R.string.no_quodex_account_required))
        DetailRow(Icons.Outlined.Schedule, texts(R.string.know_how_fresh_your_data_is), texts(R.string.background_updates_may_be_delayed_refresh_manually))
        return
    }
    Column(Modifier.testTag("dashboard")) {
        QuotaOverview(state, prefs, now, onReconnect = model::startLogin)
        Spacer(Modifier.height(16.dp))
        TokenUsageCard(state.snapshot.tokenUsage, state.tokenLoading, state.tokenFailure, state.demo, model::refreshTokenUsage, prefs.compactTokens, model::compactTokens)
        if (state.demo) {
            Spacer(Modifier.height(18.dp))
            ConnectButton(texts(R.string.connect_my_codex_account), model::startLogin, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun QuotaStatusNotice(snapshot: UsageSnapshot) {
    val texts = localTextResources()
    snapshot.quotaNotice(texts)?.let { notice ->
        Notice(notice.title, notice.description, Icons.Outlined.Info)
        if (snapshot.windows.isNotEmpty()) Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun QuotaWindowCard(window: QuotaWindow, remaining: Boolean, compact: Boolean = false, now: Long = Instant.now().epochSecond) {
    val texts = localTextResources()
    val value = window.displayedPercent(remaining)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth / LocalDensity.current.fontScale < 250.dp
        val details: @Composable () -> Unit = {
            Column {
                Text(window.displayName(texts), fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(resetLabel(window, texts, now), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val percentage: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(window.percentageLabel(remaining, texts),
                    fontFamily = if (value == null) FontFamily.Default else FontFamily.Monospace,
                    fontSize = if (value == null) 16.sp else if (compact) 32.sp else 40.sp,
                    fontWeight = FontWeight.Normal, letterSpacing = if (value == null) 0.sp else (-1).sp,
                    modifier = Modifier.alignByBaseline())
                if (value != null) Text("%", fontSize = 16.sp, modifier = Modifier.alignByBaseline(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { details(); percentage() }
        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { details() }
            percentage()
        }
    }
    Spacer(Modifier.height(12.dp))
    if (value == null) {
        Text(texts(R.string.the_service_has_not_provided_valid_usage), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        SegmentedBar(value, texts(R.string.quota_percentage_description, window.displayName(texts), if (remaining) texts(R.string.remaining) else texts(R.string.used), window.percentageLabel(remaining, texts)))
        Spacer(Modifier.height(6.dp))
        Text(if (remaining) texts(R.string.remaining_quota) else texts(R.string.used_quota), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SegmentedBar(percent: Double?, description: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(17.dp).semantics { contentDescription = description }) {
        val count = 40
        val gap = 3.dp.toPx()
        val width = (size.width - gap * (count - 1)) / count
        repeat(count) { i ->
            drawRoundRect(if (percent != null && i < percent / 100 * count) fill else track,
                Offset(i * (width + gap), 0f), Size(width.coerceAtLeast(1f), size.height), CornerRadius(1.dp.toPx()))
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Notice(title: String, subtitle: String, icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp)); Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp)); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LoginDialog(login: LoginState, cancel: () -> Unit, retry: () -> Unit) {
    val texts = localTextResources()
    val context = LocalContext.current
    AlertDialog(onDismissRequest = cancel, title = { Text(texts(R.string.connect_codex)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(texts(R.string.complete_device_sign_in_on_openai_s), lineHeight = 22.sp)
            Text(texts(R.string.first_enable_device_code_sign_in_in), fontSize = 12.sp,
                lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (login.error != null) Text(login.error.resolve(texts), color = MaterialTheme.colorScheme.error)
            else if (login.challenge == null) CircularProgressIndicator(Modifier.size(24.dp))
            else {
                Text(texts(R.string.enter_this_sign_in_code), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(login.challenge.userCode, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                Text(texts(R.string.waiting_for_authorization_valid_for_15_minutes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(texts(R.string.device_sign_in_and_quota_access_use), fontSize = 12.sp,
                lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DiagnosticEntry()
    PrivacyNoticeEntry()
        }
    }, confirmButton = {
        if (login.error != null) TextButton(onClick = retry) { Text(texts(R.string.get_a_new_code)) }
        else TextButton(enabled = login.challenge != null, onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CodexApi.VERIFICATION_URL)))
        }) { Text(texts(R.string.open_openai_sign_in)) }
    }, dismissButton = { TextButton(onClick = cancel) { Text(texts(R.string.cancel)) } })
}

@Composable
internal fun DiagnosticEntry(compact: Boolean = false) {
    val texts = localTextResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Text(texts(R.string.troubleshooting), fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = if (compact) 0.dp else 18.dp))
    Text(texts(R.string.the_device_keeps_the_latest_200_events),
        fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp))
    TextButton(onClick = {
        // Dialog state must return to Android's main dispatcher, including
        // when a Compose test supplies an unconfined effect interceptor.
        scope.launch(Dispatchers.Main.immediate) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = context.quotaApp
                    val power = context.getSystemService(android.os.PowerManager::class.java)
                    val activity = context.getSystemService(android.app.ActivityManager::class.java)
                    val network = context.getSystemService(android.net.ConnectivityManager::class.java)
                    val caps = network.getNetworkCapabilities(network.activeNetwork)
                    "Quodex ${com.tsonglew.quodex.BuildConfig.VERSION_NAME} diagnostic schema=1\n" +
                        "Android SDK=${android.os.Build.VERSION.SDK_INT}\n" +
                        "powerSave=${power.isPowerSaveMode} idle=${power.isDeviceIdleMode} batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)}\n" +
                        "backgroundRestricted=${activity.isBackgroundRestricted}\n" +
                        "networkValidated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}\n" +
                        com.tsonglew.quodex.sync.SyncScheduler.diagnosticReport(context) +
                        app.diagnostics.report()
                }
            }
            result.onSuccess { report = it }.onFailure { failed = true }
        }
    }, modifier = Modifier.testTag("diagnostics-preview")) { Text(texts(R.string.preview_diagnostic_report)) }
    TextButton(onClick = { scope.launch(Dispatchers.Main.immediate) { val result = withContext(Dispatchers.IO) { runCatching { context.quotaApp.diagnostics.clear() } }; result.onFailure { failed = true } } }, modifier = Modifier.testTag("diagnostics-clear")) { Text(texts(R.string.clear_diagnostic_records)) }
    report?.let { text ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text(texts(R.string.diagnostic_report)) },
            text = { SelectionContainer { Text(text, fontSize = 12.sp, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("diagnostics-report")) } },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, texts(R.string.share_diagnostic_report))) }.onFailure { failed = true }
            }) { Text(texts(R.string.share)) } },
            dismissButton = { TextButton(onClick = { report = null }, modifier = Modifier.testTag("diagnostics-close")) { Text(texts(R.string.close)) } })
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, title = { Text(texts(R.string.diagnostic_action_incomplete)) },
        text = { Text(texts(R.string.check_available_storage_or_the_sharing_app)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(texts(R.string.got_it)) } })
}
