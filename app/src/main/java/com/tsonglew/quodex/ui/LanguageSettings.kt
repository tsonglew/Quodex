package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tsonglew.quodex.R
import com.tsonglew.quodex.i18n.AppLanguages
import com.tsonglew.quodex.i18n.localTextResources
import com.tsonglew.quodex.quotaApp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSettings() {
    val texts = localTextResources()
    val context = LocalContext.current
    val selected = AppLanguages.selectedTag(context)
    PanelHeading(texts(R.string.app_language), Icons.Outlined.Language)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to texts(R.string.follow_system), "zh-Hans" to "简体中文", "en" to "English", "ja" to "日本語").forEach { (tag, label) ->
            val active = if (tag.isEmpty()) selected.isEmpty() else selected.substringBefore('-') == tag.substringBefore('-')
            FilterChip(selected = active, onClick = {
                AppLanguages.select(tag)
                context.quotaApp.languageChanged()
            }, label = { Text(label) }, shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.heightIn(min = 48.dp).testTag("language-${tag.ifEmpty { "system" }}"))
        }
    }
    Text(texts(R.string.language_applies_to_widgets), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}
