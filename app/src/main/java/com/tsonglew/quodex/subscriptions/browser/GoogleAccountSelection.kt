package com.tsonglew.quodex.subscriptions.browser

import java.net.URI
import kotlinx.serialization.json.JsonPrimitive

/** Only the explicitly selected email is filled; no password or Google token is requested. */
internal object GoogleAccountSelection {
    fun canFill(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "accounts.google.com" && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    fun script(email: String): String? {
        if (email.length !in 3..320 || '@' !in email || email.any { it.isISOControl() }) return null
        val value = JsonPrimitive(email).toString()
        return """(() => {
            if (location.origin !== 'https://accounts.google.com') return false;
            const field = document.querySelector('input[type="email"], input#identifierId');
            if (!field || field.disabled || field.readOnly || !field.getClientRects().length) return false;
            if (field.type !== 'email' && field.type !== 'text') return false;
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            setter.call(field, $value);
            field.dispatchEvent(new Event('input', {bubbles: true}));
            field.dispatchEvent(new Event('change', {bubbles: true}));
            field.focus();
            return field.value === $value;
        })()""".trimIndent()
    }
}
