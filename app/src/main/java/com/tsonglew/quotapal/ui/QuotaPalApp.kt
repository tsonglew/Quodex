package com.tsonglew.quotapal.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quotapal.LoginState
import com.tsonglew.quotapal.MainViewModel
import com.tsonglew.quotapal.quotaApp
import com.tsonglew.quotapal.data.*
import com.tsonglew.quotapal.widget.QuotaWidgetReceiver
import com.tsonglew.quotapal.widget.SlimQuotaWidgetReceiver
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.tsonglew.quotapal.diagnostics.DiagnosticEvent

@Composable
fun QuotaPalApp(model: MainViewModel) {
    val timeRevision by LocalContext.current.quotaApp.wallClockRevision.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val login by model.login.collectAsStateWithLifecycle()
    val operationError by model.error.collectAsStateWithLifecycle()
    val clock by produceState(Instant.now().epochSecond, timeRevision) {
        while (true) { value = Instant.now().epochSecond; delay(30_000) }
    }
    var tab by rememberSaveable { mutableStateOf("quota") }
    val diagnosticApp = LocalContext.current.quotaApp
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(tab, state, prefs, configuration) {
        withContext(Dispatchers.IO) {
            diagnosticApp.diagnostics.record(DiagnosticEvent.UI_STATE,
                listOf("quota", "widgets", "settings").indexOf(tab).toLong(),
                if (state.initialized) 1 else 0, if (state.connected) 1 else 0,
                if (state.syncing) 1 else 0, if (state.snapshot != null) 1 else 0,
                state.failure?.ordinal?.toLong() ?: -1,
                configuration.screenWidthDp.toLong(), configuration.screenHeightDp.toLong(),
                (configuration.fontScale * 100).toLong(), configuration.uiMode.toLong(),
                if (prefs.showRemaining) 1 else 0, listOf("system", "light", "dark").indexOf(prefs.theme).toLong())
        }
    }
    QuotaTheme(prefs.theme) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(Triple("quota", "额度", Icons.Outlined.DonutLarge), Triple("widgets", "小组件", Icons.Outlined.Widgets),
                    Triple("settings", "设置", Icons.Outlined.Tune)).forEach { (id, label, icon) ->
                    NavigationBarItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, null) },
                        label = { Text(label) }, modifier = Modifier.testTag("tab-$id"),
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }) { padding ->
            key(tab) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(34)
                    Spacer(Modifier.width(10.dp))
                    Text("QuotaPal", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (tab == "quota" && state.snapshot != null) IconButton(onClick = model::refresh, enabled = !state.syncing && !state.demo,
                        modifier = Modifier.testTag("refresh-button")) {
                        if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, "刷新额度")
                    }
                }
                Spacer(Modifier.height(32.dp))
                when (tab) {
                    "quota" -> if (!state.initialized) CircularProgressIndicator() else key(timeRevision) { Dashboard(state, prefs, model, clock) }
                    "widgets" -> WidgetPage(state, prefs)
                    else -> SettingsPage(state, prefs, model)
                }
                Spacer(Modifier.height(28.dp))
            }
            }
        }
        operationError?.let { message ->
            AlertDialog(onDismissRequest = model::dismissError,
                title = { Text("操作未完成") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } })
        }
        login?.let { LoginDialog(it, model::cancelLogin, model::startLogin) }
    }
}

