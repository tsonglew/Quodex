package com.tsonglew.quodex.widget

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import com.tsonglew.quodex.i18n.AppLanguages
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tsonglew.quodex.quotaApp
import com.tsonglew.quodex.ui.PreferenceToggle
import com.tsonglew.quodex.ui.QuotaTheme
import kotlinx.coroutines.launch

class WidgetConfigurationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguages.delegateInitialized()
        quotaApp.languageChanged()
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val heatmap = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className == TokenHeatmapWidgetReceiver::class.java.name
        lifecycleScope.launch {
            val config = quotaApp.settings.widgetSettings(widgetId)
            setContent {
                val texts = localTextResources()
                var remaining by remember { mutableStateOf(config.first) }
                var theme by remember { mutableStateOf(config.second) }
                var saving by remember { mutableStateOf(false) }
                QuotaTheme(theme) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                            Column(
                                Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Text(texts(R.string.configure_widget), style = MaterialTheme.typography.headlineMedium)
                                Text(texts(if (heatmap) R.string.token_heatmap_widget_description else R.string.these_settings_apply_to_this_widget_only))
                                if (!heatmap) PreferenceToggle(title = texts(R.string.show_remaining_quota), checked = remaining, onCheckedChange = { remaining = it }, enabled = !saving)
                                Text(texts(R.string.appearance), style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("system" to texts(R.string.system), "light" to texts(R.string.light), "dark" to texts(R.string.dark)).forEach { (value, label) ->
                                        FilterChip(theme == value, { theme = value }, label = { Text(label) }, enabled = !saving,
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
                                    }
                                }
                                Text(texts(R.string.press_and_hold_on_your_home_screen), style = MaterialTheme.typography.bodyMedium)
                                Button(enabled = !saving, onClick = {
                                    saving = true
                                    lifecycleScope.launch {
                                        quotaApp.settings.saveWidget(widgetId, remaining, theme)
                                        quotaApp.updateWidgets()
                                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                        finish()
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text(texts(R.string.save_widget)) }
                                TextButton(onClick = { finish() }, enabled = !saving) { Text(texts(R.string.cancel)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
