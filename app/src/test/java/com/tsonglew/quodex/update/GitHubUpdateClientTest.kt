package com.tsonglew.quodex.update

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.tsonglew.quodex.network.RequestRetry
import com.tsonglew.quodex.network.HttpFailure

class GitHubUpdateClientTest {
    private val server = MockWebServer()
    private val certificate = "ab".repeat(32)
    private val source = "a".repeat(40)
    private lateinit var api: GitHubUpdateClient

    @Before fun start() {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        api = checker()
    }
    @After fun stop() { server.shutdown() }

    private fun checker(client: OkHttpClient = OkHttpClient()) = GitHubUpdateClient(
        requestRetry = com.tsonglew.quodex.network.RequestRetry(maxAttempts = 1),
        installedVersionCode = 20, signingCertificateSha256 = certificate.uppercase().chunked(2).joinToString(":"),
        client = client, apiUrl = server.url("/releases?per_page=100"))

    private fun release(
        code: Long = 21, tag: String = "build-123-1", draft: Boolean = false,
        signing: String = certificate, version: String = "0.1.0-alpha21",
        releaseUrl: String = "https://github.com/tsonglew/Quodex/releases/tag/$tag",
        assetUrl: String = "https://github.com/tsonglew/Quodex/releases/download/$tag/Quodex-$version.apk",
        assetState: String = "uploaded", assetSize: Long = 1000, includeAsset: Boolean = true,
        digest: String? = null, metadataOverride: String? = null, productName: String = "Quodex", metadataMarker: String = "quodex",
    ): JsonObject {
        val apk = "$productName-$version.apk"
        val metadata = buildJsonObject {
            put("schema", 1); put("version_code", code); put("version_name", version)
            put("source_sha", source); put("signing_cert_sha256", signing); put("apk_name", apk)
        }
        return buildJsonObject {
            put("tag_name", tag); put("draft", draft); put("prerelease", true); put("html_url", releaseUrl)
            put("body", "Release notes\n\n<!-- $metadataMarker-update\n${metadataOverride ?: metadata}\n-->\n")
            putJsonArray("assets") {
                if (includeAsset) addJsonObject {
                    put("name", apk); put("state", assetState); put("size", assetSize)
                    put("browser_download_url", assetUrl)
                    digest?.let { put("digest", it) }
                }
            }
        }
    }

    private fun enqueue(vararg releases: JsonElement, link: String? = null) {
        server.enqueue(MockResponse().setBody(JsonArray(releases.toList()).toString()).apply {
            if (link != null) addHeader("Link", link)
        })
    }

    @Test fun retriesOnlyTheFailedPageAndStillSelectsNewestRelease() = runBlocking {
        val waits = mutableListOf<Long>()
        val retrying = GitHubUpdateClient(20, certificate, apiUrl = server.url("/releases?per_page=100"),
            requestRetry = RequestRetry(pause = { waits += it }))
        enqueue(release(), link = "<${server.url("/releases?per_page=100&page=2")}>; rel=\"next\"")
        server.enqueue(MockResponse().setResponseCode(503))
        enqueue(release(25))
        assertEquals(25L, retrying.latest()!!.versionCode)
        assertEquals(listOf(1000L), waits)
        assertEquals("/releases?per_page=100", server.takeRequest().path)
        repeat(2) { assertEquals("/releases?per_page=100&page=2", server.takeRequest().path) }
    }

