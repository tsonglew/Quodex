package com.tsonglew.quodex.update

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.tsonglew.quodex.network.RequestRetry

class ApkDownloaderTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private val payload = "signed apk fixture bytes"
    private val update get() = AppUpdate(24, "0.1.0-alpha24",
        "https://github.com/tsonglew/Quodex/releases/tag/build-42-1", "a".repeat(40),
        "https://github.com/tsonglew/Quodex/releases/download/build-42-1/Quodex-0.1.0-alpha24.apk",
        payload.length.toLong(), MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).hex())
    private lateinit var downloader: ApkDownloader
    private lateinit var client: OkHttpClient
    private val destination get() = File(directory, "update.apk")

    @Before fun prepare() {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        directory = Files.createTempDirectory("quodex-download-test").toFile()
        // Keep production URL validation intact, redirect only the test transport to localhost.
        client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath).newBuilder().host("127.0.0.1").build()).build())
        }.build()
        downloader = ApkDownloader(client, requestRetry = RequestRetry(pause = {}))
    }
    @After fun cleanup() { server.shutdown(); directory.deleteRecursively() }

    @Test fun followsGitHubAssetRedirectAndWritesOnlyVerifiedFile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/download/file?token=public"))
        server.enqueue(MockResponse().setBody(payload))
        val progress = mutableListOf<Long>()
        downloader.download(update, destination) { bytes, total -> assertEquals(update.apkSize, total); progress += bytes }
        assertEquals(payload, destination.readText())
        assertEquals(update.apkSize, progress.last())
        assertFalse(File(directory, "update.apk.part").exists())
        repeat(2) {
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("ChatGPT-Account-Id"))
        }
    }

    @Test fun wrongDigestTruncatedAndOversizeBodiesNeverLeaveInstallableFiles() = runBlocking {
        for (response in listOf(MockResponse().setBody("x".repeat(payload.length)),
            MockResponse().setBody("short"), MockResponse().setChunkedBody(payload + "overflow", 3))) {
            server.enqueue(response)
            expectFailure { downloader.download(update, destination) { _, _ -> } }
            assertFalse(destination.exists()); assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun untrustedUrlsAndMissingChecksumsFailBeforeNetworking() = runBlocking {
        for (invalid in listOf(update.copy(apkUrl = "https://evil.example/payload.apk"),
            update.copy(apkSha256 = null), update.copy(apkSize = Long.MAX_VALUE),
            update.copy(releaseUrl = update.releaseUrl + "?redirect=evil"))) {
            expectFailure { downloader.download(invalid, destination) { _, _ -> } }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectsCannotDowngradeTlsOrEscapeTheAssetHosts() = runBlocking {
        for (target in listOf("http://github.com/file", "https://evil.example/file", "https://github.com.evil.example/file")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
            expectFailure { downloader.download(update, destination) { _, _ -> } }
            assertFalse(destination.exists())
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun cancellationClosesStalledDownloadAndAllowsRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch { downloader.download(update, destination) { _, _ -> } }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) { pending.cancelAndJoin() }
        assertTrue(directory.listFiles()!!.isEmpty())
        server.enqueue(MockResponse().setBody(payload))
        downloader.download(update, destination) { _, _ -> }
        assertEquals(payload, destination.readText())
    }

    @Test fun interruptedDownloadRestartsFromPublicUrlAndNeverAppendsPartialBytes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/old"))
        server.enqueue(MockResponse().setBody(payload).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/fresh"))
        server.enqueue(MockResponse().setBody(payload))
        val progress = mutableListOf<Long>()
        val retries = mutableListOf<Pair<Int, Long>>()
        downloader.download(update, destination, onRetry = { attempt, wait -> retries += attempt to wait }) { bytes, _ -> progress += bytes }
        assertEquals(payload, destination.readText())
        assertEquals(listOf(2 to 1000L), retries)
        assertEquals(2, progress.count { it == 0L })
        assertEquals(4, server.requestCount)
        val requests = List(4) { server.takeRequest() }
        assertEquals(requests[0].path, requests[2].path)
        assertEquals("/old", requests[1].path)
        assertEquals("/fresh", requests[3].path)
        requests.forEach { assertNull(it.getHeader("Range")); assertNull(it.getHeader("Authorization")) }
        assertFalse(File(directory, "update.apk.part").exists())
    }

    @Test fun httpRetriesAreBoundedAndLeaveNoPartialFile() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        expectFailure { downloader.download(update, destination) { _, _ -> } }
        assertEquals(3, server.requestCount)
        assertTrue(directory.listFiles()!!.isEmpty())
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody(payload))
        downloader.download(update, destination) { _, _ -> }
        assertEquals(payload, destination.readText())
        assertEquals(5, server.requestCount)
    }

    @Test fun cancellingDuringBackoffStopsRetriesAndRemovesPartialFile() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val retrying = ApkDownloader(client, requestRetry = RequestRetry(pause = { entered.complete(Unit); awaitCancellation() }))
        server.enqueue(MockResponse().setBody(payload).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val job = launch { retrying.download(update, destination) { _, _ -> } }
        withTimeout(5000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(1, server.requestCount)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun longRetryAfterBlocksNewDownloadsUntilDeadline() = runBlocking {
        var time = 1000L
        val retrying = ApkDownloader(client, now = { time }, requestRetry = RequestRetry(now = { time }, pause = {}))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        repeat(2) { expectFailure { retrying.download(update, destination) { _, _ -> } } }
        assertEquals(1, server.requestCount)
        assertTrue(directory.listFiles()!!.isEmpty())
        time += 120
        server.enqueue(MockResponse().setBody(payload))
        retrying.download(update, destination) { _, _ -> }
        assertEquals(2, server.requestCount)
    }

    @Test fun diskWriteFailureIsNotMistakenForNetworkInterruption() = runBlocking {
        val partial = File(directory, "update.apk.part")
        partial.mkdirs()
        File(partial, "block-removal").writeText("fixture")
        server.enqueue(MockResponse().setBody(payload))
        expectFailure { downloader.download(update, destination) { _, _ -> } }
        assertEquals(1, server.requestCount)
        assertFalse(destination.exists())
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected download rejection") } catch (_: IOException) { }
    }
}
