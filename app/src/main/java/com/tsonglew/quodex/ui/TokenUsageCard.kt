package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.data.*
import java.math.BigInteger

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenUsageCard(
    usage: TokenUsageSnapshot?,
    loading: Boolean,
    failure: FailureKind?,
    demo: Boolean,
    onRefresh: () -> Unit,
    compact: Boolean = false,
    onCompactChange: (Boolean) -> Unit = {},
) {
    val texts = localTextResources()
    var period by rememberSaveable { mutableIntStateOf(7) }
    var details by rememberSaveable { mutableStateOf(false) }
    val sample = remember { demoTokenUsage() }
    val displayed = if (demo) sample else usage
    val trend = remember(displayed, period) { tokenTrend(displayed?.dailyBuckets, period) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("token-usage-card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelHeading(texts(R.string.token_usage_trend), Icons.Outlined.BarChart)
            Text(if (demo) texts(R.string.sample_data_preview_only) else texts(R.string.account_wide_token_totals_service_dates), fontSize = 12.sp, color = secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { days ->
                    FilterChip(shape = RoundedCornerShape(50), selected = period == days, onClick = { period = days },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        label = { Text(texts(R.string.last_value_days, days)) }, modifier = Modifier.testTag("token-trend-$days"))
                }
            }
            if (trend != null) {
                Text(texts(R.string.through_value_last_value_days, trend.endDate, period), fontSize = 12.sp, color = secondary)
                Text(formatTokenCount(trend.total, compact), fontSize = 32.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    lineHeight = 40.sp, modifier = Modifier.testTag("token-trend-total"))
                Text(texts(R.string.total_reported_daily_usage_value_value_days, trend.knownDays, trend.days.size), fontSize = 12.sp, color = secondary)
                TokenBars(trend, compact)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(trend.startDate.toString(), fontSize = 12.sp, color = secondary)
                    Text(trend.endDate.toString(), fontSize = 12.sp, color = secondary)
                }
                Text(texts(R.string.dash_no_data_dot_recorded_as_0), fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
                TextButton(onClick = { details = true }, modifier = Modifier.testTag("token-trend-details")) { Text(texts(R.string.daily_details)) }
            } else {
                val message = when {
                    loading && !demo -> texts(R.string.loading_token_statistics)
                    displayed?.dailyBuckets == null -> texts(R.string.the_service_has_not_provided_daily_token)
                    else -> texts(R.string.no_displayable_daily_usage_records_were_returned)
                }
                Text(message, fontSize = 13.sp, lineHeight = 21.sp, color = secondary)
                Text(texts(R.string.missing_data_does_not_mean_no_usage), fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
            }
            if (displayed?.lifetimeTokens != null || displayed?.peakDailyTokens != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                displayed?.lifetimeTokens?.let { TokenMetric(texts(R.string.lifetime_tokens), it, compact) }
                displayed?.peakDailyTokens?.let { TokenMetric(texts(R.string.all_time_daily_peak), it, compact) }
            }
            if (displayed?.partial == true) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Text(texts(R.string.service_data_may_be_incomplete_only_available), fontSize = 12.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            if (!demo) {
                failure?.let {
                    Text(tokenFailureMessage(it, usage != null, texts), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error)
                }
                if (displayed != null) {
                    Text(texts(R.string.fetched_at_value, absoluteTime(displayed.fetchedAt, true)), fontSize = 12.sp, color = secondary)
                    displayed.statsAsOf?.let { Text(texts(R.string.service_statistics_timestamp_value, it), fontSize = 12.sp, color = secondary) }
                }
                TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.testTag("token-trend-refresh")) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (loading) texts(R.string.updating_statistics) else texts(R.string.refresh_token_statistics))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            PreferenceToggle(texts(R.string.compact_units_k_m_b_t), compact, onCompactChange,
                modifier = Modifier.testTag("compact-token-toggle"))
            Text(texts(R.string.statistics_may_be_delayed_this_interface_does), fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
        }
    }
    if (details && trend != null) {
        AlertDialog(onDismissRequest = { details = false }, title = { Text(if (demo) texts(R.string.demo_daily_tokens) else texts(R.string.daily_tokens)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("token-trend-day-list")) {
                    itemsIndexed(trend.days.asReversed(), key = { _, day -> day.date.toString() }) { index, day ->
                        // Keep the explanation scrollable with the data at large font sizes.
                        // Including it in the first day preserves one list item per date.
                        if (index == 0) {
                            Text(texts(R.string.value_value_ndates_come_from_the_service, trend.startDate, trend.endDate), fontSize = 12.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {}) {
                            Text(day.date.toString(), fontSize = 12.sp, color = secondary)
                            Text(day.tokens?.let { texts(R.string.count_tokens, formatTokenCount(it, compact)) } ?: texts(R.string.no_data_provided), fontSize = 15.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { details = false }, modifier = Modifier.testTag("token-trend-details-close")) { Text(texts(R.string.close)) } })
    }
}

@Composable
private fun TokenMetric(label: String, value: Long, compact: Boolean) {
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTokenCount(BigInteger.valueOf(value), compact), fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TokenBars(trend: TokenTrend, compact: Boolean) {
    val texts = localTextResources()
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val missing = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = trend.peak.max(BigInteger.ONE).toDouble()
    // A new date range gets one staggered reveal. Refreshes within that range
    // interpolate the existing bars, without replaying when only metadata changes.
    val reveal = remember(trend.startDate, trend.endDate, trend.days.size) { Animatable(0f) }
    LaunchedEffect(reveal) {
        reveal.animateTo(1f, tween(durationMillis = 600, easing = LinearEasing))
    }
    val heights = key(trend.startDate, trend.endDate, trend.days.size) {
        trend.days.map { day ->
            key(day.date) {
                animateFloatAsState(
                    targetValue = ((day.tokens?.toDouble() ?: 0.0) / peak).toFloat().coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                    label = "token-bar-${day.date}",
                )
            }
        }
    }
    val description = texts(R.string.value_to_value_value_days_with_records, trend.startDate, trend.endDate, trend.knownDays) +
        texts(R.string.total_reported_daily_usage_value_tokens, formatTokenCount(trend.total, compact)) +
        texts(R.string.value_days_without_data, trend.days.size - trend.knownDays) +
        texts(R.string.value_days_recorded_as_0_open_daily, trend.days.count { it.tokens == BigInteger.ZERO })
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(texts(R.string.peak_daily_usage_value_tokens, formatTokenCount(trend.peak, compact)), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(140.dp).testTag("token-trend-chart").semantics { contentDescription = description }) {
            val baseline = size.height - 4.dp.toPx()
            val plotHeight = baseline - 4.dp.toPx()
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = baseline - plotHeight * fraction
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val slot = size.width / trend.days.size
            val width = (slot * 0.64f).coerceAtMost(26.dp.toPx())
            // Read animation state only while drawing, so frames do not recompose
            // the card or change the real values exposed to accessibility services.
            val elapsed = reveal.value * 600f
            trend.days.forEachIndexed { index, day ->
                val center = slot * (index + 0.5f)
                when {
                    day.tokens == null -> drawLine(missing, Offset(center - width / 3, baseline - 3.dp.toPx()),
                        Offset(center + width / 3, baseline - 3.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    else -> {
                        val start = index.toFloat() / (trend.days.size - 1).coerceAtLeast(1) * 180f
                        val growth = FastOutSlowInEasing.transform(((elapsed - start) / 420f).coerceIn(0f, 1f))
                        val minimum = if (day.tokens == BigInteger.ZERO) 0f else 2.dp.toPx()
                        val height = (heights[index].value * plotHeight).coerceAtLeast(minimum) * growth
                        if (height > 0f) {
                            drawRoundRect(bar, Offset(center - width / 2, baseline - height), Size(width, height),
                                CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                        }
                        if (day.tokens == BigInteger.ZERO) {
                            drawCircle(bar, radius = 2.dp.toPx(), center = Offset(center, baseline))
                        }
                    }
                }
            }
        }
    }
}

private fun tokenFailureMessage(failure: FailureKind, cached: Boolean, texts: TextResources): String {
    val reason = when (failure) {
        FailureKind.AUTH -> texts(R.string.token_statistics_authorization_is_unavailable_refresh_or)
        FailureKind.FORBIDDEN -> texts(R.string.this_account_cannot_access_token_statistics_right)
        FailureKind.LIMITED -> texts(R.string.too_many_token_statistics_requests_try_again)
        FailureKind.NETWORK -> texts(R.string.network_connection_failed_token_statistics_could_not)
        FailureKind.SERVER -> texts(R.string.the_token_statistics_service_is_temporarily_unavailable)
        FailureKind.PROTOCOL -> texts(R.string.the_token_statistics_format_is_not_supported)
        FailureKind.STORAGE -> texts(R.string.token_statistics_could_not_be_saved_check)
    }
    return reason + if (cached) texts(R.string.showing_previously_fetched_data) else texts(R.string.quota_display_is_unaffected)
}
