package com.tsonglew.quodex.subscriptions.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tsonglew.quodex.R
import com.tsonglew.quodex.i18n.localTextResources
import com.tsonglew.quodex.ui.*

@Composable
internal fun BrowserLoginHeader(profile: BrowserProvider, page: BrowserPageState, compact: Boolean = false, onClose: () -> Unit) {
    val texts = localTextResources()
    Column(Modifier.fillMaxWidth().glassSceneBackground()) {
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (!compact) Text(texts(R.string.subscription_browser_title, profile.provider.displayName), style = MaterialTheme.typography.titleMedium)
                    Text(page.host.ifBlank { java.net.URI(profile.origin).host }, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onClose() }) { Text(texts(R.string.close)) }
            }
        }
        if (page.loading || page.status == BrowserReadStatus.READING) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun BrowserLoginFooter(page: BrowserPageState, onChooseGoogle: () -> Unit, compact: Boolean = false, onRead: () -> Unit) {
    val texts = localTextResources()
    val statusText = when (page.status) {
        BrowserReadStatus.READING -> R.string.subscription_browser_reading
        BrowserReadStatus.RETRY -> R.string.subscription_browser_retry
        BrowserReadStatus.LIMITED -> R.string.subscription_browser_limited
        BrowserReadStatus.BLOCKED -> R.string.subscription_browser_blocked
        else -> null
    }
    val google = page.host == "accounts.google.com"
    val chooseGoogle = google && (page.documentReady || page.loading)
    val recover = !google || statusText != null || (!page.documentReady && !page.loading)
    Column(Modifier.fillMaxWidth().glassSceneBackground()) {
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!compact || statusText != null) Text(
                    texts(statusText ?: if (google) R.string.subscription_browser_google_hint else R.string.subscription_browser_sign_in),
                    modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth()) {
                    val actionModifier = if (chooseGoogle && recover) Modifier.weight(1f) else Modifier
                    if (chooseGoogle) {
                        TextButton(onClick = onChooseGoogle, enabled = !page.loading, modifier = actionModifier) {
                            Text(texts(R.string.subscription_browser_google_choose))
                        }
                    }
                    if (recover) {
                        TextButton(onClick = { onRead() }, enabled = page.status != BrowserReadStatus.READING, modifier = actionModifier) {
                            Text(texts(R.string.subscription_browser_read))
                        }
                    }
                }
            }
        }
    }
}
