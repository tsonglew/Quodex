package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
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
internal fun SettingsPage(state: AppState, prefs: PreferencesState, model: MainViewModel, now: Long = java.time.Instant.now().epochSecond) {
    val texts = localTextResources()
    val colors = MaterialTheme.colorScheme
    val updates by model.updates.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var showRefreshInfo by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(texts(R.string.settings), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })

        SettingsPanel { LanguageSettings() }

        SettingsPanel {
            SettingsLabel(texts(R.string.appearance), Icons.Outlined.Palette)
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to texts(R.string.follow_system), "light" to texts(R.string.light), "dark" to texts(R.string.dark)).forEach { (id, label) ->
                    FilterChip(shape = RoundedCornerShape(50), selected = prefs.theme == id, onClick = { model.theme(id) },
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
            SettingsLabel(texts(R.string.display), Icons.Outlined.Visibility)
            PreferenceToggle(texts(R.string.show_remaining_quota), prefs.showRemaining, model::showRemaining,
                modifier = Modifier.testTag("show-remaining-toggle"),
                supportingText = texts(R.string.updates_all_home_screen_widgets))
        }

        SettingsPanel {
            SettingsLabel(texts(R.string.target_background_interval), Icons.Outlined.Schedule)
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15L, 30L, 60L).forEach { minutes ->
                    FilterChip(shape = RoundedCornerShape(50), selected = prefs.refreshMinutes == minutes, onClick = { model.refreshMinutes(minutes) },
                        label = { Text(texts(R.string.value_min, minutes), fontSize = 14.sp, lineHeight = 20.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer),
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(texts(R.string.battery_saving_or_lost_connectivity_may_delay), fontSize = 12.sp, lineHeight = 20.sp,
                color = colors.onSurfaceVariant)
            TextButton(onClick = { showRefreshInfo = true }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("background-refresh-info"), contentPadding = PaddingValues(vertical = 10.dp)) {
                Text(texts(R.string.about_refresh_timing), fontSize = 13.sp)
            }
        }

        DesktopIconSettings(state, prefs, model, now)
        BackgroundGuide()
        AppUpdatesCard(updates, model::checkForUpdates, model::automaticUpdates, model::updateNotificationPermissionChanged)

        SettingsPanel {
            SettingsLabel(texts(R.string.codex_connection), Icons.Outlined.AccountCircle)
            Spacer(Modifier.height(10.dp))
            Text(if (state.demo) texts(R.string.demo_mode) else if (state.connected) texts(R.string.connected_credentials_encrypted_on_device) else texts(R.string.not_connected),
                fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
            state.failure?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.userMessage().resolve(texts), fontSize = 12.sp, lineHeight = 20.sp, color = colors.error)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = model::startLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentPadding = PaddingValues(vertical = 10.dp)) {
                Text(if (state.connected) texts(R.string.reconnect) else texts(R.string.connect_account), Modifier.fillMaxWidth(), fontSize = 14.sp)
            }
            if (state.connected || state.demo) TextButton(onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("logout-button"),
                contentPadding = PaddingValues(vertical = 10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                Text(if (state.demo) texts(R.string.exit_demo_mode) else texts(R.string.sign_out_and_clear_local_data), Modifier.fillMaxWidth(),
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
        Text(texts(R.string.quodex_value_nindependent_third_party_tool_experimental, BuildConfig.VERSION_NAME),
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp), fontSize = 12.sp,
            lineHeight = 20.sp, color = colors.onSurfaceVariant)
    }

    if (showRefreshInfo) AlertDialog(onDismissRequest = { showRefreshInfo = false },
        title = { Text(texts(R.string.background_refresh_details)) },
        text = {
            Text(texts(R.string.battery_and_network_conditions_may_delay_background),
                modifier = Modifier.verticalScroll(rememberScrollState()), lineHeight = 24.sp)
        }, confirmButton = { TextButton(onClick = { showRefreshInfo = false }) { Text(texts(R.string.got_it)) } })

    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(texts(R.string.clear_local_connection)) },
        text = {
            Text(texts(R.string.local_credentials_quota_cache_and_background_quota),
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }, confirmButton = {
            TextButton(onClick = { confirmLogout = false; model.logout() }, modifier = Modifier.testTag("confirm-logout")) { Text(texts(R.string.clear)) }
        }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(texts(R.string.cancel)) } })
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

@Composable
private fun SettingsLabel(text: String, icon: ImageVector) {
    PanelHeading(text, icon)
}