@Composable
fun BrandMark(size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(Color(0xFF17191D)), contentAlignment = Alignment.Center) {
        Text("Q", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = (size * 0.65).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Dashboard(state: AppState, prefs: PreferencesState, model: MainViewModel, now: Long) {
    if (state.snapshot == null) {
        PageTitle("额度，心中有数。", "把 Codex 的剩余额度与重置时间，放到你的 Android 桌面。")
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, tint = QuotaBlue, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp)); Text("Codex", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("连接现有账号，查看订阅额度。凭据加密保存在此设备，额度直接从 OpenAI 获取。", lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
                Button(onClick = model::startLogin, modifier = Modifier.fillMaxWidth().testTag("connect-button"), contentPadding = PaddingValues(16.dp)) {
                    Text(if (state.connected) "重新连接 Codex" else "连接 Codex")
                }
                TextButton(onClick = model::demo, modifier = Modifier.fillMaxWidth().testTag("demo-button")) { Text("先看看示例") }
            }
        }
        Spacer(Modifier.height(24.dp))
        DetailRow(Icons.Outlined.Widgets, "桌面即刻可见", "紧凑与宽版布局，随桌面自由调整")
        DetailRow(Icons.Outlined.Lock, "凭据留在设备", "无需创建 QuotaPal 账号")
        DetailRow(Icons.Outlined.Schedule, "知道数据的新鲜度", "后台更新可能延迟，随时手动刷新")
        return
    }
    Column(Modifier.testTag("dashboard")) {
        PageTitle("你的 Codex 额度", if (state.demo) "看看桌面上的额度，会是什么样子。" else "留一点余量，安心开始下一段工作。")
        if (state.demo) {
            Notice("示例数据", "当前仅用于预览，不代表你的真实额度。", Icons.Outlined.Visibility)
            Spacer(Modifier.height(16.dp))
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp)); Text("Codex", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    state.snapshot.plan?.let {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(7.dp)) {
                            Text(it.uppercase(), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.failure == null) Color(0xFF5A9B7C) else QuotaAmber))
                }
                Spacer(Modifier.height(28.dp))
                QuotaStatusNotice(state.snapshot)
                state.snapshot.windows.forEachIndexed { index, window ->
                    if (index > 0) { Spacer(Modifier.height(24.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp)) }
                    QuotaWindowCard(window, prefs.showRemaining, now = now)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(5.dp))
                    Text("${if (state.demo) "示例更新于" else "更新于"} ${absoluteTime(state.snapshot.fetchedAt, true)}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.snapshot.resetCredits != null) Text("${state.snapshot.resetCredits} 次重置可用", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val stale = now - state.snapshot.fetchedAt > prefs.refreshMinutes * 120
        if (state.failure != null || stale) {
            Notice(state.failure?.userMessage() ?: "数据较旧", "仍显示最后成功获取的额度。", Icons.Outlined.Info)
            if (state.failure in listOf(FailureKind.AUTH, FailureKind.STORAGE)) TextButton(onClick = model::startLogin) { Text("重新连接") }
        } else Text(if (state.demo) "连接账号后，这里会显示真实额度。" else "后台目标间隔 ${prefs.refreshMinutes} 分钟 · 系统可能延迟更新",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        if (state.demo) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = model::startLogin, modifier = Modifier.fillMaxWidth()) { Text("连接我的 Codex") }
        }
    }
}

