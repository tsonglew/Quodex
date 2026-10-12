package com.tsonglew.quotapal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import com.tsonglew.quotapal.data.*
import com.tsonglew.quotapal.quotaApp
import com.tsonglew.quotapal.sync.SyncScheduler
import kotlinx.coroutines.launch

open class QuotaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(DpSize(140.dp, 150.dp), DpSize(140.dp, 230.dp), DpSize(280.dp, 150.dp)))
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
                context.quotaApp.diagnostics.record(com.tsonglew.quotapal.diagnostics.DiagnosticEvent.WIDGET_STATE,
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
        if (context.resources.configuration.fontScale > 1.3f) {
            LargeFontContent(state, remaining, background, foreground, muted, track)
            return
        }
        if (size.height < 100.dp) {
            CompactContent(state, remaining, background, foreground, muted, track)
            return
        }
        val notice = state.snapshot?.quotaNotice()
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(28.dp)
            .padding(horizontal = 12.dp, vertical = if (notice != null) 8.dp else 12.dp)
            .clickable(actionStartActivity(widgetLaunchIntent(LocalContext.current)))) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Codex", style = TextStyle(color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight().openApp())
                Text(if (state.syncing) "…" else "↻", style = TextStyle(color = muted, fontSize = 20.sp), modifier = GlanceModifier.padding(horizontal = 8.dp)
                    .semantics { contentDescription = "刷新额度" }
                    .clickable(if (!state.connected && !state.demo) actionStartActivity(widgetLaunchIntent(LocalContext.current)) else actionRunCallback<RefreshWidgetAction>()))
            }
            Spacer(GlanceModifier.height(8.dp))
            if (state.snapshot == null) {
                Text("连接你的 Codex", style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.openApp())
                Spacer(GlanceModifier.height(8.dp))
                Text(state.failure?.userMessage() ?: "轻点打开 QuotaPal", style = TextStyle(color = muted, fontSize = 11.sp), modifier = GlanceModifier.openApp())
            } else {
                val windows = state.snapshot.windows.take(if (size.width >= 280.dp || size.height >= 230.dp) 2 else 1)
                if (windows.isEmpty()) {
                    Text(notice?.title ?: "未提供周期额度", style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 2, modifier = GlanceModifier.openApp())
                    Spacer(GlanceModifier.height(6.dp))
                    Text(notice?.description ?: "请以账号实际权益为准。", style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 3, modifier = GlanceModifier.openApp())
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
                    style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 2, modifier = GlanceModifier.openApp())
            }
        }
    }

    @Composable
    private fun LargeFontContent(state: AppState, remaining: Boolean, background: ColorProvider,
        foreground: ColorProvider, muted: ColorProvider, track: ColorProvider) {
        val size = LocalSize.current
        val compact = size.height < 100.dp
        val snapshot = state.snapshot
        val notice = snapshot?.quotaNotice()
        val restricted = snapshot?.allowed == false || snapshot?.limitReached == true
        val windows = snapshot?.windows.orEmpty().let { entries ->
            if (compact) entries.filter { it.id.startsWith("codex:") || it.id.startsWith("demo:") } else entries
        }.take(if (!compact && (size.width >= 280.dp || size.height >= 230.dp)) 2 else 1)
        val timestamp = snapshot?.let { absoluteTime(it.fetchedAt, true) } ?: "轻点连接"
        val summary = listOfNotNull("Codex", if (state.demo) "示例数据" else null,
            if (remaining) "剩余额度" else "已用额度", if (snapshot == null) "未连接，轻点连接账号" else null, notice?.title,
            windows.joinToString { "${it.name} ${it.percentageLabel(remaining)}${if (it.usedPercent != null) "%" else ""}，${resetLabel(it)}" },
            snapshot?.let { "最后成功更新于 $timestamp" }, state.failure?.userMessage()).joinToString("，")
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(background).cornerRadius(20.dp)
            .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 6.dp)
            .semantics { contentDescription = summary }.clickable(actionStartActivity(widgetLaunchIntent(LocalContext.current)))) {
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
                    if (metered) {
                        Text(when { state.failure != null -> "旧"; state.demo -> "示例"; remaining -> "剩余"; else -> "已用" },
                            style = TextStyle(color = muted, fontSize = 7.sp), modifier = GlanceModifier.defaultWeight().openApp(), maxLines = 1)
                        Text(value, style = TextStyle(color = foreground, fontSize = 10.sp,
                            fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.openApp())
                    } else Text(value, style = TextStyle(color = foreground, fontSize = 10.sp),
                        modifier = GlanceModifier.defaultWeight().openApp(), maxLines = 1)
                    RefreshControl(state, muted, 6.dp, 10.sp)
                }
                Spacer(GlanceModifier.defaultWeight())
                // Keep every compact variant within the 50 dp minimum, including legacy hosts.
                Text(timestamp, style = TextStyle(color = muted, fontSize = 6.sp), maxLines = 1, modifier = GlanceModifier.openApp())
            } else {
                if (windows.isEmpty()) {
                    Column(GlanceModifier.openApp()) {
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
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(timestamp, style = TextStyle(color = muted, fontSize = 8.sp), modifier = GlanceModifier.defaultWeight().openApp(), maxLines = 1)
                    RefreshControl(state, muted)
                }
            }
        }
    }

    @Composable
    private fun LargeWindow(window: QuotaWindow, state: AppState, remaining: Boolean, foreground: ColorProvider,
        muted: ColorProvider, track: ColorProvider) {
        val restricted = state.snapshot?.allowed == false || state.snapshot?.limitReached == true
        Column(GlanceModifier.openApp()) {
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

    // Bind visible content too: some hosts do not deliver a child tap to the
    // background's listener. Keep the refresh control outside these body targets.
    @Composable
    private fun GlanceModifier.openApp(): GlanceModifier =
        clickable(actionStartActivity(widgetLaunchIntent(LocalContext.current)))

    @Composable
    private fun RefreshControl(state: AppState, color: ColorProvider, horizontalPadding: androidx.compose.ui.unit.Dp = 8.dp,
        fontSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
        Text(if (state.syncing) "…" else "↻", style = TextStyle(color = color, fontSize = fontSize),
            // Fixed text widths use a sizing TextView on legacy hosts, which also inflates row height.
            modifier = GlanceModifier.padding(horizontal = horizontalPadding).semantics { contentDescription = "刷新额度" }
                .clickable(if (!state.connected && !state.demo) actionStartActivity(widgetLaunchIntent(LocalContext.current)) else actionRunCallback<RefreshWidgetAction>()))
    }

    @Composable
    private fun CompactContent(state: AppState, remaining: Boolean, background: ColorProvider, foreground: ColorProvider,
        muted: ColorProvider, track: ColorProvider) {
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
            .padding(horizontal = 8.dp, vertical = if (short) 4.dp else 6.dp)
            .clickable(actionStartActivity(widgetLaunchIntent(LocalContext.current)))) {
            Row(GlanceModifier.fillMaxWidth().openApp(), verticalAlignment = Alignment.CenterVertically) {
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
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (metered) "${if (remaining) "剩余" else "已用"} · $timestamp" else timestamp,
                    style = TextStyle(color = muted, fontSize = 8.sp), modifier = GlanceModifier.defaultWeight().openApp(), maxLines = 1)
                Text(if (state.syncing) "…" else "↻", style = TextStyle(color = muted, fontSize = 14.sp), modifier = GlanceModifier.width(24.dp)
                    .semantics { contentDescription = "刷新额度" }
                    .clickable(if (!state.connected && !state.demo) actionStartActivity(widgetLaunchIntent(LocalContext.current)) else actionRunCallback<RefreshWidgetAction>()))
            }
        }
    }

    @Composable
    private fun Window(window: QuotaWindow, remaining: Boolean, foreground: ColorProvider, muted: ColorProvider, track: ColorProvider, note: String?) {
        Column(GlanceModifier.openApp()) {
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
    // The horizontal widget has one content structure at every supported width.
    // Send one flexible RemoteViews tree rather than asking the host to choose
    // between size-mapped trees with separate action bindings.
    override val sizeMode = SizeMode.Single
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val app = context.quotaApp
        // Record callback delivery before initialization, so an initialization
        // failure cannot be mistaken for a launcher that never delivered the tap.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.diagnostics.record(com.tsonglew.quotapal.diagnostics.DiagnosticEvent.WIDGET_CLICK)
        }
        app.repository.initialize()
        refreshFromWidget(
            refresh = { app.repository.refresh(force = true) },
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
