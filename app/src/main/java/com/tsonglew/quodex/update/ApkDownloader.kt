package com.tsonglew.quodex.update

import com.tsonglew.quodex.i18n.*
import com.tsonglew.quodex.R
import com.tsonglew.quodex.network.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Uses its own public HTTP client; account credentials never enter the download path. */
class ApkDownloader(
    client: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = { java.time.Instant.now().epochSecond },
    private val requestRetry: RequestRetry = RequestRetry(now = now),
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()
    @Volatile private var cooldown: HttpFailure? = null

    suspend fun download(update: AppUpdate, destination: File,
        onRetry: (Int, Long) -> Unit = { _, _ -> }, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        trustedApkUrl(update) ?: throw UserVisibleException(UiText(R.string.valid_apk_details_are_missing_check_for))
        val checksum = update.apkSha256?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            ?: throw UserVisibleException(UiText(R.string.this_release_has_no_apk_checksum_check))
        if (update.apkSize !in 1..GitHubUpdateClient.MAX_APK_BYTES) throw UserVisibleException(UiText(R.string.the_apk_size_is_not_supported))
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        var complete = false
        try {
            requestRetry.run(classify = ::networkRetry, onRetry = onRetry) {
                // Start from the public URL again: a CDN redirect can expire. Never append
                // bytes from different attempts or expose an unverified partial APK.
                partial.delete()
                progress(0, update.apkSize)
                attempt(update, destination, partial, checksum, progress)
            }
            complete = true
        } catch (failure: HttpFailure) {
            throw UserVisibleException(if (failure.retryAt?.let { it > now() } == true) UiText(R.string.network_retry_after)
                else UiText(R.string.download_failed_http_value_try_again_later, failure.status))
        } finally {
            partial.delete()
            if (!complete) destination.delete()
        }
    }

    private suspend fun attempt(update: AppUpdate, destination: File, partial: File, checksum: String,
        progress: (Long, Long) -> Unit) {
        cooldown?.takeIf { (it.retryAt ?: 0) > now() }?.let { throw it }
        var url = requireNotNull(trustedApkUrl(update))
        for (redirect in 0..5) {
            val call = client.newCall(Request.Builder().url(url)
                .header("User-Agent", "Quodex-InApp-Update").header("Accept-Encoding", "identity").build())
            val finished = coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    val response = try { call.execute() } catch (failure: IOException) {
                        ensureActive()
                        throw TransportFailure(failure)
                    }
                    response.use {
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            val next = response.header("Location")?.let(url::resolve)
                            if (redirect == 5 || next == null || !trustedAssetRedirect(next))
                                throw UserVisibleException(UiText(R.string.the_download_address_is_invalid_check_for))
                            url = next
                            false
                        } else {
                            if (response.code != 200) {
                                val deadline = retryAfterAt(response.header("Retry-After"), now())
                                    ?: if (response.code == 429) now() + 60 else null
                                val failure = HttpFailure(response.code, deadline)
                                if (deadline != null) cooldown = failure
                                throw failure
                            }
                            val body = response.body ?: throw UserVisibleException(UiText(R.string.the_apk_response_is_empty))
                            if (body.contentLength() != -1L && body.contentLength() != update.apkSize)
                                throw UserVisibleException(UiText(R.string.the_apk_size_does_not_match_download))
                            val digest = MessageDigest.getInstance("SHA-256")
                            var received = 0L
                            var lastPercent = -1L
                            body.byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        ensureActive()
                                        val count = try { input.read(buffer) } catch (failure: IOException) {
                                            ensureActive()
                                            throw TransportFailure(failure)
                                        }
                                        if (count == -1) break
                                        received += count
                                        if (received > update.apkSize) throw UserVisibleException(UiText(R.string.the_apk_size_does_not_match_download))
                                        digest.update(buffer, 0, count)
                                        output.write(buffer, 0, count) // Storage errors are not network retries.
                                        val percent = received * 100 / update.apkSize
                                        if (percent != lastPercent) { progress(received, update.apkSize); lastPercent = percent }
                                    }
                                }
                            }
                            ensureActive()
                            if (received != update.apkSize) throw TransportFailure(java.io.EOFException())
                            if (digest.digest().hex() != checksum)
                                throw UserVisibleException(UiText(R.string.apk_verification_failed_download_it_again))
                            if (!partial.renameTo(destination)) throw UserVisibleException(UiText(R.string.could_not_save_the_apk_check_available))
                            true
                        }
                    }
                } finally { cancellation.cancel() }
            }
            if (finished) return
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun trustedApkUrl(update: AppUpdate): HttpUrl? {
    val release = update.releaseUrl.toHttpUrlOrNull() ?: return null
    val tag = release.pathSegments.lastOrNull()?.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(it) } ?: return null
    if (update.releaseUrl != "https://github.com/tsonglew/Quodex/releases/tag/$tag") return null
    if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?").matches(update.versionName)) return null
    val expected = "https://github.com/tsonglew/Quodex/releases/download/$tag/"
    val url = update.apkUrl ?: return null
    if (url !in listOf("${expected}Quodex-${update.versionName}.apk", "${expected}QuotaPal-${update.versionName}.apk")) return null
    return url.toHttpUrlOrNull()
}

internal fun trustedAssetRedirect(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
    url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
