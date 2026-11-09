package com.tsonglew.quodex.subscriptions.browser

import androidx.compose.foundation.layout.*
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
    val google = page.host == "accounts.google.com"
    Column(Modifier.fillMaxWidth().glassSceneBackground()) {
        GlassPanel(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!compact) Text(texts(if (google) R.string.subscription_browser_google_hint else when (page.status) {
                    BrowserReadStatus.READING -> R.string.subscription_browser_reading
                    BrowserReadStatus.RETRY -> R.string.subscription_browser_retry
                    BrowserReadStatus.LIMITED -> R.string.subscription_browser_limited
                    BrowserReadStatus.BLOCKED -> R.string.subscription_browser_blocked
                    else -> R.string.subscription_browser_sign_in
                }), style = MaterialTheme.typography.bodySmall)
                if (google) {
                    TextButton(onClick = onChooseGoogle, enabled = !page.loading) {
                        Text(texts(R.string.subscription_browser_google_choose))
                    }
                } else {
                    TextButton(onClick = { onRead() }, enabled = page.status != BrowserReadStatus.READING) {
                        Text(texts(R.string.subscription_browser_read))
                    }
                }
            }
        }
    }
}
