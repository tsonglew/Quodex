package com.tsonglew.quodex

import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.tsonglew.quodex.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keystoreRoundTripRejectsTamperingAndClearsCredentials() {
        val vault = CredentialVault(context)
        val file = File(context.noBackupFilesDir, "codex-session.enc")
        val session = Session("device-test-access-token", "device-test-refresh-token", "device-test-account", 9000)
        try {
            vault.write(session)
            assertEquals(session, vault.read())
            val encrypted = file.readBytes()
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(session.accessToken))
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(session.refreshToken))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            try { vault.read(); fail("Tampered ciphertext must be rejected") }
            catch (failure: ApiFailure) { assertEquals(FailureKind.STORAGE, failure.kind) }
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        } finally { vault.clear() }
        assertNull(vault.read())
        assertFalse(file.exists())
    }

    @Test fun widgetInstancesKeepIndependentPreferences() = runBlocking {
        val settings = context.quotaApp.settings
        try {
            settings.saveWidget(70001, false, "dark")
            settings.saveWidget(70002, true, "light")
            assertEquals(false to "dark", settings.widgetSettings(70001))
            assertEquals(true to "light", settings.widgetSettings(70002))
            settings.deleteWidget(70001)
            assertEquals(settings.read().showRemaining to "system", settings.widgetSettings(70001))
            assertEquals(true to "light", settings.widgetSettings(70002))
        } finally { settings.deleteWidget(70001); settings.deleteWidget(70002) }
    }

    @Test fun globalRemainingSwitchUpdatesAllInstancesAndPreservesTheirThemes() = runBlocking {
        val settings = context.quotaApp.settings
        val originalRemaining = settings.read().showRemaining
        val firstId = 71001
        val secondId = 71002
        val unconfiguredId = 71003
        val ownIds = listOf(firstId, secondId, unconfiguredId)
        try {
            ownIds.forEach { settings.deleteWidget(it) }
            settings.saveWidget(firstId, false, "dark")
            settings.saveWidget(secondId, true, "light")

            // A global change must override already-configured instances as well as defaults.
            for (remaining in listOf(false, true)) {
                settings.showRemaining(remaining)
                val reloaded = SettingsStore(context)
                assertEquals(remaining, reloaded.read().showRemaining)
                assertEquals(remaining to "dark", reloaded.widgetSettings(firstId))
                assertEquals(remaining to "light", reloaded.widgetSettings(secondId))
                assertEquals(remaining to "system", reloaded.widgetSettings(unconfiguredId))
            }

            // Individual configuration still applies until the next global synchronization.
            settings.saveWidget(firstId, false, "dark")
            assertTrue(settings.read().showRemaining)
            assertEquals(false to "dark", settings.widgetSettings(firstId))
            assertEquals(true to "light", settings.widgetSettings(secondId))
            assertEquals(true to "system", settings.widgetSettings(unconfiguredId))
            settings.showRemaining(true)
            val reloaded = SettingsStore(context)
            assertEquals(true to "dark", reloaded.widgetSettings(firstId))
            assertEquals(true to "light", reloaded.widgetSettings(secondId))
            assertEquals(true to "system", reloaded.widgetSettings(unconfiguredId))
        } finally {
            ownIds.forEach { settings.deleteWidget(it) }
            settings.showRemaining(originalRemaining)
        }
    }
}