@Composable
fun QuotaStatusNotice(snapshot: UsageSnapshot) {
    snapshot.quotaNotice()?.let { notice ->
        Notice(notice.title, notice.description, Icons.Outlined.Info)
        if (snapshot.windows.isNotEmpty()) Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun QuotaWindowCard(window: QuotaWindow, remaining: Boolean, compact: Boolean = false, now: Long = Instant.now().epochSecond) {
    val value = window.displayedPercent(remaining)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(window.name, fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Text(resetLabel(window, now), fontSize = if (compact) 10.sp else 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(window.percentageLabel(remaining), fontFamily = if (value == null) FontFamily.Default else FontFamily.Monospace,
                fontSize = if (value == null) 16.sp else if (compact) 32.sp else 46.sp,
                fontWeight = FontWeight.Normal, letterSpacing = if (value == null) 0.sp else (-2).sp)
            Text(if (value == null) "" else "%", fontSize = 15.sp, modifier = Modifier.padding(bottom = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
    if (value == null) {
        Text("服务暂未提供有效的用量数据，请稍后刷新。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        SegmentedBar(value, "${window.name}，${if (remaining) "剩余" else "已用"} ${window.percentageLabel(remaining)}%")
        Spacer(Modifier.height(6.dp))
        Text(if (remaining) "剩余额度" else "已用额度", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SegmentedBar(percent: Double?, description: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(17.dp).semantics { contentDescription = description }) {
        val count = 40
        val gap = 3.dp.toPx()
        val width = (size.width - gap * (count - 1)) / count
        repeat(count) { i ->
            drawRoundRect(if (percent != null && i < percent / 100 * count) QuotaBlue else track,
                Offset(i * (width + gap), 0f), Size(width.coerceAtLeast(1f), size.height), CornerRadius(1.dp.toPx()))
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Notice(title: String, subtitle: String, icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp)); Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp)); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WidgetPage(state: AppState, prefs: PreferencesState) {
    val context = LocalContext.current
    var help by remember { mutableStateOf(false) }
    PageTitle("留在桌面，一眼可见。", "2×1 横条、紧凑与宽版布局。长按小组件可调整大小与显示方式。")
    val preview = state.snapshot ?: remember { demoSnapshot(Instant.now().epochSecond) }
    if (state.snapshot == null || state.demo) Text("布局预览 · 示例数据", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row { Text("Codex", fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("${absoluteTime(preview.fetchedAt)}", fontSize = 11.sp) }
            Spacer(Modifier.height(20.dp))
            QuotaStatusNotice(preview)
            preview.windows.take(2).forEachIndexed { i, window ->
                if (i > 0) Spacer(Modifier.height(18.dp))
                QuotaWindowCard(window, prefs.showRemaining, compact = true)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    DetailRow(Icons.Outlined.AspectRatio, "新增 2×1 横条", "只占一行，显示主要额度或账号状态；标准组件保留更多信息")
    DetailRow(Icons.Outlined.Refresh, "主动刷新", "更新时间始终可见，多个组件共享数据")
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, SlimQuotaWidgetReceiver::class.java), null, null)
        else help = true
    }, modifier = Modifier.fillMaxWidth().testTag("add-slim-widget-button"), contentPadding = PaddingValues(16.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("添加 2×1 紧凑组件")
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, QuotaWidgetReceiver::class.java), null, null)
        else help = true
    }, modifier = Modifier.fillMaxWidth().testTag("add-widget-button"), contentPadding = PaddingValues(16.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("添加标准组件")
    }
    if (help) Text("长按桌面空白处 → 小组件 → QuotaPal，即可添加。", Modifier.padding(top = 12.dp), fontSize = 13.sp)
}

@Composable
private fun SettingsPage(state: AppState, prefs: PreferencesState, model: MainViewModel) {
    var confirmLogout by remember { mutableStateOf(false) }
    PageTitle("按你的习惯。", "显示、更新与账号连接，都在这里。")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("显示方式", fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示剩余额度", Modifier.weight(1f), fontSize = 14.sp)
                Switch(prefs.showRemaining, onCheckedChange = model::showRemaining)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp)); Text("外观", fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) ->
                    FilterChip(prefs.theme == id, onClick = { model.theme(id) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.testTag("theme-$id"))
                }
            }
            Spacer(Modifier.height(12.dp)); Text("后台目标间隔", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15L, 30L, 60L).forEach { minutes -> FilterChip(prefs.refreshMinutes == minutes, onClick = { model.refreshMinutes(minutes) }, label = { Text("$minutes 分钟", fontSize = 12.sp) }) }
            }
            Text("后台刷新受系统省电与网络影响，可能延迟。持续失败时每轮最多尝试 3 次，之后等待下一周期或手动刷新。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(18.dp))
    BackgroundGuide()
    Spacer(Modifier.height(18.dp))
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Codex 连接", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp)); Text(if (state.demo) "示例模式" else if (state.connected) "已连接 · 凭据在本机加密保存" else "尚未连接", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.failure?.let { Spacer(Modifier.height(8.dp)); Text(it.userMessage(), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = model::startLogin) { Text(if (state.connected) "重新连接" else "连接账号") }
            if (state.connected || state.demo) TextButton(onClick = { confirmLogout = true }, modifier = Modifier.testTag("logout-button")) { Text(if (state.demo) "退出示例模式" else "退出并清除本机数据", color = MaterialTheme.colorScheme.error) }
        }
    }
    DiagnosticEntry()
    PrivacyNoticeEntry()
    Spacer(Modifier.height(24.dp))
    Text("QuotaPal ${com.tsonglew.quotapal.BuildConfig.VERSION_NAME}\n独立第三方工具 · 连接能力处于实验阶段\n应用仅获取额度；登录凭据的权限可能覆盖更多能力。", fontSize = 11.sp,
        lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("清除本机连接？") },
        text = { Text("本机凭据、额度缓存与后台任务将被清除，桌面小组件恢复为未连接。远程授权不会在此操作中撤销。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout() }, modifier = Modifier.testTag("confirm-logout")) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable
private fun LoginDialog(login: LoginState, cancel: () -> Unit, retry: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = cancel, title = { Text("连接 Codex") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("在 OpenAI 页面完成设备登录。不要在 QuotaPal 输入账号密码。", lineHeight = 22.sp)
            Text("首次使用需在 ChatGPT 安全设置中启用设备码登录；工作区账号可能需要管理员开启。", fontSize = 12.sp,
                lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (login.error != null) Text(login.error, color = MaterialTheme.colorScheme.error)
            else if (login.challenge == null) CircularProgressIndicator(Modifier.size(24.dp))
            else {
                Text("输入这次登录码", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(login.challenge.userCode, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                Text("等待授权完成 · 15 分钟内有效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("设备登录与额度接口为实验性兼容接入。凭据加密保存在本机，应用不执行模型请求或消耗重置次数。", fontSize = 11.sp,
                lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DiagnosticEntry()
    PrivacyNoticeEntry()
        }
    }, confirmButton = {
        if (login.error != null) TextButton(onClick = retry) { Text("重新获取") }
        else TextButton(enabled = login.challenge != null, onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CodexApi.VERIFICATION_URL)))
        }) { Text("打开 OpenAI 登录页") }
    }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}

@Composable
private fun DiagnosticEntry() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Text("问题排查", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp))
    Text("本机保留最近 200 条事件和 7 天按小时统计，不自动上传。复现问题后可预览并分享给维护者。", fontSize = 12.sp)
    TextButton(onClick = {
        // Dialog state must return to Android's main dispatcher, including
        // when a Compose test supplies an unconfined effect interceptor.
        scope.launch(Dispatchers.Main.immediate) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = context.quotaApp
                    val power = context.getSystemService(android.os.PowerManager::class.java)
                    val activity = context.getSystemService(android.app.ActivityManager::class.java)
                    val network = context.getSystemService(android.net.ConnectivityManager::class.java)
                    val caps = network.getNetworkCapabilities(network.activeNetwork)
                    "QuotaPal ${com.tsonglew.quotapal.BuildConfig.VERSION_NAME} diagnostic schema=1\n" +
                        "Android SDK=${android.os.Build.VERSION.SDK_INT}\n" +
                        "powerSave=${power.isPowerSaveMode} idle=${power.isDeviceIdleMode} batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)}\n" +
                        "backgroundRestricted=${activity.isBackgroundRestricted}\n" +
                        "networkValidated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}\n" +
                        com.tsonglew.quotapal.widget.widgetDiagnosticReport(context) +
                        com.tsonglew.quotapal.sync.SyncScheduler.diagnosticReport(context) +
                        app.diagnostics.report()
                }
            }
            result.onSuccess { report = it }.onFailure { failed = true }
        }
    }, modifier = Modifier.testTag("diagnostics-preview")) { Text("预览诊断报告") }
    TextButton(onClick = { scope.launch(Dispatchers.Main.immediate) { val result = withContext(Dispatchers.IO) { runCatching { context.quotaApp.diagnostics.clear() } }; result.onFailure { failed = true } } }, modifier = Modifier.testTag("diagnostics-clear")) { Text("清除诊断记录") }
    report?.let { text ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text("诊断报告") },
            text = { SelectionContainer { Text(text, fontSize = 11.sp, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("diagnostics-report")) } },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "分享诊断报告")) }.onFailure { failed = true }
            }) { Text("分享") } },
            dismissButton = { TextButton(onClick = { report = null }, modifier = Modifier.testTag("diagnostics-close")) { Text("关闭") } })
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, title = { Text("诊断操作未完成") },
        text = { Text("请检查存储空间或分享应用后重试。") },
        confirmButton = { TextButton(onClick = { failed = false }) { Text("知道了") } })
}
