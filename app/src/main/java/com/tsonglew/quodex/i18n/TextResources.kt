package com.tsonglew.quodex.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.io.IOException
import java.util.Locale

/** Explicitly supplied to presentation code; cached account data never owns a locale. */
interface TextResources {
    val locale: Locale
    operator fun invoke(@StringRes id: Int, vararg arguments: Any): String
}

class AndroidTextResources(context: Context) : TextResources {
    private val resources = AppLanguages.contextForResources(context).resources
    override val locale: Locale get() = resources.configuration.locales[0]
    override fun invoke(id: Int, vararg arguments: Any): String = if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
}

@Composable
fun localTextResources(): TextResources {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { AndroidTextResources(context) }
}

/** Resolve transient errors at display time, including after a language change. */
data class UiText(@StringRes val id: Int, val arguments: List<Any> = emptyList()) {
    constructor(@StringRes id: Int, vararg arguments: Any) : this(id, arguments.toList())
    fun resolve(texts: TextResources): String = texts(id, *arguments.toTypedArray())
}

class UserVisibleException(val text: UiText) : IOException("Operation failed (message resource ${text.id})")
