package com.tsonglew.quodex

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ComposeSchedulingTest {
    @get:Rule val compose = createQuodexComposeRule()

    @Test fun compositionEffectResumesOnUiThreadAfterIo() {
        val resumedOnUiThread = AtomicReference<Boolean?>()
        compose.setContent {
            LaunchedEffect(Unit) {
                // Real repositories suspend on IO; resuming a composition effect must
                // not run inline on that worker while the UI is measuring a frame.
                withContext(Dispatchers.IO) { delay(25) }
                resumedOnUiThread.set(Looper.myLooper() == Looper.getMainLooper())
            }
        }
        compose.waitUntil(5_000) { resumedOnUiThread.get() != null }
        assertTrue("Composition effects must resume on the UI thread", resumedOnUiThread.get() == true)
    }

    @Test fun queuedScrollStartsWhileCompositionIsOtherwiseIdle() {
        compose.setContent {
            Column(Modifier.height(100.dp).verticalScroll(rememberScrollState())) {
                repeat(10) { Text("Row $it", Modifier.height(50.dp).testTag("row-$it")) }
            }
        }
        // StandardTestDispatcher queues the semantics scroll coroutine. The idle
        // resource must start it even though there is no pending recomposition yet.
        compose.onNodeWithTag("row-9").performScrollTo().assertIsDisplayed()
    }
}
