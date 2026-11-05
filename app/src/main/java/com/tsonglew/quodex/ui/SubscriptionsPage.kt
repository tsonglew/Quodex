package com.tsonglew.quodex.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.MainViewModel
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.subscriptions.*

@Composable
fun SubscriptionsPage(model: MainViewModel, now: Long, appState: AppState,
    selectedProvider: SubscriptionProvider? = null, examples: Boolean = false,
    onExamples: (Boolean) -> Unit = {}, onOpen: (String) -> Unit = {}) {
    val appDemo = appState.demo
    val saved by model.subscriptions.collectAsStateWithLifecycle()
    val storageUnavailable by model.subscriptionStorageUnavailable.collectAsStateWithLifecycle()
    val demo = appDemo || examples
    LaunchedEffect(demo) { if (!demo) model.loadSubscriptions() }
    val texts = localTextResources()
    val context = LocalContext.current
    var connecting by remember { mutableStateOf<SubscriptionProvider?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    if (selectedProvider == null) {
        Text(texts(R.string.nav_accounts), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(texts(R.string.account_intro), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onExamples(!examples) }, enabled = !appDemo, modifier = Modifier.testTag("subscription-examples")) {
            Text(texts(if (demo) R.string.subscription_real_data else R.string.subscription_examples))
        }
    }
    if (demo) Text(texts(R.string.subscription_sample_notice), color = MaterialTheme.colorScheme.primary)
    if (!demo && storageUnavailable) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(texts(R.string.subscription_storage_unreadable))
                OutlinedButton(onClick = model::loadSubscriptions, modifier = Modifier.fillMaxWidth()) {
                    Text(texts(R.string.subscription_retry_storage))
                }
                TextButton(onClick = { confirmReset = true }, modifier = Modifier.testTag("subscription-reset")) {
                    Text(texts(R.string.subscription_reset_storage))
                }
            }
        }
    }
    if (selectedProvider == null) {
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            NavigationRow("Codex", texts(when {
                appDemo -> R.string.demo_mode
                appState.failure != null -> R.string.account_attention
                appState.connected -> R.string.account_connected
                else -> R.string.not_connected
            }), { onOpen("codex-account") }, Modifier.testTag("account-codex"), Icons.Outlined.AccountCircle)
            SubscriptionProvider.entries.forEach { provider ->
                val account = saved.getValue(provider)
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                NavigationRow(provider.displayName, texts(when {
                    demo -> R.string.demo_mode
                    account.busy -> R.string.refreshing_quota
                    account.failure != null -> R.string.account_attention
                    account.connected -> R.string.account_connected
                    else -> R.string.not_connected
                }), { onOpen("provider:${provider.name}") }, Modifier.testTag("account-${provider.name}"), Icons.Outlined.AccountCircle)
            }
        }
    }
    listOfNotNull(selectedProvider).forEach { provider ->
        val state = if (demo) SubscriptionState(snapshot = sampleSubscription(provider, now)) else saved.getValue(provider)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().testTag("subscription-${provider.name}")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(provider.displayName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(texts(provider.descriptionResource()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.snapshot?.let { snapshot ->
                    snapshot.plan?.let { plan -> Text(if (provider == SubscriptionProvider.OPENCODE_ZEN) texts(when (plan) {
                        "prepaid" -> R.string.subscription_prepaid
                        "credit" -> R.string.subscription_credit
                        "seat" -> R.string.subscription_seat
                        else -> R.string.subscription_legacy
                    }) else plan, style = MaterialTheme.typography.labelLarge) }
                    if (snapshot.available == false) Text(texts(R.string.subscription_api_unavailable))
                    snapshot.metrics.forEach { metric ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(texts(metric.kind.labelResource()), style = MaterialTheme.typography.labelLarge)
                            val value = metric.value
                            Text(metric.formattedValue(texts), fontSize = 20.sp)
                            if (metric.currency == null && value != null) LinearProgressIndicator(
                                progress = { ((value.toDoubleOrNull() ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat() },
                                modifier = Modifier.fillMaxWidth())
                            metric.resetsAt?.let { Text(if (it <= now) texts(R.string.reset_unconfirmed_refresh_to_check)
                                else texts(R.string.resets_value, absoluteTime(it, true)),
                                style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Text(texts(R.string.subscription_updated, absoluteTime(snapshot.fetchedAt, true)), style = MaterialTheme.typography.bodySmall)
                }
                state.failure?.takeUnless { storageUnavailable && !demo }?.let { failure ->
                    Text(if (failure == FailureKind.LIMITED) texts(R.string.subscription_error_limited, absoluteTime(state.retryAt, true))
                        else texts(failure.subscriptionErrorResource()), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("subscription-error-${provider.name}"))
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!demo) {
                    if (state.busy) TextButton(onClick = { model.cancelSubscription(provider) }) {
                        Text(texts(R.string.subscription_cancel))
                    }
                    Button(onClick = { connecting = provider }, enabled = !state.busy && !storageUnavailable,
                        modifier = Modifier.fillMaxWidth().testTag("subscription-connect-${provider.name}")) { Text(texts(if (state.connected) R.string.reconnect else R.string.subscription_connect)) }
                    if (state.connected) {
                        OutlinedButton(onClick = { model.refreshSubscription(provider) },
                            enabled = !state.busy && now >= state.retryAt, modifier = Modifier.fillMaxWidth()) { Text(texts(R.string.subscription_refresh)) }
                        TextButton(onClick = { model.disconnectSubscription(provider) }) { Text(texts(R.string.subscription_disconnect)) }
                    }
                }
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.dashboard))) } }) {
                    Text(texts(R.string.subscription_dashboard))
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(texts(R.string.subscriptions_scope), style = MaterialTheme.typography.bodySmall)
    if (confirmReset) GlassAlertDialog(surfaceId = "subscriptions-reset", onDismissRequest = { confirmReset = false },
        title = { Text(texts(R.string.subscription_reset_title)) },
        text = { Text(texts(R.string.subscription_reset_detail), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmReset = false; model.resetUnreadableSubscriptions() },
            modifier = Modifier.testTag("subscription-reset-confirm")) { Text(texts(R.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(texts(R.string.subscription_cancel)) } })
    connecting?.let { provider ->
        // Never save credentials to saved-instance-state or a persistent text field.
        var input by remember(provider) { mutableStateOf("") }
        GlassAlertDialog(surfaceId = "subscription-connect", onDismissRequest = { input = ""; connecting = null },
            title = { Text(provider.displayName) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(texts(provider.helpResource()))
                OutlinedTextField(value = input, onValueChange = { input = it.take(8192) }, singleLine = true,
                    label = { Text(texts(if (provider == SubscriptionProvider.CURSOR) R.string.subscription_session else R.string.subscription_credential)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("subscription-credential"))
                Text(texts(R.string.subscription_secret_notice), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = SubscriptionApi.validCredential(input.trim()), onClick = {
                model.connectSubscription(provider, input); input = ""; connecting = null
            }) { Text(texts(R.string.subscription_connect)) } },
            dismissButton = { TextButton(onClick = { input = ""; connecting = null }) { Text(texts(R.string.subscription_cancel)) } })
    }
}

private fun SubscriptionProvider.helpResource() = when (this) {
    SubscriptionProvider.DEEPSEEK -> R.string.subscription_deepseek_help
    SubscriptionProvider.OPENCODE_GO -> R.string.subscription_go_help
    SubscriptionProvider.OPENCODE_ZEN -> R.string.subscription_zen_help
    SubscriptionProvider.CURSOR -> R.string.subscription_cursor_help
}
private fun SubscriptionProvider.descriptionResource() = when (this) {
    SubscriptionProvider.DEEPSEEK -> R.string.subscription_deepseek_description
    SubscriptionProvider.OPENCODE_GO -> R.string.subscription_go_description
    SubscriptionProvider.OPENCODE_ZEN -> R.string.subscription_zen_description
    SubscriptionProvider.CURSOR -> R.string.subscription_cursor_description
}
internal fun SubscriptionMetricKind.labelResource() = when (this) {
    SubscriptionMetricKind.BALANCE -> R.string.subscription_balance
    SubscriptionMetricKind.AVAILABLE -> R.string.subscription_available
    SubscriptionMetricKind.GRANTED -> R.string.subscription_granted
    SubscriptionMetricKind.TOPPED_UP -> R.string.subscription_topped_up
    SubscriptionMetricKind.ROLLING -> R.string.subscription_rolling
    SubscriptionMetricKind.WEEKLY -> R.string.subscription_weekly
    SubscriptionMetricKind.MONTHLY -> R.string.subscription_monthly
    SubscriptionMetricKind.CURSOR_MODELS -> R.string.subscription_cursor_models
    SubscriptionMetricKind.OTHER_MODELS -> R.string.subscription_other_models
    SubscriptionMetricKind.TOTAL -> R.string.subscription_total
    SubscriptionMetricKind.ON_DEMAND -> R.string.subscription_on_demand
}
internal fun FailureKind.subscriptionErrorResource() = when (this) {
    FailureKind.AUTH -> R.string.subscription_error_auth
    FailureKind.FORBIDDEN -> R.string.subscription_error_forbidden
    FailureKind.PROTOCOL -> R.string.subscription_error_protocol
    FailureKind.STORAGE -> R.string.subscription_error_storage
    else -> R.string.subscription_error_network
}

internal fun sampleSubscription(provider: SubscriptionProvider, now: Long) = SubscriptionSnapshot(provider, now, when (provider) {
    SubscriptionProvider.DEEPSEEK -> listOf(SubscriptionMetric(SubscriptionMetricKind.BALANCE, "110.00", "CNY"),
        SubscriptionMetric(SubscriptionMetricKind.GRANTED, "10.00", "CNY"), SubscriptionMetric(SubscriptionMetricKind.TOPPED_UP, "100.00", "CNY"))
    SubscriptionProvider.OPENCODE_ZEN -> listOf(SubscriptionMetric(SubscriptionMetricKind.BALANCE, "18.50", "USD"),
        SubscriptionMetric(SubscriptionMetricKind.AVAILABLE, "18.50", "USD"))
    SubscriptionProvider.OPENCODE_GO -> listOf(SubscriptionMetric(SubscriptionMetricKind.ROLLING, "24", resetsAt = now + 7200),
        SubscriptionMetric(SubscriptionMetricKind.WEEKLY, "38", resetsAt = now + 172800), SubscriptionMetric(SubscriptionMetricKind.MONTHLY, "46", resetsAt = now + 864000))
    SubscriptionProvider.CURSOR -> listOf(SubscriptionMetric(SubscriptionMetricKind.CURSOR_MODELS, "35", resetsAt = now + 864000),
        SubscriptionMetric(SubscriptionMetricKind.OTHER_MODELS, "18", resetsAt = now + 864000))
})
