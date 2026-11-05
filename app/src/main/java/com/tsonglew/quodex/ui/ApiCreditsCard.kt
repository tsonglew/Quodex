package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.localTextResources

@Composable
fun ApiCreditsCard(state: AppState, now: Long, onLoad: (Boolean) -> Unit) {
    val snapshot = state.snapshot ?: return
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    var expanded by rememberSaveable(snapshot.accountId) { mutableStateOf(false) }
    val details = snapshot.apiCreditDetails
    val benefit = details?.benefit ?: details?.offer
    LaunchedEffect(expanded, snapshot.accountId) {
        if (expanded && state.connected && !state.demo) onLoad(false)
    }
    GlassPanel(Modifier.fillMaxWidth().testTag("api-credits-card"), RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CardGiftcard, null, Modifier.size(22.dp), tint = colors.primary)
                Text(texts(R.string.api_credits_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text(texts(when {
                benefit != null && benefit.expiresAt <= now -> R.string.api_credits_expired
                details?.benefit != null -> R.string.api_credits_benefit
                details?.eligible == true -> R.string.api_credits_eligible
                details != null -> R.string.api_credits_ineligible
                else -> R.string.api_credits_unknown
            }), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("api-credits-status"))
            Text(texts(R.string.api_credits_description), fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            if (state.demo) Text(texts(R.string.sample_data_not_your_actual_quota), fontSize = 12.sp, color = colors.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("api-credits-toggle")) {
                Text(texts(if (expanded) R.string.reset_cards_hide else R.string.reset_cards_show))
            }
            if (expanded) {
                if (state.apiCreditsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.apiCreditsFailure != null) Text(texts(R.string.api_credits_unavailable),
                    fontSize = 13.sp, color = colors.error, modifier = Modifier.testTag("api-credits-error"))
                benefit?.let {
                    Text(texts(if (it.cadence == "annual") R.string.api_credits_annual else R.string.api_credits_monthly,
                        it.periodAmountUsd), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.primary,
                        modifier = Modifier.testTag("api-credits-period"))
                    if (details?.benefit == null) it.claimableAmountUsd?.let { amount ->
                        Text(texts(R.string.api_credits_claimable, amount), fontSize = 14.sp)
                    }
                    Text(texts(R.string.api_credits_expires, absoluteTime(it.expiresAt, true)), fontSize = 13.sp)
                }
                Text(texts(R.string.api_credits_balance_unknown), fontSize = 13.sp, color = colors.onSurfaceVariant)
                details?.let {
                    Text(texts(R.string.reset_cards_details_updated, absoluteTime(it.fetchedAt, true)), fontSize = 12.sp, color = colors.onSurfaceVariant)
                    if (!state.demo && (state.apiCreditsFailure != null || now - it.fetchedAt > 300))
                        Text(texts(R.string.reset_cards_details_cached), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                if (state.apiCreditsRetryAt > now) Text(texts(R.string.reset_cards_retry_at,
                    absoluteTime(state.apiCreditsRetryAt, true)), fontSize = 13.sp)
                if (!state.demo) TextButton(onClick = { onLoad(true) },
                    enabled = state.connected && !state.apiCreditsLoading && now >= state.apiCreditsRetryAt,
                    modifier = Modifier.testTag("api-credits-refresh")) { Text(texts(R.string.reset_cards_refresh)) }
                TextButton(onClick = { runCatching { uri.openUri("https://chatgpt.com/settings/billing") } }) {
                    Text(texts(R.string.api_credits_open_billing))
                }
                Text(texts(R.string.api_credits_read_only), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}
