package com.tsonglew.quodex.update

import com.tsonglew.quodex.network.*

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class AppUpdate(
    val versionCode: Long, val versionName: String, val releaseUrl: String, val sourceSha: String,
    val apkUrl: String? = null, val apkSize: Long = 0, val apkSha256: String? = null,
)

fun interface UpdateSource { suspend fun latest(): AppUpdate? }

/** Public release metadata only. This client never reads Codex credentials or its HTTP client. */
class GitHubUpdateClient(
    private val installedVersionCode: Long,
    private val signingCertificateSha256: String,
    client: OkHttpClient = OkHttpClient(),
    private val apiUrl: HttpUrl = "https://api.github.com/repos/tsonglew/Quodex/releases?per_page=100".toHttpUrl(),
    private val now: () -> Long = { java.time.Instant.now().epochSecond },
    private val requestRetry: RequestRetry = RequestRetry(now = now),
) : UpdateSource {
    // Also disable redirects for injected clients: a Link or HTTP redirect must not change the endpoint.
    private val client = client.newBuilder().callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    @Volatile private var cooldown: HttpFailure? = null

    override suspend fun latest(): AppUpdate? {
        // A completed scan can legitimately return null; the wrapper distinguishes it from a timeout.
        val result = withTimeoutOrNull(60_000) { ScanResult(scan()) }
            ?: throw (cooldown?.takeIf { (it.retryAt ?: 0) > now() } ?: IOException("Update check timed out"))
        return result.update
    }

    private data class ScanResult(val update: AppUpdate?)
    private data class Page(val releases: JsonArray, val link: String?)
    private class InvalidResponse(message: String, cause: Exception? = null) : IOException(message, cause)

    private suspend fun scan(): AppUpdate? {
        var next: HttpUrl? = apiUrl
        var best: AppUpdate? = null
        var pages = 0
        while (next != null) {
            if (++pages > MAX_PAGES) throw IOException("Update list exceeds page limit")
            val url = next
            val page = requestRetry.run(classify = ::networkRetry) {
                // Retain a server deadline even if the previous check was cancelled in backoff.
                cooldown?.takeIf { (it.retryAt ?: 0) > now() }?.let { throw it }
                fetch(url)
            }
            page.releases.forEach { item ->
                candidate(item as? JsonObject)?.let { update ->
                    if (best == null || update.versionCode > best!!.versionCode) best = update
                }
            }
            next = nextPage(page.link, pages)
        }
        return best
    }

    private fun nextPage(link: String?, currentPage: Int): HttpUrl? {
        if (link.isNullOrBlank()) return null
        var next: HttpUrl? = null
        for (part in link.split(',')) {
            val match = LINK.matchEntire(part.trim()) ?: throw IOException("Invalid update pagination")
            if ("next" !in match.groupValues[2].split(' ')) continue
            if (next != null) throw IOException("Duplicate update pagination")
            val url = match.groupValues[1].toHttpUrlOrNull() ?: throw IOException("Invalid update pagination")
            if (url.scheme != apiUrl.scheme || url.host != apiUrl.host || url.port != apiUrl.port ||
                url.encodedPath != apiUrl.encodedPath || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.fragment != null || url.queryParameterNames != setOf("per_page", "page") ||
                url.queryParameterValues("per_page") != listOf("100") ||
                url.queryParameterValues("page") != listOf((currentPage + 1).toString())) {
                throw IOException("Untrusted update pagination")
            }
            next = url
        }
        return next
    }

    private suspend fun fetch(url: HttpUrl): Page = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Quodex-Update-Checker").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(TransportFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (it.code != 200) {
                            val limited = it.code == 429 || it.code == 403 && (it.header("X-RateLimit-Remaining") == "0" || it.header("Retry-After") != null)
                            val deadline = retryAfterAt(it.header("Retry-After"), now())
                                ?: if (limited) it.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { value -> value > now() } ?: now() + 60 else null
                            val failure = HttpFailure(it.code, deadline, limited)
                            if (failure.retryable && deadline != null) cooldown = failure
                            throw failure
                        }
                        val body = it.body ?: throw InvalidResponse("Missing update response")
                        val source = body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw InvalidResponse("Update response is too large")
                        val releases = Json.parseToJsonElement(source.readUtf8()) as? JsonArray
                            ?: throw InvalidResponse("Invalid update list")
                        val page = Page(releases, it.headers.values("Link").takeIf { values -> values.isNotEmpty() }?.joinToString(","))
                        if (continuation.isActive) continuation.resume(page)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            when (failure) {
                                is HttpFailure, is InvalidResponse -> failure
                                is IOException -> TransportFailure(failure)
                                else -> InvalidResponse("Invalid update response", failure)
                            })
                    }
                }
            }
        })
    }

    private fun candidate(release: JsonObject?): AppUpdate? {
        if (release == null || release.boolean("draft") != false) return null
        val tag = release.string("tag_name")?.takeIf { TAG.matches(it) } ?: return null
        val releaseUrl = "$RELEASE_BASE/tag/$tag"
        if (release.string("html_url") != releaseUrl) return null
        val body = release.string("body") ?: return null
        val matches = METADATA.findAll(body).take(2).toList()
        if (matches.size != 1) return null
        val metadata = try { Json.parseToJsonElement(matches.single().groupValues[1]) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        if (metadata.integer("schema") != 1L) return null
        val code = metadata.integer("version_code")?.takeIf { it > installedVersionCode && it <= 2_100_000_000L } ?: return null
        val version = metadata.string("version_name")?.takeIf { VERSION.matches(it) } ?: return null
        val source = metadata.string("source_sha")?.takeIf { SHA.matches(it) } ?: return null
        val certificate = normalizeCertificate(metadata.string("signing_cert_sha256") ?: return null) ?: return null
        if (certificate != normalizeCertificate(signingCertificateSha256)) return null
        // Releases published before the product rename keep their original asset name.
        // Continue accepting those signed builds while all new releases use Quodex.
        val apk = metadata.string("apk_name")?.takeIf {
            it == "Quodex-$version.apk" || it == "QuotaPal-$version.apk"
        } ?: return null
        val assetUrl = "$RELEASE_BASE/download/$tag/$apk"
        val assets = release["assets"] as? JsonArray ?: return null
        val asset = assets.mapNotNull { it as? JsonObject }.singleOrNull {
            it.string("name") == apk && it.string("state") == "uploaded" &&
                (it.integer("size") ?: 0) in 1..MAX_APK_BYTES && it.string("browser_download_url") == assetUrl
        } ?: return null
        val digest = asset.string("digest")?.let {
            if (!Regex("sha256:[0-9a-fA-F]{64}").matches(it)) return null
            it.removePrefix("sha256:").lowercase()
        }
        return AppUpdate(code, version, releaseUrl, source, assetUrl, asset.integer("size")!!, digest)
    }

    companion object {
        const val MAX_APK_BYTES = 128 * 1024 * 1024L
        private const val MAX_PAGES = 5
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024L
        private const val RELEASE_BASE = "https://github.com/tsonglew/Quodex/releases"
        private val LINK = Regex("""<([^>]+)>\s*;\s*rel="([^"]+)"""")
        private val METADATA = Regex("""<!-- (?:quodex|quotapal)-update\s*\n(.*?)\n\s*-->""", RegexOption.DOT_MATCHES_ALL)
        private val TAG = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?")
        private val SHA = Regex("[0-9a-f]{40}")
        private fun normalizeCertificate(value: String): String? = value.replace(":", "").lowercase()
            .takeIf { Regex("[0-9a-f]{64}").matches(it) }
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.integer(key: String): Long? = (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.longOrNull
        private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.booleanOrNull
    }
}
