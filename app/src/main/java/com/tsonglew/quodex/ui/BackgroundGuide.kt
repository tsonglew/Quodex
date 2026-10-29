package com.tsonglew.quodex.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal enum class BackgroundBrand(val label: String, val steps: List<String>) {
    XIAOMI("小米 / Redmi / POCO", listOf(
        "允许自启动：设置 → 应用设置 → 应用管理／权限管理 → 自启动／后台自启动，开启 Quodex。也可在系统设置搜索“自启动”。",
        "放宽省电：应用信息 → 省电策略／电池，选择“无限制”。",
        "锁定后台：打开最近任务，长按 Quodex 卡片，选择锁形图标。部分版本需在安全中心 → 设置 → 加速 → 锁定应用中设置。锁定用于减少一键清理的影响。")),
    OPPO("OPPO / OnePlus / realme", listOf(
        "允许启动：在系统设置搜索“自启动”或“应用启动管理”，若有对应选项，为 Quodex 开启。",
        "允许后台：应用信息 → 电池使用／耗电管理，允许后台活动；若有“允许自启动”选项，一并开启。不同 ColorOS／OxygenOS／realme UI 版本入口不同。",
        "锁定后台：打开最近任务，查看 Quodex 卡片的菜单或长按卡片，若有“锁定”则开启；部分旧版通过下拉卡片锁定。")),
    VIVO("vivo / iQOO", listOf(
        "允许自启动：设置 → 应用与权限 → 权限管理 → 权限 → 自启动，开启 Quodex。",
        "允许后台：设置 → 电池 → 后台耗电管理／后台高耗电，找到 Quodex，允许后台运行。",
        "锁定后台：在最近任务中查看 Quodex 卡片菜单，若有锁定选项则开启，避免一键加速清理。")),
    HUAWEI("华为 / 荣耀（Android）", listOf(
        "手动管理启动：在设置搜索“应用启动管理”，关闭 Quodex 的自动管理。",
        "允许运行：在手动管理中开启“允许自启动”和“允许后台活动”；若系统要求关联启动权限，请按实际需要设置。",
        "锁定后台：在最近任务中下拉 Quodex 卡片或查看卡片菜单，若有锁形标记则锁定。仅适用于可安装本应用的 Android 兼容系统。")),
    SAMSUNG("Samsung", listOf(
        "排除休眠：设置 → 电池／设备维护 → 后台使用限制，把 Quodex 从休眠和深度休眠列表移除，加入“从不休眠的应用”。",
        "允许后台：应用信息 → 电池，若有“不受限制”选项可选择。",
        "最近任务：若卡片菜单提供“保持打开”可启用；此项并非所有机型都有，也不是自启动权限。")),
    GENERIC("Pixel / 原生 Android / 其他", listOf(
        "允许后台：应用信息 → 应用电池用量／电池，开启“允许后台使用”；若后台更新仍被限制，可选择“不受限制”。",
        "检查网络：应用信息 → 移动数据，允许后台数据；省流量模式下按需要允许不受限数据。",
        "自启动与锁定：原生 Android 通常没有独立自启动开关或后台锁。其他品牌请在设置搜索“自启动”“后台运行”，仅调整 Quodex。"));

    companion object {
        fun detect(manufacturer: String, brand: String): BackgroundBrand {
            val names = listOf(manufacturer, brand).map { it.trim().lowercase(java.util.Locale.ROOT) }
            return when {
                names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
                names.any { it in setOf("oppo", "oneplus", "realme") } -> OPPO
                names.any { it in setOf("vivo", "iqoo") } -> VIVO
                names.any { it in setOf("huawei", "honor") } -> HUAWEI
                "samsung" in names -> SAMSUNG
                else -> GENERIC
            }
        }
    }
}

@Composable
internal fun BackgroundGuide() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(BackgroundBrand.detect(Build.MANUFACTURER, Build.BRAND).name) }
    var menu by remember { mutableStateOf(false) }
    fun batteryExempt() = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    var exempt by remember { mutableStateOf(batteryExempt()) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) exempt = batteryExempt() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val guide = BackgroundBrand.valueOf(selected)
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("后台更新保障", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text("清理后台后不再更新？按手机品牌检查自启动、省电与后台锁定。",
                fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).testTag("background-guide-toggle"),
                contentPadding = PaddingValues(vertical = 10.dp)) {
                Text(if (expanded) "收起引导" else "查看设置引导", fontSize = 13.sp)
            }
            if (expanded) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                Spacer(Modifier.height(14.dp))
                Box {
                    OutlinedButton(onClick = { menu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-brand-picker"),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(guide.label, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        BackgroundBrand.entries.forEach { item ->
                            DropdownMenuItem(text = { Text(item.label, fontSize = 13.sp, lineHeight = 20.sp) },
                                onClick = { selected = item.name; menu = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("已按设备品牌选择，可手动切换。菜单会随系统版本、地区变化，找不到时使用设置搜索。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                guide.steps.forEachIndexed { index, step ->
                    Spacer(Modifier.height(12.dp))
                    Text("${index + 1}. $step", fontSize = 13.sp, lineHeight = 22.sp)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openBackgroundSettings(context, false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-app-settings"),
                    contentPadding = PaddingValues(vertical = 10.dp)) {
                    Text("打开 Quodex 应用信息", Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 20.sp)
                }
                TextButton(onClick = { openBackgroundSettings(context, true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)) {
                    Text("打开系统电池优化列表", Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(if (exempt) "系统电池优化：已豁免" else "系统电池优化：未豁免",
                    fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("此状态只反映 Android 电池优化；无法读取厂商自启动或后台锁定状态，请在系统页面确认。允许后台运行可能增加耗电。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("设置后返回 App 刷新一次，再回到桌面观察组件的最后更新时间。目标间隔不是准点保证，休眠或断网仍可能延迟。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("若在应用信息中点过“强制停止”，请先重新打开 Quodex 才能恢复调度；后台锁定不能绕过强制停止。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun openBackgroundSettings(context: Context, battery: Boolean) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    val intents = if (battery) listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details, Intent(Settings.ACTION_SETTINGS))
        else listOf(details, Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        try { context.startActivity(intent); return } catch (_: android.content.ActivityNotFoundException) {
            // Some vendor ROMs omit standard settings activities; use the next safe entry point.
        } catch (_: SecurityException) {
            // Vendor settings may deny access even when the activity is present.
        }
    }
    Toast.makeText(context, "无法打开系统设置，请手动搜索 Quodex 应用信息。", Toast.LENGTH_LONG).show()
}
