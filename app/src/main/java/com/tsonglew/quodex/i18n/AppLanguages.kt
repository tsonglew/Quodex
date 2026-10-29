package com.tsonglew.quodex.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import android.os.LocaleList

object AppLanguages {
    val supportedTags = listOf("", "zh-Hans", "en", "ja")
    @Volatile private var delegateReady = false

    fun delegateInitialized() { delegateReady = true }

    fun selectedTag(context: Context): String = requestedLocales(context).get(0)?.toLanguageTag().orEmpty()

    fun select(tag: String) {
        require(tag in supportedTags)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun requestedLocales(context: Context): LocaleListCompat =
        if (Build.VERSION.SDK_INT < 33 && delegateReady) AppCompatDelegate.getApplicationLocales()
        else LocaleManagerCompat.getApplicationLocales(context)

    /** Workers and Glance also need app locales on Android 12 and earlier.
     * Before an Activity exists, AndroidX reads its persisted locale. After that,
     * use the delegate's current value so background rendering need not race its async storage.
     */
    fun contextForResources(context: Context): Context {
        val requested = requestedLocales(context)
        val locales = if (requested.isEmpty) Resources.getSystem().configuration.locales
            else LocaleList.forLanguageTags(requested.toLanguageTags())
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(locales) })
    }
}
