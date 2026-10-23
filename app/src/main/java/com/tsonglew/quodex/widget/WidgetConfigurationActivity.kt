package com.tsonglew.quodex.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        lifecycleScope.launch {
            val config = quotaApp.settings.widgetSettings(widgetId)
            setContent {
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
                                Text("设置小组件", style = MaterialTheme.typography.headlineMedium)
                                Text("仅调整当前小组件。App 内切换“显示剩余额度”时，会同步到全部小组件；各自的主题会保留。")
                                PreferenceToggle(title = "显示剩余额度", checked = remaining, onCheckedChange = { remaining = it }, enabled = !saving)
                                Text("外观", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("system" to "系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                                        FilterChip(theme == value, { theme = value }, label = { Text(label) }, enabled = !saving)
                                    }
                                }
                                Text("可在桌面长按并缩放。后台更新可能延迟，最后更新时间始终可见。", style = MaterialTheme.typography.bodyMedium)
                                Button(enabled = !saving, onClick = {
                                    saving = true
                                    lifecycleScope.launch {
                                        quotaApp.settings.saveWidget(widgetId, remaining, theme)
                                        quotaApp.updateWidgets()
                                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                        finish()
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("保存小组件") }
                                TextButton(onClick = { finish() }, enabled = !saving) { Text("取消") }
                            }
                        }
                    }
                }
            }
        }
    }
}
