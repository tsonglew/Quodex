package com.tsonglew.quodex

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tsonglew.quodex.data.DailyTokenUsage
import com.tsonglew.quodex.data.tokenTrend
import com.tsonglew.quodex.ui.TokenHeatmapChart
import org.junit.Rule
import org.junit.Test

class TokenHeatmapStateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun restoredSelectionOutsideUpdatedServiceWindowFallsBackToLatestDate() {
        var displayed = tokenTrend(listOf(DailyTokenUsage("2026-01-30", 10)), 30)!!
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { TokenHeatmapChart(displayed, compact = false) } }
        compose.onNodeWithTag("heatmap-selected-day").assertTextEquals("2026-01-30 · 10 tokens")
        // Model the repository already having a newer background snapshot when
        // saved UI state is restored, without first recomposing the old Activity.
        displayed = tokenTrend(listOf(DailyTokenUsage("2026-03-30", 30)), 30)!!
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("heatmap-selected-day").assertTextEquals("2026-03-30 · 30 tokens")
    }
}
