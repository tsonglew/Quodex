package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.subscriptions.browser.GoogleAccountSelection
import org.junit.Assert.*
import org.junit.Test

class GoogleAccountSelectionTest {
    @Test fun emailSelectionIsRestrictedToGoogleHttpsOrigin() {
        assertTrue(GoogleAccountSelection.canFill("https://accounts.google.com/v3/signin/identifier"))
        assertTrue(GoogleAccountSelection.canFill("https://accounts.google.com:443/signin"))
        listOf(null, "http://accounts.google.com", "https://accounts.google.com:444/", "https://accounts.google.com.evil.test/",
            "https://accounts.google.com@evil.test/", "https://evil.test@accounts.google.com/", "https://grok.com/", "javascript:alert(1)").forEach {
            assertFalse("Unexpected email destination: $it", GoogleAccountSelection.canFill(it))
        }
    }
    @Test fun invalidSelectedNamesNeverProduceJavascript() {
        listOf("", "missing-at", "a@b\n", "a@" + "b".repeat(320)).forEach { assertNull(GoogleAccountSelection.script(it)) }
    }
}
