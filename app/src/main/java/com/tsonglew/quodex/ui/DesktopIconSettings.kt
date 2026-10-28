package com.tsonglew.quodex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.MainViewModel
import com.tsonglew.quodex.data.AppState
import com.tsonglew.quodex.data.PreferencesState
import com.tsonglew.quodex.launcher.QuotaIconRenderer
import com.tsonglew.quodex.launcher.desktopIconContent

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopIconSettings(state: AppState, prefs: PreferencesState, model: MainViewModel, now: Long) {
    val context = LocalContext.current
    val availability by model.desktopIconAvailability.collectAsStateWithLifecycle()
    val message by model.desktopIconMessage.collectAsStateWithLifecycle()
    val content = desktopIconContent(state, prefs, now)
    val bitmap = remember(context, content) { QuotaIconRenderer.render(context, content, 216).asImageBitmap() }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("desktop-icon-settings")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("桌面额度图标", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(bitmap, content.description, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                    .testTag("desktop-icon-preview"))
                Text(content.description, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            PreferenceToggle("在图标上显示剩余百分比", prefs.desktopIconEnabled, model::desktopIconEnabled,
                modifier = Modifier.testTag("desktop-icon-toggle"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("short" to "短周期", "weekly" to "每周").forEach { (id, label) ->
                    FilterChip(prefs.desktopIconWindow == id, { model.desktopIconWindow(id) },
                        enabled = prefs.desktopIconEnabled, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("desktop-icon-window-$id"))
                }
            }
            Text("添加一个带额度的桌面图标，轻点即可打开 Quodex。百分比随额度刷新，后台更新可能受系统调度影响。",
                fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(model::addDesktopIcon, enabled = availability.supported || availability.pinned,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-desktop-icon")) {
                Icon(Icons.AutoMirrored.Outlined.AddToHomeScreen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (availability.pinned) "更新桌面图标" else "添加到桌面")
            }
            val status = when {
                availability.pinned -> message?.takeUnless { it == "请在桌面提示中确认添加" } ?: "已添加，额度刷新时自动更新"
                !availability.supported -> "当前桌面不支持添加额度图标，可使用桌面小组件"
                else -> message
            }
            status?.let {
                Text(it, fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("desktop-icon-message"))
            }
        }
    }
}
