package com.tsonglew.quodex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.localTextResources
import java.math.BigInteger

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenHeatmapChart(trend: TokenTrend, compact: Boolean, modifier: Modifier = Modifier, onDetails: (() -> Unit)? = null) {
    val texts = localTextResources()
    val map = remember(trend) { TokenHeatmap(trend) }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var selected by rememberSaveable(trend.startDate.toString(), trend.endDate.toString()) { mutableStateOf(trend.endDate.toString()) }
    // A restored selection may predate a snapshot updated by the widget while
    // the Activity was gone. Saved-state restoration does not validate input keys.
    val day = trend.days.firstOrNull { it.date.toString() == selected } ?: trend.days.last()
    val selectedDate = day.date.toString()
    val description = texts(R.string.value_to_value_value_days_with_records, trend.startDate, trend.endDate, trend.knownDays) +
        texts(R.string.total_reported_daily_usage_value_tokens, formatTokenCount(trend.total, compact)) +
        texts(R.string.value_days_without_data, trend.days.size - trend.knownDays) +
        texts(R.string.value_days_recorded_as_0_open_daily, trend.days.count { it.tokens == BigInteger.ZERO })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(texts(R.string.token_heatmap_scale), fontSize = 12.sp, color = muted)
        BoxWithConstraints(Modifier.fillMaxWidth().height(210.dp).testTag("token-trend-chart")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (onDetails != null) onClick(texts(R.string.daily_details)) { onDetails(); true }
            }) {
            val density = LocalDensity.current
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val height = with(density) { 210.dp.roundToPx() }
            val bitmap = remember(map, width, height, dark, texts.locale, selectedDate) {
                TokenHeatmapRenderer.render(map, width, height, dark, texts.locale, selectedDate).asImageBitmap()
            }
            Image(bitmap, null, Modifier.fillMaxSize().testTag("token-heatmap").pointerInput(map, width, height) {
                detectTapGestures { point -> TokenHeatmapRenderer.hit(map, width.toFloat(), height.toFloat(), point.x, point.y)?.let { selected = it.date.toString() } }
            })
        }
        Text(texts(R.string.token_heatmap_selected_day, day.date,
            day.tokens?.let { texts(R.string.count_tokens, formatTokenCount(it, compact)) } ?: texts(R.string.no_data_provided)),
            fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("heatmap-selected-day"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(texts(R.string.token_heatmap_less), fontSize = 11.sp, color = muted)
            TokenHeatmapRenderer.colors(dark).forEach { color ->
                Canvas(Modifier.size(14.dp)) { drawRoundRect(Color(color), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())) }
            }
            Text(texts(R.string.token_heatmap_more), fontSize = 11.sp, color = muted)
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.size(14.dp)) { drawRoundRect(muted, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx())) }
            Text(texts(R.string.no_data_provided), fontSize = 11.sp, color = muted)
        }
        Text(texts(R.string.token_heatmap_legend), fontSize = 12.sp, lineHeight = 20.sp, color = muted)
    }
}
