package com.tsonglew.quodex.subscriptions.browser

import com.tsonglew.quodex.subscriptions.SubscriptionProvider
import java.net.URI

/** Fixed destinations; neither a deep link nor webpage can select the data origin. */
enum class BrowserProvider(val provider: SubscriptionProvider, val origin: String, val dashboard: String, val profile: String) {
    KIMI(SubscriptionProvider.KIMI, "https://www.kimi.com", "https://www.kimi.com/settings/subscription", "subscription_kimi"),
    WORKBUDDY(SubscriptionProvider.WORKBUDDY, "https://www.workbuddy.ai", "https://www.workbuddy.ai/profile/plans-usage", "subscription_workbuddy"),
    GROK(SubscriptionProvider.GROK, "https://grok.com", "https://grok.com/dashboard/usage", "subscription_grok");

    fun canRead(url: String?): Boolean = uri(url)?.let {
        it.scheme == "https" && it.host != null && it.rawUserInfo == null && (it.port == -1 || it.port == 443) &&
            it.host == URI(origin).host
    } == true

    fun canNavigate(url: String?): Boolean = uri(url)?.let {
        it.scheme == "https" && it.host != null && it.rawUserInfo == null && (it.port == -1 || it.port == 443)
    } == true

    companion object {
        // Cursor accounts that have migrated use the same Grok / Cursor quota pool.
        fun forProvider(provider: SubscriptionProvider): BrowserProvider? = when (provider) {
            SubscriptionProvider.KIMI -> KIMI
            SubscriptionProvider.WORKBUDDY -> WORKBUDDY
            SubscriptionProvider.CURSOR, SubscriptionProvider.GROK -> GROK
            else -> null
        }
        fun forProcess(packageName: String, processName: String): BrowserProvider? =
            entries.singleOrNull { processName == "$packageName:${it.profile}" }
        private fun uri(value: String?): URI? = value?.let { runCatching { URI(it) }.getOrNull() }
    }
}
