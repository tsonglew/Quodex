package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.data.*

/** The dashboard's quota area. Data and refresh behaviour remain owned by the repository. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuotaOverview(state: AppState, prefs: PreferencesState, now: Long, onReconnect: () -> Unit) {
    val texts = localTextResources()
    val snapshot = state.snapshot ?: return
    val colors = MaterialTheme.colorScheme
    var showUpdateInfo by rememberSaveable { mutableStateOf(false) }
    // Review and additional limits must not stand in for the account's main quota.
    val primary = snapshot.windows.firstOrNull { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
    val secondary = snapshot.windows.filter { it !== primary }
    val stale = !state.demo && now - snapshot.fetchedAt > prefs.refreshMinutes * 120

    Column(Modifier.fillMaxWidth().testTag("quota-overview")) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(texts(R.string.quota_overview), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            if (state.demo) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(texts(R.string.sample_data_not_your_actual_quota), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
                        Icon(Icons.Outlined.Code, null, Modifier.padding(14.dp).size(26.dp), tint = colors.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Codex", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            snapshot.plan?.let { plan ->
                                Surface(color = colors.surface, shape = RoundedCornerShape(50)) {
                                    Text(plan.uppercase(), Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                                }
                            }
                        }
                        Text(when {
                            state.demo -> texts(R.string.demo_mode)
                            state.failure != null -> texts(R.string.showing_last_quota)
                            state.connected -> texts(R.string.account_connected)
                            else -> texts(R.string.not_connected)
                        }, fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
                snapshot.quotaNotice(texts)?.let { notice ->
                    QuotaInlineNotice(notice.title, notice.description,
                        important = snapshot.limitReached == true || snapshot.allowed == false)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Equal-width dashboard tiles at normal phone widths; stack for narrow windows or large text.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val windows = listOfNotNull(primary) + secondary
            val columns = if (windows.size > 1 && maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.15f) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                windows.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { window -> key(window.id) {
                            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                                color = colors.surface) {
                                Column(Modifier.padding(18.dp)) {
                                    if (window === primary) PrimaryQuotaWindow(window, prefs.showRemaining, now)
                                    else SecondaryQuotaWindow(window, prefs.showRemaining, now)
                                }
                            }
                        } }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Column(Modifier.padding(top = 13.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(texts(if (state.demo) R.string.demo_updated_at else R.string.quota_updated_at, absoluteTime(snapshot.fetchedAt, true)),
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
                snapshot.resetCredits?.let {
                    Text(texts(R.string.value_resets_available, it), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
            TextButton(onClick = { showUpdateInfo = true }, modifier = Modifier.testTag("quota-update-info"),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(texts(R.string.update_details), fontSize = 12.sp)
            }
        }
        if (state.failure != null || stale) {
            Spacer(Modifier.height(8.dp))
            QuotaInlineNotice(state.failure?.userMessage()?.resolve(texts) ?: texts(R.string.older_data), texts(R.string.showing_the_last_successfully_retrieved_quota),
                important = state.failure != null, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.failure == FailureKind.AUTH || state.failure == FailureKind.STORAGE) {
                TextButton(onClick = onReconnect, modifier = Modifier.testTag("quota-reconnect"),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text(texts(R.string.reconnect)) }
            }
        }
    }
    if (showUpdateInfo) AlertDialog(onDismissRequest = { showUpdateInfo = false },
        title = { Text(texts(R.string.about_quota_updates)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("quota-update-info-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.demo) texts(R.string.this_is_a_preview_not_your_actual)
                    else texts(R.string.target_background_interval_value_minutes_battery_settings, prefs.refreshMinutes))
                Text(if (state.demo) texts(R.string.connect_an_account_to_see_your_actual)
                    else texts(R.string.refresh_manually_from_the_top_right_corner))
            }
        }, confirmButton = {
            TextButton(onClick = { showUpdateInfo = false },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text(texts(R.string.got_it)) }
        })
}

@Composable
private fun PrimaryQuotaWindow(window: QuotaWindow, remaining: Boolean, now: Long) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    val value = window.displayedPercent(remaining)
    Column(Modifier.fillMaxWidth().testTag("quota-primary")) {
        Text(window.displayName(texts), fontSize = 15.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Leave room for three digits, the percent sign and large system fonts.
            val numberSize = (maxWidth.value / LocalDensity.current.fontScale / 2.8f).coerceIn(28f, 48f).sp
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(window.percentageLabel(remaining, texts), fontSize = if (value == null) 24.sp else numberSize,
                    lineHeight = if (value == null) 32.sp else numberSize * 1.12f,
                    fontWeight = FontWeight.SemiBold, color = colors.primary, letterSpacing = if (value == null) 0.sp else (-1).sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("quota-primary-value"))
                if (value != null) Text("%", fontSize = 24.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline())
            }
        }
        if (value != null) {
            Text(if (remaining) texts(R.string.remaining_quota) else texts(R.string.used_quota), fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            QuotaMeter(window, remaining, height = 8)
        } else {
            Spacer(Modifier.height(8.dp))
            UnknownQuotaExplanation()
        }
        Spacer(Modifier.height(12.dp))
        ResetTime(window, now)
    }
}

@Composable
private fun SecondaryQuotaWindow(window: QuotaWindow, remaining: Boolean, now: Long) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    val value = window.displayedPercent(remaining)
    Column(Modifier.fillMaxWidth()) {
        Text(window.displayName(texts), fontSize = 15.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val numberSize = (maxWidth.value / LocalDensity.current.fontScale / 2.8f).coerceIn(28f, 48f).sp
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(window.percentageLabel(remaining, texts), fontSize = if (value == null) 24.sp else numberSize,
                    lineHeight = if (value == null) 32.sp else numberSize * 1.12f,
                    fontWeight = FontWeight.SemiBold, color = colors.primary,
                    letterSpacing = if (value == null) 0.sp else (-1).sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                if (value != null) Text("%", fontSize = 24.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline())
            }
        }
        if (value != null) {
            Text(if (remaining) texts(R.string.remaining_quota) else texts(R.string.used_quota), fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            QuotaMeter(window, remaining, height = 8)
        } else {
            Spacer(Modifier.height(8.dp))
            UnknownQuotaExplanation()
        }
        Spacer(Modifier.height(12.dp))
        ResetTime(window, now)
    }
}

@Composable
private fun ResetTime(window: QuotaWindow, now: Long) {
    val texts = localTextResources()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(resetLabel(window, texts, now), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnknownQuotaExplanation() {
    val texts = localTextResources()
    Text(texts(R.string.the_service_has_not_provided_valid_usage), fontSize = 12.sp, lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun QuotaMeter(window: QuotaWindow, remaining: Boolean, height: Int) {
    val texts = localTextResources()
    val value = window.displayedPercent(remaining) ?: return
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = remember(window.id) { Animatable(0f) }
    val target = (value / 100).toFloat().coerceIn(0f, 1f)
    LaunchedEffect(progress, target) {
        progress.animateTo(target, tween(520, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxWidth().height(height.dp).testTag("quota-meter-${window.id}").semantics {
        contentDescription = texts(R.string.quota_percentage_description, window.displayName(texts), if (remaining) texts(R.string.remaining) else texts(R.string.used), window.percentageLabel(remaining, texts))
    }) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        // The spoken value above is always the actual quota; only its visual fill interpolates.
        if (progress.value > 0f) drawRoundRect(fill,
            size = Size(size.width * progress.value, size.height), cornerRadius = radius)
    }
}

@Composable
private fun QuotaInlineNotice(title: String, description: String, modifier: Modifier = Modifier, important: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.padding(top = 2.dp).size(16.dp),
            tint = if (important) colors.error else colors.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                color = if (important) colors.error else colors.onSurface)
            Text(description, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        }
    }
}
