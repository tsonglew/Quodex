package com.tsonglew.quodex

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction

/** Compose's performScrollTo considers viewport bounds, but not overlapping sibling navigation. */
internal fun SemanticsNodeInteraction.performScrollToVisible(rule: ComposeTestRule): SemanticsNodeInteraction {
    performScrollTo()
    val node = fetchSemanticsNode()
    val ancestors = generateSequence(node.parent) { it.parent }.toList()
    // Dialogs and standalone component fixtures do not sit behind the app's dock.
    if (ancestors.none { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("page-") == true }) return this
    val dock = rule.onAllNodesWithTag("floating-navigation").fetchSemanticsNodes().singleOrNull() ?: return this
    val viewport = ancestors.firstOrNull { it.config.contains(SemanticsActions.ScrollBy) } ?: return this
    val targetBottom = node.positionInRoot.y + node.size.height
    val clearance = 8f * rule.density.density
    val delta = targetBottom - dock.boundsInRoot.top + clearance
    if (delta > 0f) {
        rule.onNode(SemanticsMatcher("scroll ancestor") { it.id == viewport.id })
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        rule.waitForIdle()
    }
    return this
}
