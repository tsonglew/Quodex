package com.tsonglew.quodex.i18n

import com.tsonglew.quodex.R
import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.launcher.desktopIconContent
import org.junit.Assert.*
import org.junit.Test

class LocalizationTest {
    private val en = TestTextResources("en")
    private val zh = TestTextResources("zh")
    private val ja = TestTextResources("ja")

    @Test fun everyLanguageContainsTheFullCatalogAndMatchingFormatArguments() {
        val english = TestTextResources.readCatalog("en").filterKeys { !it.startsWith("brand_") }
        // Resource placeholders use positional strings so translations can safely reorder them.
        val arguments = Regex("%(\\d+)\\\$s")
        for (language in listOf("zh", "ja")) {
            val translated = TestTextResources.readCatalog(language)
            assertEquals(language, english.keys, translated.keys)
            for ((name, source) in english) {
                val target = translated.getValue(name)
                assertTrue("$language/$name is empty", target.isNotBlank())
                val slots = arguments.findAll(source).map { it.groupValues[1].toInt() }.toList()
                assertEquals("$language/$name placeholders", slots.sorted(), arguments.findAll(target).map { it.groupValues[1].toInt() }.sorted().toList())
                if (slots.isNotEmpty()) {
                    val values = Array<Any>(slots.max()) { "value${it + 1}" }
                    assertFalse(String.format(target, *values).contains("%1\$s"))
                }
            }
        }
    }

    @Test fun legacyChineseCacheChangesItsBuiltInLabelsWithoutRefetching() {
        val snapshot = AppJson.decodeFromString<UsageSnapshot>("""{"accountId":"test","plan":"plus","windows":[{"id":"codex:primary_window","name":"每周额度","usedPercent":38.0,"durationSeconds":604800,"resetsAt":2000}],"fetchedAt":1000}""")
        val window = snapshot.windows.single()
        assertEquals("Weekly quota", window.displayName(en))
        assertEquals("週間使用枠", window.displayName(ja))
        assertEquals("每周额度", window.displayName(zh))
        assertEquals("62", window.percentageLabel(true, ja))
        assertEquals("每周额度", window.name) // Stored account data was not rewritten.
    }

    @Test fun serverFeatureNamesArePreservedWhileThePeriodIsTranslated() {
        val old = QuotaWindow("reviews:primary", "Code review · 每周额度", 12.0, 604800, null)
        assertEquals("Code review · Weekly quota", old.displayName(en))
        val current = old.copy(name = "ignored cached label", sourceLabel = "Custom feature")
        assertEquals("Custom feature · 週間使用枠", current.displayName(ja))
    }

    @Test fun pendingErrorsAndRestrictionsUseTheLanguageAtDisplayTime() {
        val message = UiText(R.string.download_failed_http_value_try_again_later, 503)
        assertEquals("Download failed (HTTP 503). Try again later.", message.resolve(en))
        assertTrue(message.resolve(ja).startsWith("ダウンロードに失敗"))
        assertTrue(message.resolve(zh).startsWith("下载失败"))
        val state = UsageSnapshot("test", "plus", emptyList(), 1000, allowed = false)
        assertEquals("Usage is currently restricted", state.quotaNotice(en)!!.title)
        assertEquals("現在、利用が制限されています", state.quotaNotice(ja)!!.title)
    }

    @Test fun desktopDescriptionsAndUnknownQuotaFollowTheSelectedLanguage() {
        val snapshot = demoSnapshot(1000)
        val state = AppState(initialized = true, demo = true, snapshot = snapshot)
        val prefs = PreferencesState(desktopIconWindow = "weekly")
        assertTrue(desktopIconContent(state, prefs, en, 1000).description.contains("Weekly quota remaining 62%"))
        assertTrue(desktopIconContent(state, prefs, ja, 1000).description.contains("週間使用枠 残り 62%"))
        assertEquals("Unavailable", snapshot.windows.first().copy(usedPercent = null).percentageLabel(true, en))
        assertEquals("利用不可", snapshot.windows.first().copy(usedPercent = null).percentageLabel(true, ja))
    }
}
