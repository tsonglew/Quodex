package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.localTextResources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ResetCreditsCard(state: AppState, now: Long, onLoad: (Boolean) -> Unit) {
    val snapshot = state.snapshot ?: return
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(snapshot.accountId) { mutableStateOf(false) }
    val details = snapshot.resetCreditDetails
    val (count, countTime) = snapshot.resetCreditAvailability()
    LaunchedEffect(expanded, snapshot.accountId, snapshot.fetchedAt) {
        if (expanded && state.connected && !state.demo) onLoad(false)
    }
    GlassPanel(Modifier.fillMaxWidth().testTag("reset-credits-card"), shape = RoundedCornerShape(24.dp), color = colors.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.ConfirmationNumber, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Text(texts(R.string.reset_cards_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text(when (count) {
                null -> texts(R.string.reset_cards_count_unknown)
                0 -> texts(R.string.reset_cards_none)
                else -> texts(R.string.reset_cards_count, count)
            }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.primary,
                modifier = Modifier.testTag("reset-credits-count"))
            Text(texts(R.string.reset_cards_explanation), fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            if (state.demo) Text(texts(R.string.sample_data_not_your_actual_quota), fontSize = 12.sp, color = colors.onSurfaceVariant)
            if (state.failure != null || (!state.demo && now - countTime > 300)) {
                Text(texts(R.string.reset_cards_cached), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            Text(texts(R.string.reset_cards_count_updated, absoluteTime(countTime, true)), fontSize = 12.sp, color = colors.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("reset-credits-toggle")) {
                Text(texts(if (expanded) R.string.reset_cards_hide else R.string.reset_cards_show))
            }
            if (expanded) {
                HorizontalDivider(color = colors.outlineVariant)
                if (state.resetCreditsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reset-credits-loading"))
                    Text(texts(R.string.reset_cards_loading), fontSize = 13.sp)
                }
                if (state.resetCreditsFailure != null) {
                    Text(texts(when (state.resetCreditsFailure) {
                        FailureKind.AUTH -> R.string.reset_cards_auth_failure
                        else -> R.string.reset_cards_details_failure
                    }), fontSize = 13.sp, lineHeight = 20.sp, color = colors.error)
                }
                if (state.resetCreditsRetryAt > now) {
                    Text(texts(R.string.reset_cards_retry_at, absoluteTime(state.resetCreditsRetryAt, true)), fontSize = 13.sp)
                }
                when {
                    details == null && !state.resetCreditsLoading -> Text(texts(R.string.reset_cards_details_unknown), fontSize = 13.sp, color = colors.onSurfaceVariant)
                    details != null -> {
                        Text(texts(R.string.reset_cards_details_updated, absoluteTime(details.fetchedAt, true)), fontSize = 12.sp, color = colors.onSurfaceVariant)
                        if (details.fetchedAt < snapshot.fetchedAt || state.resetCreditsFailure != null) {
                            Text(texts(R.string.reset_cards_details_cached), fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                        if (details.credits.isEmpty()) Text(texts(R.string.reset_cards_details_empty), fontSize = 13.sp, color = colors.onSurfaceVariant)
                        details.credits.forEach { credit -> key(credit.id) { ResetCreditRow(credit, now) } }
                        if (details.credits.size < details.availableCount) {
                            Text(texts(R.string.reset_cards_partial, details.credits.size, details.availableCount), fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (!state.demo) TextButton(onClick = { onLoad(true) },
                    enabled = state.connected && !state.resetCreditsLoading && now >= state.resetCreditsRetryAt,
                    modifier = Modifier.testTag("reset-credits-refresh")) { Text(texts(R.string.reset_cards_refresh)) }
                Text(texts(R.string.reset_cards_read_only), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResetCreditRow(credit: ResetCredit, now: Long) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    fun date(value: Long) = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(value))
    GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(credit.title ?: texts(R.string.reset_cards_default_name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(texts(when (credit.status) {
                "available" -> if (credit.expiresAt != null && credit.expiresAt <= now) R.string.reset_cards_expired_check else R.string.reset_cards_available
                "redeemed", "consumed" -> R.string.reset_cards_redeemed
                "expired" -> R.string.reset_cards_expired
                else -> R.string.reset_cards_status_unknown
            }), fontSize = 12.sp, color = colors.primary)
            credit.description?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant) }
            credit.grantedAt?.let { Text(texts(R.string.reset_cards_granted, date(it)), fontSize = 12.sp, color = colors.onSurfaceVariant) }
            Text(credit.expiresAt?.let { texts(R.string.reset_cards_expires, date(it)) }
                ?: texts(R.string.reset_cards_expiry_unknown), fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}
