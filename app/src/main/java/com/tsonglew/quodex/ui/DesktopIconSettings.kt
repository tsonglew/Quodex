package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
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
    val texts = localTextResources()
    val context = LocalContext.current
    val availability by model.desktopIconAvailability.collectAsStateWithLifecycle()
    val message by model.desktopIconMessage.collectAsStateWithLifecycle()
    val content = desktopIconContent(state, prefs, texts, now)
    val bitmap = remember(context, content) { QuotaIconRenderer.render(context, content, 216).asImageBitmap() }
    val requestPin = rememberHomePinRequest { model.addDesktopIcon() }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("desktop-icon-settings")) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(texts(R.string.home_screen_quota_icon), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(bitmap, content.description, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                    .testTag("desktop-icon-preview"))
                Text(content.description, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            PreferenceToggle(texts(R.string.show_remaining_percentage_on_the_icon), prefs.desktopIconEnabled, model::desktopIconEnabled,
                modifier = Modifier.testTag("desktop-icon-toggle"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("short" to texts(R.string.short_period), "weekly" to texts(R.string.weekly)).forEach { (id, label) ->
                    FilterChip(prefs.desktopIconWindow == id, { model.desktopIconWindow(id) }, shape = RoundedCornerShape(50),
                        enabled = prefs.desktopIconEnabled, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("desktop-icon-window-$id"))
                }
            }
            Text(texts(R.string.add_a_home_screen_icon_with_your),
                fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                if (availability.pinned) model.addDesktopIcon() else requestPin(HomePinTarget.ICON)
            }, enabled = availability.supported || availability.pinned,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-desktop-icon")) {
                Icon(Icons.AutoMirrored.Outlined.AddToHomeScreen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (availability.pinned) texts(R.string.update_home_screen_icon) else texts(R.string.add_to_home_screen))
            }
            val status = when {
                availability.pinned -> message?.takeUnless { it.id == R.string.confirm_adding_the_icon_in_the_home }?.resolve(texts) ?: texts(R.string.added_updates_with_your_quota)
                !availability.supported -> texts(R.string.your_launcher_does_not_support_this_icon)
                else -> message?.resolve(texts)
            }
            status?.let {
                Text(it, fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("desktop-icon-message"))
            }
        }
    }
}
