package com.tsonglew.quodex.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.i18n.AndroidTextResources
import com.tsonglew.quodex.quotaApp
import com.tsonglew.quodex.sync.SyncScheduler
import com.tsonglew.quodex.ui.TokenHeatmapRenderer
import com.tsonglew.quodex.ui.quodexColorScheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Instant

class TokenHeatmapWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(220.dp, 160.dp), DpSize(280.dp, 180.dp), DpSize(360.dp, 220.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.quotaApp
        app.repository.initialize()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val initial = app.settings.widgetSettings(widgetId)
        provideContent {
            val state by app.repository.state.collectAsState()
            val settings by app.settings.widgetSettingsFlow(widgetId).collectAsState(initial)
            val locale by app.localeRevision.collectAsState()
            val time by app.wallClockRevision.collectAsState()
            key(locale, time) { Content(state, settings.second, context) }
        }
    }

    @Composable private fun Content(state: AppState, theme: String, context: Context) {
        val texts = AndroidTextResources(context)
        val size = LocalSize.current
        val systemDark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = theme == "dark" || theme == "system" && systemDark
        val colors = quodexColorScheme(dark)
        val foreground = ColorProvider(colors.onSurface)
        val muted = ColorProvider(colors.onSurfaceVariant)
        val large = context.resources.configuration.fontScale > 1.3f
        val compactLabels = large || size.width < 280.dp
        val tokens = if (state.demo) demoTokenUsage() else state.snapshot?.tokenUsage
        val trend = remember(tokens) { tokenTrend(tokens?.dailyBuckets, 30) }
        val stale = !state.demo && tokens != null && (state.tokenFailure != null || Instant.now().epochSecond - tokens.fetchedAt > 7200)
        val status = when {
            state.demo -> texts(R.string.sample_data)
            state.tokenLoading -> texts(R.string.updating_statistics)
            stale -> texts(R.string.cached)
            state.tokenFailure != null -> texts(R.string.token_heatmap_unavailable)
            tokens?.partial == true -> texts(R.string.token_heatmap_partial)
            else -> texts(R.string.last_value_days, 30)
        }
        val summary = if (trend != null) texts(R.string.token_heatmap_accessibility, trend.startDate, trend.endDate,
            exactTokenCount(trend.total), trend.knownDays, status) else texts(R.string.token_heatmap_unavailable)
        val launch = actionStartActivity(widgetLaunchIntent(context).putExtra(EXTRA_TOKEN_HEATMAP_LAUNCH, true))
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(ColorProvider(colors.background)).cornerRadius(24.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(texts(R.string.token_heatmap_widget_title), GlanceModifier.defaultWeight(),
                    style = TextStyle(color = foreground, fontSize = if (large) 10.sp else 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Button(if (state.tokenLoading) "…" else "↻", onClick = if (!state.connected && !state.demo) launch else actionRunCallback<RefreshTokenWidgetAction>(),
                    modifier = GlanceModifier.width(40.dp).height(32.dp).padding(0.dp).background(Color.Transparent)
                        .semantics { contentDescription = texts(R.string.refresh_token_statistics) },
                    style = TextStyle(color = muted, fontSize = if (large) 12.sp else 18.sp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color.Transparent), contentColor = muted), maxLines = 1)
            }
            Box(GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (trend != null) {
                    val map = remember(trend) { TokenHeatmap(trend) }
                    val chartWidth = (size.width.value - 24) * if (large) .48f else .55f
                    val chartHeight = (size.height.value - 80).coerceAtLeast(68f)
                    val bitmap = remember(map, chartWidth, chartHeight, dark, texts.locale) {
                        TokenHeatmapRenderer.render(map, (chartWidth * 2).toInt(), (chartHeight * 2).toInt(), dark, texts.locale)
                    }
                    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(bitmap), null, GlanceModifier.width(chartWidth.dp).fillMaxHeight(), contentScale = ContentScale.Fit)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(formatTokenCount(trend.total, true), style = TextStyle(color = foreground,
                                fontSize = if (large) 12.sp else 23.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                            if (!compactLabels) Text(texts(R.string.token_heatmap_tokens), style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
                            Spacer(GlanceModifier.height(if (compactLabels) 4.dp else 6.dp))
                            Text(texts(if (large) R.string.token_heatmap_known_days_short else R.string.token_heatmap_known_days, trend.knownDays, 30),
                                style = TextStyle(color = muted, fontSize = if (large) 7.sp else 10.sp), maxLines = 2)
                            Text(if (compactLabels) trend.endDate.toString() else texts(R.string.token_heatmap_through, trend.endDate),
                                style = TextStyle(color = muted, fontSize = if (large) 7.sp else 10.sp), maxLines = 2)
                        }
                    }
                } else {
                    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (!state.connected && !state.demo) texts(R.string.connect_your_codex) else texts(R.string.token_heatmap_unavailable),
                            style = TextStyle(color = foreground, fontSize = if (large) 10.sp else 15.sp), maxLines = 2)
                        Text(texts(R.string.token_heatmap_no_estimates), style = TextStyle(color = muted, fontSize = if (large) 8.sp else 11.sp), maxLines = 2)
                    }
                }
                // Keep the Xiaomi-tested direct Button PendingIntent leaf, separate from refresh.
                Button("", launch, GlanceModifier.fillMaxSize().padding(0.dp).background(Color.Transparent)
                    .semantics { contentDescription = texts(R.string.value_open_quodex, summary) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color.Transparent), contentColor = ColorProvider(Color.Transparent)), maxLines = 1)
            }
            Text(if (state.demo || tokens == null) status else "$status · ${absoluteTime(tokens.fetchedAt, true)}",
                style = TextStyle(color = muted, fontSize = if (large) 7.sp else 10.sp), maxLines = 2)
        }
    }
}

class TokenHeatmapWidgetReceiver : QuotaWidgetReceiver() {
    override val glanceAppWidget = TokenHeatmapWidget()
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        context.quotaApp.scope.launch {
            context.quotaApp.repository.initialize()
            if (context.quotaApp.repository.state.value.let { it.connected && !it.demo }) SyncScheduler.refreshTokens(context, force = false)
        }
    }
}

class RefreshTokenWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        if (!refreshLock.tryLock()) return
        try {
            refreshFromWidget(refresh = { refreshTokenWidget(context) },
                enqueue = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SyncScheduler.refreshTokens(context).result.get() } },
                update = { context.quotaApp.updateWidgets() })
        } finally { refreshLock.unlock() }
    }
    companion object { private val refreshLock = Mutex() }
}

internal suspend fun refreshTokenWidget(context: Context, force: Boolean = true): SyncResult {
    val repository = context.quotaApp.repository
    repository.initialize()
    if (repository.state.value.let { !it.connected || it.demo || it.tokenLoading }) return SyncResult.SUCCESS
    if (repository.state.value.snapshot == null) {
        val outcome = repository.refresh()
        if (repository.state.value.snapshot == null) return outcome
    }
    repository.refreshTokenUsage(force = force)
    return when (repository.state.value.tokenFailure) {
        FailureKind.NETWORK, FailureKind.SERVER, FailureKind.STORAGE -> SyncResult.RETRY
        FailureKind.LIMITED -> SyncResult.DEFERRED
        else -> SyncResult.SUCCESS
    }
}
