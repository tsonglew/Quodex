package com.tsonglew.quodex.ui

import com.tsonglew.quodex.i18n.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsonglew.quodex.R

/** The bundled policy is available offline before connecting an account. */
@Composable
internal fun PrivacyNoticeEntry() {
    val texts = localTextResources()
    var visible by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { visible = true }, modifier = Modifier.testTag("privacy-notice-button")) { Text(texts(R.string.privacy_and_data)) }
    if (visible) {
        val context = LocalContext.current
        val notice = remember(context, texts.locale) {
            AppLanguages.contextForResources(context).resources.openRawResource(R.raw.privacy_notice).bufferedReader().use { it.readText() }
        }
        AlertDialog(onDismissRequest = { visible = false }, title = { Text(texts(R.string.privacy_and_data)) }, text = {
            SelectionContainer {
                Text(notice, fontSize = 13.sp, lineHeight = 22.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("privacy-notice-text"))
            }
        }, confirmButton = { TextButton(onClick = { visible = false }, modifier = Modifier.testTag("privacy-notice-close")) { Text(texts(R.string.close)) } })
    }
}
