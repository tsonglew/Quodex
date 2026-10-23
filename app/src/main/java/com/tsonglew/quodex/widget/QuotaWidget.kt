package com.tsonglew.quodex.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.quotaApp
import com.tsonglew.quodex.sync.SyncScheduler
import kotlinx.coroutines.launch
import kotlin.math.ceil

open class QuotaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(140.dp, 150.dp), DpSize(140.dp, 230.dp), DpSize(280.dp, 150.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.quotaApp
        app.repository.initialize()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val preferences = app.settings.widgetSettingsFlow(widgetId)
        val initialPreferences = app.settings.widgetSettings(widgetId)
        provideContent {
            val state by app.repository.state.collectAsState()
            val config by preferences.collectAsState(initialPreferences)
            val timeRevision by app.wallClockRevision.collectAsState()
            androidx.compose.runtime.key(timeRevision) { Content(state, config.first, config.second, context) }
        }
    }

    @Composable
    private fun Content(state: AppState, remaining: Boolean, theme: String, context: Context) {
        val size = LocalSize.current
        androidx.compose.runtime.LaunchedEffect(size, state, remaining, theme) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.quotaApp.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.WIDGET_STATE,
                    size.width.value.toLong(), size.height.value.toLong(),
                    (context.resources.configuration.fontScale * 100).toLong(),
                    if (state.snapshot != null) 1 else 0, if (state.syncing) 1 else 0,
                    if (remaining) 1 else 0, listOf("system", "light", "dark").indexOf(theme).toLong())
            }
        }
        val systemDark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = theme == "dark" || theme == "system" && systemDark
        val background = ColorProvider(if (dark) Color(0xFF111215) else Color(0xFFF9FAFC))
        val foreground = ColorProvider(if (dark) Color(0xFFF2F3F5) else Color(0xFF17191D))
        val muted = ColorProvider(if (dark) Color(0xFFA4AAB5) else Color(0xFF747C88))
        val track = ColorProvider(if (dark) Color(0xFF282C33) else Color(0xFFE6E9EF))
        val largeFont = context.resources.configuration.fontScale > 1.3f
        val summary = widgetSummary(state, remaining, size, largeFont)
        if (largeFont) {
            LargeFontContent(state, remaining, background, foreground, muted, track, summary)
            return
        }
        if (size.height < 100.dp) {
            CompactContent(state, remaining, background, foreground, muted, track, summary)
            return
        }
        val notice = state.snapshot?.quotaNotice()
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(28.dp)
            .padding(horizontal = 12.dp, vertical = if (notice != null) 8.dp else 12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OpenAppArea(GlanceModifier.defaultWeight().height(controlHeight(context, 20.sp)), summary) {
                    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Text("Codex", style = TextStyle(color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    }
                }
                RefreshControl(state, muted, fontSize = 20.sp)
            }
            OpenAppArea(GlanceModifier.fillMaxWidth().defaultWeight(), summary) {
                Column(GlanceModifier.fillMaxSize()) {
                    Spacer(GlanceModifier.height(8.dp))
                    if (state.snapshot == null) {
                        Text("连接你的 Codex", style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                        Spacer(GlanceModifier.height(8.dp))
                        Text(state.failure?.userMessage() ?: "轻点打开 Quodex", style = TextStyle(color = muted, fontSize = 11.sp))
                    } else {
                        val windows = state.snapshot.windows.take(if (size.width >= 280.dp || size.height >= 230.dp) 2 else 1)
                        if (windows.isEmpty()) {
                            Text(notice?.title ?: "未提供周期额度", style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 2)
                            Spacer(GlanceModifier.height(6.dp))
                            Text(notice?.description ?: "请以账号实际权益为准。", style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 3)
                        } else if (size.width >= 280.dp && windows.size > 1) {
                            Row(GlanceModifier.fillMaxWidth()) {
                                windows.forEachIndexed { index, window ->
                                    if (index > 0) Spacer(GlanceModifier.width(18.dp))
                                    Column(GlanceModifier.defaultWeight()) { Window(window, remaining, foreground, muted, track, notice?.widgetLabel) }
                                }
                            }
                        } else windows.forEachIndexed { index, window ->
                            if (index > 0) Spacer(GlanceModifier.height(12.dp))
                            Window(window, remaining, foreground, muted, track, notice?.widgetLabel)
                        }
                        Spacer(GlanceModifier.defaultWeight())
                        Text(if (state.demo) "示例数据 · ${absoluteTime(state.snapshot.fetchedAt)}" else
                            "更新于 ${absoluteTime(state.snapshot.fetchedAt, true)}${if (state.failure != null) " · ${state.failure.userMessage()}" else ""}",
                            style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 2)
                    }
                }
            }
        }
    }

    private fun widgetSummary(state: AppState, remaining: Boolean, size: DpSize, largeFont: Boolean): String {
        val snapshot = state.snapshot
        val compact = size.height < 100.dp
        val windows = snapshot?.windows.orEmpty().let { entries ->
            if (compact) entries.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") } else entries
        }.take(if (!compact && (size.width >= 280.dp || size.height >= 230.dp)) 2 else 1)
        val restricted = snapshot?.allowed == false || snapshot?.limitReached == true
        return buildList {
            add("Codex")
            if (state.demo) add("示例数据")
            if (snapshot != null && state.failure != null) add("旧数据")
            add(if (remaining) "剩余额度" else "已用额度")
            if (snapshot == null) add("未连接，轻点连接账号")
            snapshot?.quotaNotice()?.let { add(it.title) }
            windows.forEach { window ->
                val value = if (restricted && (compact || largeFont)) "使用受限" else
                    window.percentageLabel(remaining) + if (window.usedPercent != null) "%" else ""
                add("${window.name} $value")
                if (!compact) add(resetLabel(window))
            }
            snapshot?.let { add("最后成功更新于 ${absoluteTime(it.fetchedAt, true)}") }
            state.failure?.let { add(it.userMessage()) }
            if (state.syncing) add("刷新中")
        }.joinToString("，")
    }

    @Composable
    private fun LargeFontContent(state: AppState, remaining: Boolean, background: ColorProvider,
        foreground: ColorProvider, muted: ColorProvider, track: ColorProvider, summary: String) {
        val size = LocalSize.current
        val compact = size.height < 100.dp
        val snapshot = state.snapshot
        val notice = snapshot?.quotaNotice()
        val restricted = snapshot?.allowed == false || snapshot?.limitReached == true
        val windows = snapshot?.windows.orEmpty().let { entries ->
            if (compact) entries.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") } else entries
        }.take(if (!compact && (size.width >= 280.dp || size.height >= 230.dp)) 2 else 1)
        val timestamp = snapshot?.let { absoluteTime(it.fetchedAt, true) } ?: "轻点连接"
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(20.dp)
            .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 6.dp)
            .semantics { contentDescription = summary }) {
            if (compact) {
                val window = windows.firstOrNull()
                val metered = window?.usedPercent != null && !restricted
                val value = when {
                    snapshot == null -> "未连接"
                    restricted -> "受限"
                    window != null -> window.percentageLabel(remaining) + if (metered) "%" else ""
                    notice?.title == "当前可用" -> "当前可用"
                    else -> "无周期"
                }
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OpenAppArea(GlanceModifier.defaultWeight().height(controlHeight(LocalContext.current, 10.sp)), summary) {
                        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            if (metered) {
                                Text(when { state.failure != null -> "旧"; state.demo -> "示例"; remaining -> "剩余"; else -> "已用" },
                                    style = TextStyle(color = muted, fontSize = 7.sp), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                                Text(value, style = TextStyle(color = foreground, fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium), maxLines = 1)
                            } else Text(value, style = TextStyle(color = foreground, fontSize = 10.sp),
                                modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                        }
                    }
                    RefreshControl(state, muted, 6.dp, 10.sp)
                }
                // Keep every compact variant within the 50 dp minimum, including legacy hosts.
                OpenAppArea(GlanceModifier.fillMaxWidth().defaultWeight(), summary) {
                    Column(GlanceModifier.fillMaxSize()) {
                        Spacer(GlanceModifier.defaultWeight())
                        Text(timestamp, style = TextStyle(color = muted, fontSize = 6.sp), maxLines = 1)
                    }
                }
            } else {
                OpenAppArea(GlanceModifier.fillMaxWidth().defaultWeight(), summary) {
                    Column(GlanceModifier.fillMaxSize()) {
                        if (windows.isEmpty()) {
                            Column {
                                Text(if (state.demo) "示例额度" else "Codex", style = TextStyle(color = muted, fontSize = 10.sp))
                                Text(if (snapshot == null) "未连接" else notice?.title ?: "无周期",
                                    style = TextStyle(color = foreground, fontSize = 12.sp), maxLines = 2)
                            }
                        } else if (size.width < 280.dp && windows.size > 1) {
                            windows.forEach { LargeWindow(it, state, remaining, foreground, muted, track) }
                        } else {
                            Row(GlanceModifier.fillMaxWidth()) {
                                windows.forEachIndexed { index, window ->
                                    if (index > 0) Spacer(GlanceModifier.width(12.dp))
                                    Column(GlanceModifier.defaultWeight()) {
                                        LargeWindow(window, state, remaining, foreground, muted, track)
                                    }
                                }
                            }
                        }
                        Spacer(GlanceModifier.defaultWeight())
                    }
                }
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OpenAppArea(GlanceModifier.defaultWeight().height(controlHeight(LocalContext.current, 12.sp)), summary) {
                        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            Text(timestamp, style = TextStyle(color = muted, fontSize = 8.sp), maxLines = 1)
                        }
                    }
                    RefreshControl(state, muted)
                }
            }
        }
    }

    @Composable
    private fun LargeWindow(window: QuotaWindow, state: AppState, remaining: Boolean, foreground: ColorProvider,
        muted: ColorProvider, track: ColorProvider) {
        val restricted = state.snapshot?.allowed == false || state.snapshot?.limitReached == true
        Column {
            Row(GlanceModifier.fillMaxWidth()) {
                Text(window.name, style = TextStyle(color = muted, fontSize = 8.sp),
                    modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                if (state.demo || state.failure != null) Text(if (state.demo) "示例" else "旧",
                    style = TextStyle(color = muted, fontSize = 7.sp), maxLines = 1)
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (remaining) "剩余" else "已用", style = TextStyle(color = muted, fontSize = 8.sp))
                Spacer(GlanceModifier.width(4.dp))
                Text(if (restricted) "受限" else window.percentageLabel(remaining) + if (window.usedPercent != null) "%" else "",
                    style = TextStyle(color = foreground,
                        fontSize = if (window.usedPercent != null && !restricted) 16.sp else 10.sp),
                    modifier = GlanceModifier.defaultWeight(), maxLines = 1)
            }
            if (window.usedPercent != null && !restricted) Segments(window, remaining, track, 3.dp)
            Text(resetLabel(window), style = TextStyle(color = muted, fontSize = 7.sp), maxLines = 1)
        }
    }

    // The tested Xiaomi launcher misses touch on Glance's clickable wrappers.
    // Glance Button emits a direct PendingIntent target on API 31+. Our matching
    // XML layouts use a TextView leaf to avoid the launcher's Button background.
    // Callers bound this area and keep the independent refresh control outside it.
    @Composable
    private fun OpenAppArea(modifier: GlanceModifier, summary: String, content: @Composable () -> Unit) {
        Box(modifier) {
            content()
            Button("", onClick = actionStartActivity(widgetLaunchIntent(LocalContext.current)),
                modifier = GlanceModifier.fillMaxSize().padding(0.dp).background(Color.Transparent)
                    .semantics { contentDescription = "$summary，打开 Quodex" },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = ColorProvider(Color.Transparent), contentColor = ColorProvider(Color.Transparent)),
                maxLines = 1)
        }
    }

    private fun controlTextPaint(context: Context, fontSize: TextUnit): TextPaint = TextPaint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize.value, context.resources.displayMetrics)
    }

    private fun controlHeight(context: Context, fontSize: TextUnit): Dp {
        // Match TextView's included font padding and the system's SP conversion,
        // including nonlinear accessibility scaling on newer Android versions.
        val metrics = controlTextPaint(context, fontSize).fontMetricsInt
        return ceil((metrics.bottom - metrics.top) / context.resources.displayMetrics.density).dp
    }

    private fun controlWidth(context: Context, fontSize: TextUnit, horizontalPadding: Dp): Dp {
        val paint = controlTextPaint(context, fontSize)
        val glyphWidth = maxOf(paint.measureText("↻"), paint.measureText("…"))
        return ceil(glyphWidth / context.resources.displayMetrics.density).dp + horizontalPadding * 2
    }

    @Composable
    private fun RefreshControl(state: AppState, color: ColorProvider, horizontalPadding: Dp = 8.dp,
        fontSize: TextUnit = 12.sp, width: Dp? = null) {
        val context = LocalContext.current
        Button(if (state.syncing) "…" else "↻",
            onClick = if (!state.connected && !state.demo) actionStartActivity(widgetLaunchIntent(LocalContext.current)) else actionRunCallback<RefreshWidgetAction>(),
            style = TextStyle(color = color, fontSize = fontSize),
            modifier = GlanceModifier.width(width ?: controlWidth(context, fontSize, horizontalPadding))
                .height(controlHeight(context, fontSize)).padding(0.dp).background(Color.Transparent)
                .semantics { contentDescription = "刷新额度" },
            colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color.Transparent), contentColor = color),
            maxLines = 1)
    }

    @Composable
    private fun CompactContent(state: AppState, remaining: Boolean, background: ColorProvider, foreground: ColorProvider,
        muted: ColorProvider, track: ColorProvider, summary: String) {
        val short = LocalSize.current.height < 70.dp
        val snapshot = state.snapshot
        val notice = snapshot?.quotaNotice()
        val window = snapshot?.windows?.firstOrNull { it.id.startsWith("codex:") || it.id.startsWith("demo:") }
        val restricted = snapshot?.allowed == false || snapshot?.limitReached == true
        val metered = window?.usedPercent != null && !restricted
        val label = if (metered) when (window?.durationSeconds) {
            604800L -> "Codex · 周"
            86400L -> "Codex · 日"
            else -> "Codex"
        } else "Codex"
        val value = when {
            snapshot == null -> "未连接"
            restricted -> "使用受限"
            window != null -> window.percentageLabel(remaining) + if (metered) "%" else ""
            else -> notice?.title ?: "未提供周期额度"
        }
        val timestamp = when {
            snapshot == null -> "轻点连接账号"
            state.demo -> "示例 · ${absoluteTime(snapshot.fetchedAt, true)}"
            state.failure != null -> "旧数据 · ${absoluteTime(snapshot.fetchedAt, true)}"
            else -> "更新 · ${absoluteTime(snapshot.fetchedAt, true)}"
        }
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(20.dp)
            .padding(horizontal = 8.dp, vertical = if (short) 4.dp else 6.dp)) {
            OpenAppArea(GlanceModifier.fillMaxWidth().defaultWeight(), summary) {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = TextStyle(color = foreground, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                        Text(value, style = TextStyle(color = foreground, fontSize = if (metered) (if (short) 16.sp else 18.sp) else 11.sp,
                            fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                    if (metered && !short) {
                        Spacer(GlanceModifier.height(3.dp))
                        Segments(requireNotNull(window), remaining, track, 5.dp)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OpenAppArea(GlanceModifier.defaultWeight().height(controlHeight(LocalContext.current, 14.sp)), summary) {
                    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Text(if (metered) "${if (remaining) "剩余" else "已用"} · $timestamp" else timestamp,
                            style = TextStyle(color = muted, fontSize = 8.sp), maxLines = 1)
                    }
                }
                RefreshControl(state, muted, fontSize = 14.sp, width = 24.dp)
            }
        }
    }

    @Composable
    private fun Window(window: QuotaWindow, remaining: Boolean, foreground: ColorProvider, muted: ColorProvider, track: ColorProvider, note: String?) {
        Column {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(window.name, style = TextStyle(color = foreground, fontSize = 11.sp), modifier = GlanceModifier.defaultWeight(), maxLines = 2)
                Text("${window.percentageLabel(remaining)}${if (window.usedPercent != null) "%" else ""}",
                    style = TextStyle(color = foreground, fontSize = if (window.usedPercent == null) 13.sp else 25.sp, fontWeight = FontWeight.Medium))
            }
            Spacer(GlanceModifier.height(4.dp))
            if (window.usedPercent != null) {
                Segments(window, remaining, track, 10.dp)
            } else Text("用量数据未返回", style = TextStyle(color = muted, fontSize = 10.sp))
            Spacer(GlanceModifier.height(4.dp))
            Text("${note ?: if (remaining) "剩余" else "已用"} · ${resetLabel(window)}", style = TextStyle(color = muted, fontSize = 9.sp), maxLines = 2)
        }
    }

    @Composable
    private fun Segments(window: QuotaWindow, remaining: Boolean, track: ColorProvider, height: androidx.compose.ui.unit.Dp) {
        Row(GlanceModifier.fillMaxWidth()) {
            // Glance containers support at most ten direct children.
            repeat(4) { group ->
                Row(GlanceModifier.defaultWeight()) {
                    repeat(7) { offset ->
                        val index = group * 7 + offset
                        Box(GlanceModifier.defaultWeight().height(height).padding(horizontal = 1.dp)) {
                            Box(GlanceModifier.fillMaxSize().background(
                                if (window.displayedPercent(remaining)?.let { index < it / 100 * 28 } == true) ColorProvider(Color(0xFF4796EF)) else track)) {}
                        }
                    }
                }
            }
        }
    }
}

class SlimQuotaWidget : QuotaWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(130.dp, 50.dp), DpSize(130.dp, 70.dp), DpSize(280.dp, 70.dp)))
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val app = context.quotaApp
        app.repository.initialize()
        refreshFromWidget(
            refresh = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.diagnostics.record(com.tsonglew.quodex.diagnostics.DiagnosticEvent.WIDGET_CLICK) }; app.repository.refresh(force = true) },
            enqueue = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SyncScheduler.refresh(context).result.get() } },
            update = { app.updateWidgets() },
        )
    }
}

open class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuotaWidget()
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        context.quotaApp.scope.launch { context.quotaApp.reconcileSync() }
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        context.quotaApp.scope.launch {
            appWidgetIds.forEach { context.quotaApp.settings.deleteWidget(it) }
            context.quotaApp.reconcileSync()
        }
    }
}

class SlimQuotaWidgetReceiver : QuotaWidgetReceiver() {
    override val glanceAppWidget = SlimQuotaWidget()
}
