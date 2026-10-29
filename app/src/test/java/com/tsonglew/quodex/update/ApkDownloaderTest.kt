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

class ApkDownloaderTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private val payload = "signed apk fixture bytes"
    private val update get() = AppUpdate(24, "0.1.0-alpha24",
        "https://github.com/tsonglew/Quodex/releases/tag/build-42-1", "a".repeat(40),
        "https://github.com/tsonglew/Quodex/releases/download/build-42-1/Quodex-0.1.0-alpha24.apk",
        payload.length.toLong(), MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).hex())
    private lateinit var downloader: ApkDownloader
    private val destination get() = File(directory, "update.apk")

    @Before fun prepare() {
        server.start()
        directory = Files.createTempDirectory("quodex-download-test").toFile()
        // Keep production URL validation intact, redirect only the test transport to localhost.
        downloader = ApkDownloader(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build())
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

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected download rejection") } catch (_: IOException) { }
    }
}
