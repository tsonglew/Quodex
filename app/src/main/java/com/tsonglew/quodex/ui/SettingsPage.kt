package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsonglew.quodex.BuildConfig
import com.tsonglew.quodex.MainViewModel
import com.tsonglew.quodex.data.AppState
import com.tsonglew.quodex.data.PreferencesState
import com.tsonglew.quodex.data.userMessage

/** Settings use the same quiet, rounded panels as the quota overview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsPage(state: AppState, prefs: PreferencesState, model: MainViewModel) {
    val colors = MaterialTheme.colorScheme
    val updates by model.updates.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var showRefreshInfo by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("设置", fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })

        SettingsPanel {
            SettingsLabel("外观")
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) ->
                    FilterChip(selected = prefs.theme == id, onClick = { model.theme(id) },
                        label = { Text(label, fontSize = 14.sp, lineHeight = 20.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("theme-$id"))
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
            Spacer(Modifier.height(18.dp))
            SettingsLabel("显示方式")
            PreferenceToggle("显示剩余额度", prefs.showRemaining, model::showRemaining,
                modifier = Modifier.testTag("show-remaining-toggle"),
                supportingText = "同步更新所有桌面小组件。")
        }

        SettingsPanel {
            SettingsLabel("后台目标间隔")
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15L, 30L, 60L).forEach { minutes ->
                    FilterChip(selected = prefs.refreshMinutes == minutes, onClick = { model.refreshMinutes(minutes) },
                        label = { Text("$minutes 分钟", fontSize = 14.sp, lineHeight = 20.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer),
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("省电或断网可能延迟刷新。", fontSize = 12.sp, lineHeight = 20.sp,
                color = colors.onSurfaceVariant)
            TextButton(onClick = { showRefreshInfo = true }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("background-refresh-info"), contentPadding = PaddingValues(vertical = 10.dp)) {
                Text("查看刷新说明", fontSize = 13.sp)
            }
        }

        BackgroundGuide()
        AppUpdatesCard(updates, model::checkForUpdates, model::automaticUpdates, model::updateNotificationPermissionChanged)

        SettingsPanel {
            SettingsLabel("Codex 连接")
            Spacer(Modifier.height(10.dp))
            Text(if (state.demo) "示例模式" else if (state.connected) "已连接 · 凭据在本机加密保存" else "尚未连接",
                fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            state.failure?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.userMessage(), fontSize = 12.sp, lineHeight = 20.sp, color = colors.error)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = model::startLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentPadding = PaddingValues(vertical = 10.dp)) {
                Text(if (state.connected) "重新连接" else "连接账号", Modifier.fillMaxWidth(), fontSize = 14.sp)
            }
            if (state.connected || state.demo) TextButton(onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("logout-button"),
                contentPadding = PaddingValues(vertical = 10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                Text(if (state.demo) "退出示例模式" else "退出并清除本机数据", Modifier.fillMaxWidth(),
                    fontSize = 14.sp, lineHeight = 21.sp)
            }
        }

        SettingsPanel {
            DiagnosticEntry(compact = true)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
            Spacer(Modifier.height(8.dp))
            PrivacyNoticeEntry()
        }
        Text("Quodex ${BuildConfig.VERSION_NAME}\n独立第三方工具 · 连接能力处于实验阶段\n应用仅读取额度与用量统计；登录凭据的权限可能覆盖更多能力。",
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp), fontSize = 12.sp,
            lineHeight = 20.sp, color = colors.onSurfaceVariant)
    }

    if (showRefreshInfo) AlertDialog(onDismissRequest = { showRefreshInfo = false },
        title = { Text("后台刷新说明") },
        text = {
            Text("后台刷新受系统省电与网络影响，可能延迟。持续失败时每轮最多尝试 3 次，之后等待下一周期或手动刷新。",
                modifier = Modifier.verticalScroll(rememberScrollState()), lineHeight = 24.sp)
        }, confirmButton = { TextButton(onClick = { showRefreshInfo = false }) { Text("知道了") } })

    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text("清除本机连接？") },
        text = {
            Text("本机凭据、额度缓存与额度后台任务将被清除，桌面小组件恢复为未连接。显示与应用更新偏好会保留。远程授权不会在此操作中撤销。",
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }, confirmButton = {
            TextButton(onClick = { confirmLogout = false; model.logout() }, modifier = Modifier.testTag("confirm-logout")) { Text("清除") }
        }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), content = content)
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
}
