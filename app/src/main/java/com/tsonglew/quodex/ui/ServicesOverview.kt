package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.MainViewModel
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.subscriptions.*

@Composable
internal fun ServicesOverview(state: AppState, prefs: PreferencesState, model: MainViewModel, now: Long,
    onOpen: (String) -> Unit, onAccounts: () -> Unit) {
    val texts = localTextResources()
    val saved by model.subscriptions.collectAsStateWithLifecycle()
    val initialized by model.subscriptionsInitialized.collectAsStateWithLifecycle()
    val storageUnavailable by model.subscriptionStorageUnavailable.collectAsStateWithLifecycle()
    val providers = SubscriptionProvider.entries.filter { state.demo || saved.getValue(it).connected }
    val hasCodex = state.snapshot != null || state.connected
    Column(Modifier.fillMaxWidth().testTag("dashboard"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(texts(R.string.nav_overview), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text(texts(if (state.demo) R.string.sample_data_not_your_actual_quota else R.string.overview_intro),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.demo && !initialized && !storageUnavailable) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("overview-loading"))
        }
        if (storageUnavailable && !state.demo) {
            Text(texts(R.string.subscription_storage_unreadable), color = MaterialTheme.colorScheme.error)
        }
        if (!hasCodex && providers.isEmpty() && (initialized || storageUnavailable)) {
            GlassPanel(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!storageUnavailable) Text(texts(R.string.overview_empty), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onAccounts, modifier = Modifier.fillMaxWidth().testTag("connect-service")) {
                        Text(texts(if (storageUnavailable) R.string.overview_manage else R.string.overview_add))
                    }
                    ConnectButton(texts(R.string.connect_codex), model::startLogin,
                        modifier = Modifier.fillMaxWidth().testTag("connect-button"))
                    TextButton(onClick = model::demo, modifier = Modifier.fillMaxWidth().testTag("demo-button")) {
                        Text(texts(R.string.explore_a_demo))
                    }
                }
            }
        }
        if (hasCodex) {
            val snapshot = state.snapshot
            ServiceSummary("Codex", snapshot?.plan.orEmpty(), "overview-codex", { onOpen("codex") }) {
                val windows = snapshot?.windows.orEmpty().filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
                windows.take(2).forEach { window ->
                    SummaryMetric(window.displayName(texts), window.percentageLabel(prefs.showRemaining, texts) +
                        if (window.usedPercent != null) "%" else "",
                        texts(if (prefs.showRemaining) R.string.remaining_quota else R.string.used_quota) + " · " + resetLabel(window, texts, now))
                }
                if (windows.isEmpty()) Text(snapshot?.quotaNotice(texts)?.title ?: texts(R.string.overview_no_data))
                snapshot?.quotaNotice(texts)?.takeIf { windows.isNotEmpty() }?.let {
                    Text(it.title, color = if (snapshot.allowed == false || snapshot.limitReached == true)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.failure?.let { Text(it.userMessage().resolve(texts), color = MaterialTheme.colorScheme.error) }
                snapshot?.let {
                    Text(texts(R.string.subscription_updated, absoluteTime(it.fetchedAt, true)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.demo && now - it.fetchedAt > prefs.refreshMinutes * 120) {
                        Text(texts(R.string.overview_cached), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        providers.forEach { provider ->
            val account = if (state.demo) SubscriptionState(snapshot = sampleSubscription(provider, now)) else saved.getValue(provider)
            ServiceSummary(provider.displayName, account.snapshot?.plan.orEmpty(), "overview-${provider.name}",
                { onOpen("provider:${provider.name}") }) {
                val snapshot = account.snapshot
                // Currency pools stay separate. Do not add balances across services or currencies.
                val metrics = snapshot?.overviewMetrics().orEmpty()
                metrics.forEach { metric ->
                    SummaryMetric(texts(metric.kind.labelResource()), metric.formattedValue(texts),
                        metric.resetsAt?.let { if (it <= now) texts(R.string.reset_unconfirmed_refresh_to_check)
                            else texts(R.string.resets_value, absoluteTime(it, true)) })
                }
                if (metrics.isEmpty()) Text(texts(R.string.overview_no_data))
                if (snapshot?.available == false) Text(texts(R.string.subscription_api_unavailable), color = MaterialTheme.colorScheme.error)
                account.failure?.let {
                    Text(if (it == FailureKind.LIMITED) texts(R.string.subscription_error_limited, absoluteTime(account.retryAt, true))
                        else texts(it.subscriptionErrorResource()), color = MaterialTheme.colorScheme.error)
                }
                snapshot?.let { Text(texts(R.string.subscription_updated, absoluteTime(it.fetchedAt, true)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (account.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        TextButton(onClick = onAccounts, modifier = Modifier.fillMaxWidth().testTag("overview-accounts")) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(texts(R.string.overview_manage))
        }
    }
}

@Composable
private fun ServiceSummary(name: String, plan: String, tag: String, onOpen: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag(tag)) {
        Column {
            NavigationRow(name, plan, onOpen, Modifier.testTag("$tag-open"))
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryMetric(label: String, value: String, detail: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val amountWidth = maxWidth * 0.65f
            val name: @Composable () -> Unit = { Text(label, style = MaterialTheme.typography.bodyMedium) }
            val amount: @Composable () -> Unit = {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (maxWidth / LocalDensity.current.fontScale < 240.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { name(); amount() }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { name() }
                Box(Modifier.widthIn(max = amountWidth)) { amount() }
            }
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

internal fun SubscriptionSnapshot.overviewMetrics(): List<SubscriptionMetric> = when (provider) {
    SubscriptionProvider.DEEPSEEK -> metrics.filter { it.kind == SubscriptionMetricKind.BALANCE }
    SubscriptionProvider.OPENCODE_ZEN -> listOfNotNull(metrics.firstOrNull { it.kind == SubscriptionMetricKind.AVAILABLE }
        ?: metrics.firstOrNull { it.kind == SubscriptionMetricKind.BALANCE })
    SubscriptionProvider.OPENCODE_GO -> metrics.filter { it.kind in setOf(SubscriptionMetricKind.ROLLING, SubscriptionMetricKind.WEEKLY) }
    SubscriptionProvider.CURSOR -> metrics.filter { it.kind in setOf(SubscriptionMetricKind.CURSOR_MODELS, SubscriptionMetricKind.OTHER_MODELS) }
}

internal fun SubscriptionMetric.formattedValue(texts: TextResources): String = when {
    value == null -> texts(R.string.subscription_unknown)
    currency != null -> "$currency $value"
    else -> texts(R.string.subscription_used, java.math.BigDecimal(value)
        .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
}
