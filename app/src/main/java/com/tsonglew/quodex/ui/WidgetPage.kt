package com.tsonglew.quodex.ui

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
import java.time.Instant

/** Layout illustrations use the same window selection as the corresponding desktop widgets. */
@Composable
internal fun WidgetPage(state: AppState, prefs: PreferencesState) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val example = remember { demoSnapshot(Instant.now().epochSecond) }
    val snapshot = state.snapshot ?: example
    val isExample = state.snapshot == null || state.demo
    val isOld = !isExample && state.failure != null
    var manualWidget by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().testTag("widget-page")) {
        Text("桌面小组件", fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text("选择布局，直接添加到桌面。", fontSize = 12.sp, lineHeight = 19.sp,
            color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isExample) Icons.Outlined.Visibility else Icons.Outlined.Info, null,
                Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            Text(if (isExample) "布局示意 · 示例数据，非真实额度" else "布局示意 · 最近成功更新的数据",
                fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        }
        if (state.snapshot == null) {
            Spacer(Modifier.height(6.dp))
            Text("未连接时，桌面上的小组件会提示连接账号。", fontSize = 12.sp, lineHeight = 19.sp,
                color = colors.onSurfaceVariant)
        }
        if (isOld) {
            Spacer(Modifier.height(6.dp))
            Text("${state.failure?.userMessage()}。预览保留最后成功的数据。", fontSize = 12.sp, lineHeight = 19.sp,
                color = colors.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(16.dp))

        WidgetLayoutPanel("标准组件", "额度与重置时间，随大小调整。", "添加标准组件",
            "add-widget-button", onAdd = {
                if (!requestWidgetPin(context, QuotaWidgetReceiver::class.java)) manualWidget = "Codex 额度"
            }) {
            StandardWidgetIllustration(snapshot, prefs.showRemaining, isExample, isOld)
        }
        Spacer(Modifier.height(18.dp))
        WidgetLayoutPanel("紧凑组件 · 2×1", "主要额度与更新时间，占用更少空间。", "添加 2×1 紧凑组件",
            "add-slim-widget-button", onAdd = {
                if (!requestWidgetPin(context, SlimQuotaWidgetReceiver::class.java)) manualWidget = "Codex 额度 · 2×1"
            }) {
            CompactWidgetIllustration(snapshot, prefs.showRemaining, isExample, isOld)
        }
        Spacer(Modifier.height(14.dp))
        Text("实际布局随桌面尺寸与系统字号调整。长按小组件可调整大小和显示方式。",
            fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
    }

    manualWidget?.let { widgetName ->
        AlertDialog(onDismissRequest = { manualWidget = null }, title = { Text("从桌面添加") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前桌面未接受直接添加，请手动选择小组件。")
                Text("长按桌面空白处 → 小组件 → Quodex → $widgetName。")
            }
        }, confirmButton = {
            TextButton(onClick = { manualWidget = null }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text("知道了") }
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
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surface) {
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
    val colors = MaterialTheme.colorScheme
    val windows = snapshot.windows.take(2)
    val notice = snapshot.quotaNotice()
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
        val value = if (showRestriction) "使用受限" else window.percentageLabel(remaining) +
            if (window.usedPercent != null) "%" else ""
        WidgetPreviewValue(window.name, value, window.usedPercent != null && !showRestriction)
        Spacer(Modifier.height(6.dp))
        when {
            showRestriction -> Unit
            window.usedPercent != null -> WidgetPreviewSegments(window, remaining)
            else -> Text("用量数据未返回", fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text("${if (remaining) "剩余" else "已用"} · ${resetLabel(window)}", fontSize = 12.sp,
            lineHeight = 19.sp, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    Text(widgetPreviewTimestamp(snapshot, example, old), fontSize = 12.sp, lineHeight = 19.sp,
        color = colors.onSurfaceVariant)
}

@Composable
private fun CompactWidgetIllustration(snapshot: UsageSnapshot, remaining: Boolean, example: Boolean, old: Boolean) {
    val colors = MaterialTheme.colorScheme
    // The single-row widget never promotes a Review or other extra limit to the main quota.
    val window = snapshot.windows.firstOrNull { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
    val restricted = snapshot.allowed == false || snapshot.limitReached == true
    val metered = window?.usedPercent != null && !restricted
    val label = if (metered) when (window?.durationSeconds) {
        604800L -> "Codex · 周"
        86400L -> "Codex · 日"
        else -> "Codex"
    } else "Codex"
    val value = when {
        restricted -> "使用受限"
        window != null -> window.percentageLabel(remaining) + if (metered) "%" else ""
        else -> snapshot.quotaNotice()?.title ?: "未提供周期额度"
    }
    WidgetPreviewValue(label, value, metered)
    if (metered) {
        Spacer(Modifier.height(8.dp))
        WidgetPreviewSegments(requireNotNull(window), remaining)
    } else if (window != null && !restricted) {
        Spacer(Modifier.height(6.dp))
        Text("用量数据未返回", fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (metered) "${if (remaining) "剩余" else "已用"} · " else ""}${widgetPreviewTimestamp(snapshot, example, old)}",
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
    val value = window.displayedPercent(remaining) ?: return
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(6.dp).semantics {
        contentDescription = "${window.name}，${if (remaining) "剩余" else "已用"} ${window.percentageLabel(remaining)}%"
    }) {
        val gap = 2.dp.toPx()
        val segmentWidth = (size.width - gap * 27) / 28
        repeat(28) { index ->
            drawRect(if (index < value / 100 * 28) colors.primary else colors.surfaceVariant,
                topLeft = Offset(index * (segmentWidth + gap), 0f), size = Size(segmentWidth.coerceAtLeast(0f), size.height))
        }
    }
}

private fun widgetPreviewTimestamp(snapshot: UsageSnapshot, example: Boolean, old: Boolean): String =
    "${when { example -> "示例"; old -> "旧数据"; else -> "更新" }} · ${absoluteTime(snapshot.fetchedAt, true)}"
