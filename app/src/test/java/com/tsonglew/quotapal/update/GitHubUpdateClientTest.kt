package com.tsonglew.quotapal.update

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

class GitHubUpdateClientTest {
    private val server = MockWebServer()
    private val certificate = "ab".repeat(32)
    private val source = "a".repeat(40)
    private lateinit var api: GitHubUpdateClient

    @Before fun start() {
        server.start()
        api = checker()
    }
    @After fun stop() { server.shutdown() }

    private fun checker(client: OkHttpClient = OkHttpClient()) = GitHubUpdateClient(
        installedVersionCode = 20, signingCertificateSha256 = certificate.uppercase().chunked(2).joinToString(":"),
        client = client, apiUrl = server.url("/releases?per_page=100"))

    private fun release(
        code: Long = 21, tag: String = "build-123-1", draft: Boolean = false,
        signing: String = certificate, version: String = "0.1.0-alpha21",
        releaseUrl: String = "https://github.com/tsonglew/QuotaPal/releases/tag/$tag",
        assetUrl: String = "https://github.com/tsonglew/QuotaPal/releases/download/$tag/QuotaPal-$version.apk",
        assetState: String = "uploaded", assetSize: Long = 1000, includeAsset: Boolean = true,
        metadataOverride: String? = null,
    ): JsonObject {
        val metadata = buildJsonObject {
            put("schema", 1); put("version_code", code); put("version_name", version)
            put("source_sha", source); put("signing_cert_sha256", signing); put("apk_name", "QuotaPal-$version.apk")
        }
        return buildJsonObject {
            put("tag_name", tag); put("draft", draft); put("prerelease", true); put("html_url", releaseUrl)
            put("body", "Release notes\n\n<!-- quotapal-update\n${metadataOverride ?: metadata}\n-->\n")
            putJsonArray("assets") {
                if (includeAsset) addJsonObject {
                    put("name", "QuotaPal-$version.apk"); put("state", assetState); put("size", assetSize)
                    put("browser_download_url", assetUrl)
                }
            }
        }
    }

    private fun enqueue(vararg releases: JsonElement, link: String? = null) {
        server.enqueue(MockResponse().setBody(JsonArray(releases.toList()).toString()).apply {
            if (link != null) addHeader("Link", link)
        })
    }

    @Test fun selectsHighestCodeAmongPrereleasesRegardlessOfListOrder() = runBlocking {
        enqueue(release(21), release(24, "v0.1.0-alpha24"), release(22))
        val update = api.latest()!!
        assertEquals(24L, update.versionCode)
        assertEquals("https://github.com/tsonglew/QuotaPal/releases/tag/v0.1.0-alpha24", update.releaseUrl)
        assertEquals(source, update.sourceSha)
    }

    @Test fun excludesDraftsSameCodeDowngradesAndDifferentSigningChannels() = runBlocking {
        enqueue(release(22, draft = true), release(20), release(19), release(23, signing = "cd".repeat(32)))
        assertNull(api.latest())
    }

    @Test fun requiresACompleteUploadedApkAndTrustedDownloadAndReleaseUrls() = runBlocking {
        enqueue(release(includeAsset = false), release(assetState = "new"), release(assetSize = 0),
            release(assetUrl = "https://evil.example/QuotaPal.apk"),
            release(assetUrl = "https://github.com/other/QuotaPal/releases/download/build-123-1/QuotaPal-0.1.0-alpha21.apk"),
            release(assetUrl = "http://github.com/tsonglew/QuotaPal/releases/download/build-123-1/QuotaPal-0.1.0-alpha21.apk"),
            release(releaseUrl = "https://github.com/tsonglew/QuotaPal/releases/tag/build-123-1?next=evil"),
            release(releaseUrl = "https://github.com/tsonglew/QuotaPal/releases/tag/another-tag"))
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
        val wrongFilename = JsonObject(valid + ("body" to JsonPrimitive(body.replace("QuotaPal-0.1.0-alpha21.apk", "other.apk"))))
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
