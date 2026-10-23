package com.tsonglew.quodex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
) {
    var period by rememberSaveable { mutableIntStateOf(7) }
    var details by rememberSaveable { mutableStateOf(false) }
    val sample = remember { demoTokenUsage() }
    val displayed = if (demo) sample else usage
    val trend = remember(displayed, period) { tokenTrend(displayed?.dailyBuckets, period) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("token-usage-card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Token 使用趋势", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                modifier = Modifier.semantics { heading() })
            Text(if (demo) "示例数据 · 仅用于预览" else "账号级 token 总量 · 按服务端日期统计", fontSize = 12.sp, color = secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { days ->
                    FilterChip(selected = period == days, onClick = { period = days },
                        label = { Text("近 $days 天") }, modifier = Modifier.testTag("token-trend-$days"))
                }
            }
            if (trend != null) {
                Text("截至 ${trend.endDate} · 近 $period 天", fontSize = 12.sp, color = secondary)
                Text(exactTokenCount(trend.total), fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 32.sp, modifier = Modifier.testTag("token-trend-total"))
                Text("已返回日用量合计 · ${trend.knownDays}/${trend.days.size} 天有数据", fontSize = 12.sp, color = secondary)
                TokenBars(trend)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(trend.startDate.toString(), fontSize = 12.sp, color = secondary)
                    Text(trend.endDate.toString(), fontSize = 12.sp, color = secondary)
                }
                Text("短线：未提供数据 · 圆点：已记录为 0", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
                TextButton(onClick = { details = true }, modifier = Modifier.testTag("token-trend-details")) { Text("逐日明细") }
            } else {
                val message = when {
                    loading && !demo -> "正在获取 token 统计…"
                    displayed?.dailyBuckets == null -> "服务端尚未提供按日 token 数据。"
                    else -> "服务端暂未返回可展示的日用量记录。"
                }
                Text(message, fontSize = 13.sp, lineHeight = 21.sp, color = secondary)
                Text("缺失数据不代表没有使用，也不会用额度百分比估算 token。", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
            }
            if (displayed?.lifetimeTokens != null || displayed?.peakDailyTokens != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                displayed?.lifetimeTokens?.let { TokenMetric("累计 token", it) }
                displayed?.peakDailyTokens?.let { TokenMetric("历史单日峰值", it) }
            }
            if (displayed?.partial == true) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Text("服务端返回的数据可能不完整，仅展示可用统计。", fontSize = 12.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            if (!demo) {
                failure?.let {
                    Text(tokenFailureMessage(it, usage != null), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error)
                }
                if (displayed != null) {
                    Text("获取于 ${absoluteTime(displayed.fetchedAt, true)}", fontSize = 12.sp, color = secondary)
                    displayed.statsAsOf?.let { Text("服务端统计时间：$it", fontSize = 12.sp, color = secondary) }
                }
                TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.testTag("token-trend-refresh")) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (loading) "正在更新统计" else "刷新 token 统计")
                }
            }
            Text("统计可能延迟；该接口不提供输入、输出或缓存 token 拆分。", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
        }
    }
    if (details && trend != null) {
        AlertDialog(onDismissRequest = { details = false }, title = { Text(if (demo) "示例 · 逐日 token" else "逐日 token") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("token-trend-day-list")) {
                    itemsIndexed(trend.days.asReversed(), key = { _, day -> day.date.toString() }) { index, day ->
                        // Keep the explanation scrollable with the data at large font sizes.
                        // Including it in the first day preserves one list item per date.
                        if (index == 0) {
                            Text("${trend.startDate} — ${trend.endDate}\n日期沿用服务端，不换算手机时区。", fontSize = 12.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(12.dp))
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {}) {
                            Text(day.date.toString(), fontSize = 12.sp, color = secondary)
                            Text(day.tokens?.let { "${exactTokenCount(it)} tokens" } ?: "未提供数据", fontSize = 15.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { details = false }, modifier = Modifier.testTag("token-trend-details-close")) { Text("关闭") } })
    }
}

@Composable
private fun TokenMetric(label: String, value: Long) {
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(exactTokenCount(BigInteger.valueOf(value)), fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TokenBars(trend: TokenTrend) {
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val missing = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = trend.peak.max(BigInteger.ONE).toDouble()
    val description = "${trend.startDate} 至 ${trend.endDate}，${trend.knownDays} 天有记录，" +
        "已返回日用量合计 ${exactTokenCount(trend.total)} tokens，" +
        "${trend.days.size - trend.knownDays} 天未提供数据，" +
        "${trend.days.count { it.tokens == BigInteger.ZERO }} 天记录为 0。具体数值可打开逐日明细。"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("日用量最高 ${exactTokenCount(trend.peak)} tokens", fontSize = 12.sp,
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
            trend.days.forEachIndexed { index, day ->
                val center = slot * (index + 0.5f)
                when {
                    day.tokens == null -> drawLine(missing, Offset(center - width / 3, baseline - 3.dp.toPx()),
                        Offset(center + width / 3, baseline - 3.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    day.tokens == BigInteger.ZERO -> drawCircle(bar, radius = 2.dp.toPx(), center = Offset(center, baseline))
                    else -> {
                        val height = (day.tokens.toDouble() / peak * plotHeight).toFloat().coerceAtLeast(2.dp.toPx())
                        drawRoundRect(bar, Offset(center - width / 2, baseline - height), Size(width, height),
                            CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun tokenFailureMessage(failure: FailureKind, cached: Boolean): String {
    val reason = when (failure) {
        FailureKind.AUTH -> "token 统计授权暂不可用，请刷新或重新连接。"
        FailureKind.FORBIDDEN -> "当前账号暂不允许读取 token 统计。"
        FailureKind.LIMITED -> "token 统计请求过于频繁，请稍后再试。"
        FailureKind.NETWORK -> "网络连接失败，暂时无法更新 token 统计。"
        FailureKind.SERVER -> "token 统计服务暂不可用，请稍后再试。"
        FailureKind.PROTOCOL -> "token 统计格式暂不兼容，请检查应用更新。"
        FailureKind.STORAGE -> "token 统计无法保存，请检查设备存储空间。"
    }
    return reason + if (cached) " 仍显示上次获取的数据。" else " 额度显示不受影响。"
}
