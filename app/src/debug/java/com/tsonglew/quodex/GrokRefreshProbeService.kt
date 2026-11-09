package com.tsonglew.quodex

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.tsonglew.quodex.subscriptions.browser.BrowserProvider
import com.tsonglew.quodex.subscriptions.browser.BrowserRefreshService

/** Debug-only IPC fixture. Every resource is intercepted; synthetic cookies never leave the device. */
class GrokRefreshProbeService : BrowserRefreshService() {
    override val profile = BrowserProvider.GROK
    private var reads = 0
    override fun intercept(request: WebResourceRequest): WebResourceResponse {
        val html = request.url.path == "/dashboard/usage"
        val body = when {
            html -> "<script>document.cookie='sso=fixture-ipc; path=/; Secure'</script>"
            request.url.path.orEmpty().contains("usage-status") -> {
                reads++
                """{"availability":"available","weeklyIncludedUsage":{"usedCents":$reads,"budgetCents":100}}"""
            }
            else -> """{"usdRemainingCents":1200}"""
        }
        return WebResourceResponse(if (html) "text/html" else "application/json", "UTF-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), body.byteInputStream())
    }
}