    @Test fun rateLimited403WaitsButPermission403AndMalformedResponsesDoNotRetry() = runBlocking {
        var time = 1000L
        val waits = mutableListOf<Long>()
        val retrying = GitHubUpdateClient(20, certificate, apiUrl = server.url("/releases?per_page=100"), now = { time },
            requestRetry = RequestRetry(now = { time }, pause = { waits += it; time += it / 1000 }))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setHeader("X-RateLimit-Reset", "1005"))
        enqueue(release())
        assertNotNull(retrying.latest())
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(runCatching { retrying.latest() }.exceptionOrNull() is HttpFailure)
        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(runCatching { retrying.latest() }.exceptionOrNull() is IOException)
        assertEquals(listOf(5000L), waits)
        assertEquals(4, server.requestCount)
    }

    @Test fun failedCheckHasBoundedRequestsAndLongLimitBlocksRepeatedManualAttempts() = runBlocking {
        val retrying = GitHubUpdateClient(20, certificate, apiUrl = server.url("/releases?per_page=100"), now = { 1000 },
            requestRetry = RequestRetry(now = { 1000 }, pause = {}))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502)) }
        assertTrue(runCatching { retrying.latest() }.exceptionOrNull() is HttpFailure)
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        repeat(2) { assertEquals(1120L, (runCatching { retrying.latest() }.exceptionOrNull() as HttpFailure).retryAt) }
        assertEquals(4, server.requestCount)
    }

    @Test fun cancellingRetryWaitDoesNotIssueAnotherRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val retrying = GitHubUpdateClient(20, certificate, apiUrl = server.url("/releases?per_page=100"),
            requestRetry = RequestRetry(pause = { entered.complete(Unit); awaitCancellation() }))
        server.enqueue(MockResponse().setResponseCode(503))
        val job = launch { retrying.latest() }
        withTimeout(5000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(1, server.requestCount)
    }

    @Test fun selectsHighestCodeAmongPrereleasesRegardlessOfListOrder() = runBlocking {
        enqueue(release(21), release(24, "v0.1.0-alpha24"), release(22))
        val update = api.latest()!!
        assertEquals(24L, update.versionCode)
        assertEquals("https://github.com/tsonglew/Quodex/releases/tag/v0.1.0-alpha24", update.releaseUrl)
        assertEquals(source, update.sourceSha)
    }

    @Test fun carriesTheExactAssetSizeUrlAndDigestForInAppInstallation() = runBlocking {
        enqueue(release(digest = "sha256:" + "cd".repeat(32)))
        val update = api.latest()!!
        assertEquals(1000L, update.apkSize)
        assertEquals("cd".repeat(32), update.apkSha256)
        assertEquals("https://github.com/tsonglew/Quodex/releases/download/build-123-1/Quodex-0.1.0-alpha21.apk", update.apkUrl)
        enqueue(release(digest = "sha256:broken"), release(assetSize = GitHubUpdateClient.MAX_APK_BYTES + 1))
        assertNull(api.latest())
    }

    @Test fun acceptsSignedQuotaPalReleaseAssetsDuringTheRenameTransition() = runBlocking {
        val legacy = release(productName = "QuotaPal", metadataMarker = "quotapal",
            assetUrl = "https://github.com/tsonglew/Quodex/releases/download/build-123-1/QuotaPal-0.1.0-alpha21.apk")
        enqueue(legacy)
        assertEquals(21L, api.latest()!!.versionCode)
    }

    @Test fun excludesDraftsSameCodeDowngradesAndDifferentSigningChannels() = runBlocking {
        enqueue(release(22, draft = true), release(20), release(19), release(23, signing = "cd".repeat(32)))
        assertNull(api.latest())
    }

    @Test fun requiresACompleteUploadedApkAndTrustedDownloadAndReleaseUrls() = runBlocking {
        enqueue(release(includeAsset = false), release(assetState = "new"), release(assetSize = 0),
            release(assetUrl = "https://evil.example/Quodex.apk"),
            release(assetUrl = "https://github.com/other/Quodex/releases/download/build-123-1/Quodex-0.1.0-alpha21.apk"),
            release(assetUrl = "http://github.com/tsonglew/Quodex/releases/download/build-123-1/Quodex-0.1.0-alpha21.apk"),
            release(releaseUrl = "https://github.com/tsonglew/Quodex/releases/tag/build-123-1?next=evil"),
            release(releaseUrl = "https://github.com/tsonglew/Quodex/releases/tag/another-tag"))
        assertNull(api.latest())
    }

    @Test fun malformedEntriesAreSkippedWithoutHidingValidCandidates() = runBlocking {
        enqueue(JsonNull, JsonPrimitive("oops"), release(metadataOverride = "not JSON"),
            release(metadataOverride = "{}"), release(metadataOverride = """{"schema":"1"}"""),
            release(version = "../../evil"), release(code = 2_100_000_001), release(25))
        assertEquals(25L, api.latest()!!.versionCode)
    }

    @Test fun ambiguousMetadataAndWrongPrimitiveTypesAreRejected() = runBlocking {
        val valid = release()
        val body = valid.getValue("body").jsonPrimitive.content
        val duplicate = JsonObject(valid + ("body" to JsonPrimitive(body + body)))
        val stringCode = JsonObject(valid + ("body" to JsonPrimitive(body.replace("\"version_code\":21", "\"version_code\":\"21\""))))
        val wrongSha = JsonObject(valid + ("body" to JsonPrimitive(body.replace(source, "bad"))))
        val wrongFilename = JsonObject(valid + ("body" to JsonPrimitive(body.replace("Quodex-0.1.0-alpha21.apk", "other.apk"))))
        enqueue(duplicate, stringCode, wrongSha, wrongFilename)
        assertNull(api.latest())
    }

    @Test fun followsPaginationAndScansBeforeChoosingVersion() = runBlocking {
        enqueue(release(22), link = "<${server.url("/releases?per_page=100&page=2")}>; rel=\"next\"")
        enqueue(release(28), release(21))
        assertEquals(28L, api.latest()!!.versionCode)
        assertEquals("/releases?per_page=100", server.takeRequest().path)
        assertEquals("/releases?per_page=100&page=2", server.takeRequest().path)
    }

    @Test fun untrustedOrNonProgressingPaginationFailsWithoutFollowingIt() = runBlocking {
        val badLinks = listOf("https://evil.example/releases?per_page=100&page=2",
            server.url("/other?per_page=100&page=2").toString(),
            server.url("/releases?per_page=100&page=1").toString(),
            server.url("/releases?per_page=100&page=2&page=3").toString())
        for (url in badLinks) {
            enqueue(release(), link = "<$url>; rel=\"next\"")
            expectIoFailure { api.latest() }
        }
        assertEquals(badLinks.size, server.requestCount)
    }

    @Test fun tooManyPagesIsAnErrorInsteadOfAnIncompleteLatestResult() = runBlocking {
        for (page in 1..5) enqueue(release(21 + page.toLong()),
            link = "<${server.url("/releases?per_page=100&page=${page + 1}")}>; rel=\"next\"")
        expectIoFailure { api.latest() }
        assertEquals(5, server.requestCount)
    }

    @Test fun serverErrorsMalformedJsonAndOversizeResponsesAreNotReportedAsUpToDate() = runBlocking {
        for (status in listOf(403, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("private upstream response"))
            val failure = expectIoFailure { api.latest() }
            assertFalse(failure.message.orEmpty().contains("private upstream response"))
        }
        server.enqueue(MockResponse().setBody("not JSON"))
        expectIoFailure { api.latest() }
        server.enqueue(MockResponse().setBody("{}"))
        expectIoFailure { api.latest() }
        server.enqueue(MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)))
        expectIoFailure { api.latest() }
        Unit
    }

    @Test fun publicRequestContainsNoAccountCredentialsAndRedirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
        expectIoFailure { api.latest() }
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("ChatGPT-Account-Id"))
        assertNull(request.getHeader("Cookie"))
        assertEquals("GET", request.method)
        assertEquals(0L, request.bodySize)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationCancelsTheUnderlyingHttpCall() = runBlocking {
        val client = OkHttpClient()
        val checker = checker(client)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { checker.latest() }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        withTimeout(5000) {
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }
        assertTrue(pending.isCancelled)
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit): IOException {
        try { block() } catch (failure: IOException) { return failure }
        throw AssertionError("Expected an IOException")
    }
}
