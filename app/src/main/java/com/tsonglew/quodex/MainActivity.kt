package com.tsonglew.quodex

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.tsonglew.quodex.ui.QuodexApp
import com.tsonglew.quodex.widget.EXTRA_WIDGET_LAUNCH
import com.tsonglew.quodex.diagnostics.DiagnosticEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordWidgetLaunch(intent)
        enableEdgeToEdge()
        setContent {
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onForeground() }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            QuodexApp(model)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordWidgetLaunch(intent)
    }

    private fun recordWidgetLaunch(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_WIDGET_LAUNCH, false)) {
            intent.removeExtra(EXTRA_WIDGET_LAUNCH)
            quotaApp.scope.launch(Dispatchers.IO) { quotaApp.diagnostics.record(DiagnosticEvent.WIDGET_OPEN_APP) }
        }
    }
}
