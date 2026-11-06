package com.tsonglew.quodex

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

// Compose 1.8 defaults to UnconfinedTestDispatcher: effects returning from IO can
// continue on that worker. Queue them until the test scheduler runs on the UI thread.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal fun createQuodexComposeRule() = StandardTestDispatcher().let { dispatcher ->
    createComposeRule(dispatcher).drainQueuedEffects(dispatcher.scheduler)
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal inline fun <reified A : ComponentActivity> createQuodexAndroidComposeRule() =
    StandardTestDispatcher().let { dispatcher ->
        createAndroidComposeRule<A>(dispatcher).drainQueuedEffects(dispatcher.scheduler)
    }

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal fun createQuodexEmptyComposeRule() = StandardTestDispatcher().let { dispatcher ->
    createEmptyComposeRule(dispatcher).drainQueuedEffects(dispatcher.scheduler)
}

// Compose 1.8's idle check only advances time after recomposition or a frame is
// requested. A queued scroll/effect may request neither until its first dispatch.
// Register before the built-in resource so due tasks run before Compose checks
// pending layout/frames. This is the runCurrent step added by Compose test v2.
// It does not advance virtual time or replace Compose's own idle assertions.
@PublishedApi
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : ComposeTestRule> T.drainQueuedEffects(scheduler: TestCoroutineScheduler): T = apply {
    registerIdlingResource(object : IdlingResource {
        override val isIdleNow: Boolean
            get() {
                runOnUiThread { scheduler.runCurrent() }
                return true
            }
    })
}
