package com.tsonglew.quodex

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun ComposeTestRule.openSettingsPage(destination: String) {
    onNodeWithTag("tab-settings").performClick()
    if (onAllNodesWithTag("navigate-back").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("navigate-back").performClick()
    }
    onNodeWithTag("settings-$destination").performScrollToVisible(this).performClick()
}

internal fun ComposeTestRule.openCodexAccount() {
    onNodeWithTag("tab-subscriptions").performClick()
    if (onAllNodesWithTag("navigate-back").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("navigate-back").performClick()
    }
    onNodeWithTag("account-codex").performScrollToVisible(this).performClick()
}

internal fun ComposeTestRule.openCodexDetails() {
    waitUntil(10_000) { onAllNodesWithTag("overview-codex-open").fetchSemanticsNodes().isNotEmpty() ||
        onAllNodesWithTag("token-usage-card").fetchSemanticsNodes().isNotEmpty() }
    if (onAllNodesWithTag("overview-codex-open").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("overview-codex-open").performScrollToVisible(this).performClick()
    }
}

internal fun ComposeTestRule.openProvider(provider: String) {
    onNodeWithTag("account-$provider").performScrollToVisible(this).performClick()
}
