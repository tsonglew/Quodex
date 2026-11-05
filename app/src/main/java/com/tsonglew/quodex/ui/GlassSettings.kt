package com.tsonglew.quodex.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.data.GlassPreferences
import kotlin.math.roundToInt
import com.tsonglew.quodex.R
import com.tsonglew.quodex.i18n.localTextResources

@Composable
internal fun GlassSettings(
    preferences: GlassPreferences,
    onPreview: (GlassPreferences?) -> Unit,
    onTint: (Boolean, Float) -> Unit,
    onReduceTransparency: (Boolean) -> Unit,
) {
    val texts = localTextResources()
    var open by rememberSaveable { mutableStateOf(false) }
    // An earlier slider's asynchronous save must not reset a second drag in progress.
    var draft by remember(open) { mutableStateOf(preferences) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("glass-settings")) {
        Text(texts(R.string.glass_material), fontSize = 14.sp)
    }
    if (!open) return
    DisposableEffect(Unit) { onDispose { onPreview(null) } }
    GlassAlertDialog(surfaceId = "glass-settings", onDismissRequest = { open = false },
        title = { Text(texts(R.string.glass_material)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(texts(R.string.glass_material_description),
                    fontSize = 13.sp, lineHeight = 21.sp)
                GlassTintSlider(texts(R.string.light), draft.lightTint, !draft.reduceTransparency, "glass-light-tint",
                    onChange = { draft = draft.copy(lightTint = it); onPreview(draft) },
                    onFinished = { onTint(false, draft.lightTint) })
                GlassTintSlider(texts(R.string.dark), draft.darkTint, !draft.reduceTransparency, "glass-dark-tint",
                    onChange = { draft = draft.copy(darkTint = it); onPreview(draft) },
                    onFinished = { onTint(true, draft.darkTint) })
                PreferenceToggle(texts(R.string.reduce_transparency), draft.reduceTransparency, {
                    draft = draft.copy(reduceTransparency = it)
                    onPreview(draft)
                    onReduceTransparency(it)
                }, modifier = Modifier.testTag("reduce-transparency"), supportingText = texts(R.string.reduce_transparency_description))
            }
        }, confirmButton = {
            TextButton(onClick = { open = false }, modifier = Modifier.testTag("glass-settings-close")) { Text(texts(R.string.close)) }
        })
}

@Composable
private fun GlassTintSlider(label: String, value: Float, enabled: Boolean, tag: String,
    onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val texts = localTextResources()
    Column {
        Text(texts(R.string.glass_tint_value, label, (value * 100).roundToInt()), fontSize = 14.sp)
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onFinished,
            valueRange = 0f..1.5f, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = texts(R.string.glass_tint_accessibility, label) })
    }
}
