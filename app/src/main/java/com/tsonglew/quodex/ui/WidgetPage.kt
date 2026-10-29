package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.widget.QuotaWidgetReceiver
import com.tsonglew.quodex.widget.SlimQuotaWidgetReceiver
import com.tsonglew.quodex.widget.TokenHeatmapWidgetReceiver
import java.time.Instant

/** Layout illustrations use the same window selection as the corresponding desktop widgets. */
@Composable
internal fun WidgetPage(state: AppState, prefs: PreferencesState) {
    val texts = localTextResources()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val example = remember { demoSnapshot(Instant.now().epochSecond) }
    val snapshot = state.snapshot ?: example
    val isExample = state.snapshot == null || state.demo
    val isOld = !isExample && state.failure != null
    var manualWidget by rememberSaveable { mutableStateOf<String?>(null) }
    val requestPin = rememberHomePinRequest { target ->
        val (receiver, label) = when (target) {
            HomePinTarget.COMPACT_WIDGET -> SlimQuotaWidgetReceiver::class.java to R.string.codex_quota_2_1
            HomePinTarget.TOKEN_HEATMAP -> TokenHeatmapWidgetReceiver::class.java to R.string.token_heatmap_widget_name
            else -> QuotaWidgetReceiver::class.java to R.string.codex_quota
        }
        if (!requestWidgetPin(context, receiver)) {
            manualWidget = texts(label)
        }
    }

    Column(Modifier.fillMaxWidth().testTag("widget-page")) {
        Text(texts(R.string.home_screen_widgets), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(texts(R.string.choose_a_layout_and_add_it_to), fontSize = 12.sp, lineHeight = 19.sp,
            color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isExample) Icons.Outlined.Visibility else Icons.Outlined.Info, null,
                Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            Text(if (isExample) texts(R.string.layout_preview_sample_data_not_your_quota) else texts(R.string.layout_preview_last_successful_update),
                fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        }
        if (state.snapshot == null) {
            Spacer(Modifier.height(6.dp))
            Text(texts(R.string.when_disconnected_home_screen_widgets_prompt_you), fontSize = 12.sp, lineHeight = 19.sp,
                color = colors.onSurfaceVariant)
        }
        if (isOld) {
            Spacer(Modifier.height(6.dp))
            Text(texts(R.string.value_the_preview_retains_the_last_successful, state.failure.userMessage().resolve(texts)), fontSize = 12.sp, lineHeight = 19.sp,
                color = colors.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(16.dp))

        WidgetLayoutPanel(texts(R.string.standard_widget), texts(R.string.quota_and_reset_times_adapted_to_the), texts(R.string.add_standard_widget),
            "add-widget-button", onAdd = {
                requestPin(HomePinTarget.STANDARD_WIDGET)
            }) {
            StandardWidgetIllustration(snapshot, prefs.showRemaining, isExample, isOld)
        }
        Spacer(Modifier.height(18.dp))
        WidgetLayoutPanel(texts(R.string.compact_widget_2_1), texts(R.string.main_quota_and_update_time_in_less), texts(R.string.add_compact_2_1_widget),
            "add-slim-widget-button", onAdd = {
                requestPin(HomePinTarget.COMPACT_WIDGET)
            }) {
            CompactWidgetIllustration(snapshot, prefs.showRemaining, isExample, isOld)
        }
        Spacer(Modifier.height(18.dp))
        WidgetLayoutPanel(texts(R.string.token_heatmap_widget_name), texts(R.string.token_heatmap_widget_description),
            texts(R.string.add_token_heatmap_widget), "add-heatmap-widget-button", onAdd = { requestPin(HomePinTarget.TOKEN_HEATMAP) }) {
            val actual = if (state.demo) demoTokenUsage() else state.snapshot?.tokenUsage
            val heatmap = remember(actual) { tokenTrend(actual?.dailyBuckets, 30) }
            if (state.demo || heatmap == null) Text(texts(R.string.layout_preview_sample_data_not_your_quota), fontSize = 12.sp, color = colors.onSurfaceVariant)
            TokenHeatmapChart(heatmap ?: tokenTrend(demoTokenUsage().dailyBuckets, 30)!!, compact = true)
        }
        Spacer(Modifier.height(14.dp))
        Text(texts(R.string.the_layout_adapts_to_home_screen_size),
            fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
    }

    manualWidget?.let { widgetName ->
        AlertDialog(onDismissRequest = { manualWidget = null }, title = { Text(texts(R.string.add_from_home_screen)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(texts(R.string.your_launcher_did_not_accept_direct_adding))
                Text(texts(R.string.press_and_hold_an_empty_area_of, widgetName))
            }
        }, confirmButton = {
            TextButton(onClick = { manualWidget = null }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text(texts(R.string.got_it)) }
        })
    }
}

private fun requestWidgetPin(context: Context, receiver: Class<*>): Boolean = try {
    val manager = AppWidgetManager.getInstance(context)
    manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
} catch (_: IllegalStateException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
private fun WidgetLayoutPanel(title: String, description: String, addLabel: String, addTag: String,
    onAdd: () -> Unit, preview: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(5.dp))
            Text(description, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = colors.background) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { preview() }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(addTag),
                shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(addLabel, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StandardWidgetIllustration(snapshot: UsageSnapshot, remaining: Boolean, example: Boolean, old: Boolean) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    val windows = snapshot.windows.take(2)
    val notice = snapshot.quotaNotice(texts)
    val restricted = snapshot.allowed == false || snapshot.limitReached == true
    val largeFont = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Codex", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
    notice?.let {
        Spacer(Modifier.height(12.dp))
        Text(it.title, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            color = if (restricted) colors.error else colors.onSurface)
        if (windows.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(it.description, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        }
    }
    windows.forEach { window ->
        Spacer(Modifier.height(12.dp))
        val showRestriction = restricted && largeFont
        val value = if (showRestriction) texts(R.string.usage_restricted) else window.percentageLabel(remaining, texts) +
            if (window.usedPercent != null) "%" else ""
        WidgetPreviewValue(window.displayName(texts), value, window.usedPercent != null && !showRestriction)
        Spacer(Modifier.height(6.dp))
        when {
            showRestriction -> Unit
            window.usedPercent != null -> WidgetPreviewSegments(window, remaining)
            else -> Text(texts(R.string.usage_data_unavailable), fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text("${if (remaining) texts(R.string.remaining) else texts(R.string.used)} · ${resetLabel(window, texts)}", fontSize = 12.sp,
            lineHeight = 19.sp, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Text(widgetPreviewTimestamp(snapshot, example, old, texts), fontSize = 12.sp, lineHeight = 19.sp,
        color = colors.onSurfaceVariant)
}

@Composable
private fun CompactWidgetIllustration(snapshot: UsageSnapshot, remaining: Boolean, example: Boolean, old: Boolean) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    // The single-row widget never promotes a Review or other extra limit to the main quota.
    val window = snapshot.windows.firstOrNull { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
    val restricted = snapshot.allowed == false || snapshot.limitReached == true
    val metered = window?.usedPercent != null && !restricted
    val label = if (metered) when (window?.durationSeconds) {
        604800L -> texts(R.string.codex_week)
        86400L -> texts(R.string.codex_day)
        else -> "Codex"
    } else "Codex"
    val value = when {
        restricted -> texts(R.string.usage_restricted)
        window != null -> window.percentageLabel(remaining, texts) + if (metered) "%" else ""
        else -> snapshot.quotaNotice(texts)?.title ?: texts(R.string.no_period_quota_provided)
    }
    WidgetPreviewValue(label, value, metered)
    if (metered) {
        Spacer(Modifier.height(8.dp))
        WidgetPreviewSegments(requireNotNull(window), remaining)
    } else if (window != null && !restricted) {
        Spacer(Modifier.height(6.dp))
        Text(texts(R.string.usage_data_unavailable), fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (metered) "${if (remaining) texts(R.string.remaining) else texts(R.string.used)} · " else ""}${widgetPreviewTimestamp(snapshot, example, old, texts)}",
            Modifier.weight(1f), fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun WidgetPreviewValue(label: String, value: String, metered: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val name: @Composable () -> Unit = { Text(label, fontSize = 13.sp, lineHeight = 19.sp) }
        val amount: @Composable () -> Unit = {
            Text(value, fontSize = if (metered) 26.sp else 16.sp, lineHeight = if (metered) 32.sp else 23.sp,
                fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"))
        }
        if (maxWidth / LocalDensity.current.fontScale < 220.dp || !metered) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { name(); amount() }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { name() }
            amount()
        }
    }
}

@Composable
private fun WidgetPreviewSegments(window: QuotaWindow, remaining: Boolean) {
    val texts = localTextResources()
    val value = window.displayedPercent(remaining) ?: return
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(6.dp).semantics {
        contentDescription = texts(R.string.quota_percentage_description, window.displayName(texts), if (remaining) texts(R.string.remaining) else texts(R.string.used), window.percentageLabel(remaining, texts))
    }) {
        val gap = 2.dp.toPx()
        val segmentWidth = (size.width - gap * 27) / 28
        repeat(28) { index ->
            drawRect(if (index < value / 100 * 28) colors.primary else colors.surfaceVariant,
                topLeft = Offset(index * (segmentWidth + gap), 0f), size = Size(segmentWidth.coerceAtLeast(0f), size.height))
        }
    }
}

private fun widgetPreviewTimestamp(snapshot: UsageSnapshot, example: Boolean, old: Boolean, texts: TextResources): String =
    texts(when { example -> R.string.demo_value; old -> R.string.cached_value; else -> R.string.updated_value }, absoluteTime(snapshot.fetchedAt, true))
