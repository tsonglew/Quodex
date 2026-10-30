package com.tsonglew.quodex.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class CodexApiTest {
    private val server = MockWebServer()
    private lateinit var api: CodexApi
    private val session = Session("access", "refresh", "test-account", 2000)
    @Before fun start() { server.start(); api = CodexApi(requestRetry = com.tsonglew.quodex.network.RequestRetry(maxAttempts = 1), authBase = server.url("/").toString().trimEnd('/'), usageUrl = server.url("/usage").toString(), now = { 1000 }) }
    @After fun stop() { server.shutdown() }
    @Test fun usageSendsCredentialsOnlyInHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"rate_limit":{"primary_window":{"used_percent":12}}}"""))
        api.usage(session)
        val request = server.takeRequest()
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals("test-account", request.getHeader("ChatGPT-Account-Id"))
        assertEquals("/usage", request.path)
    }
    @Test fun diagnosticsCountTransportWithoutLeakingCredentialsOrBody() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("quota-api-diagnostics").toFile()
        try {
            val diagnostics = com.tsonglew.quodex.diagnostics.Diagnostics(java.io.File(directory, "events.log"))
            val instrumented = CodexApi(requestRetry = com.tsonglew.quodex.network.RequestRetry(maxAttempts = 1), usageUrl = server.url("/private-usage-path").toString(), diagnostics = diagnostics)
            server.enqueue(MockResponse().setResponseCode(503).setBody("sensitive-response-body"))
            try { instrumented.usage(Session("secret-access", "secret-refresh", "secret-account", 2000)); fail() }
            catch (error: ApiFailure) { assertEquals(FailureKind.SERVER, error.kind) }
            val report = diagnostics.report()
            assertTrue(report.contains("HTTP_START 0"))
            assertTrue(report.contains("HTTP_END 0 503"))
            listOf("secret-access", "secret-refresh", "secret-account", "sensitive-response-body", "private-usage-path").forEach {
                assertFalse("Diagnostics leaked $it", report.contains(it))
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun rateLimitPreservesServerRetryTime() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
        try { api.usage(session); fail() } catch (error: ApiFailure) { assertEquals(FailureKind.LIMITED, error.kind); assertEquals(1120L, error.retryAt) }
    }
    @Test fun pendingDeviceAuthorizationDoesNotFail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(api.poll(DeviceChallenge("device", "code", 5, 2000)))
    }
    @Test fun challengeAcceptsStringIntervalAndLegacyCodeName() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"device_auth_id":"device","usercode":"code","interval":"5"}"""))
        val challenge = api.challenge()
        assertEquals(5L, challenge.intervalSeconds)
        assertEquals(1900L, challenge.expiresAt)
        assertEquals("code", challenge.userCode)
    }
    @Test fun renewalKeepsReplacementRefreshToken() = runBlocking {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"},"exp":3000}""".toByteArray())
        server.enqueue(MockResponse().setBody("""{"access_token":"header.$claims.signature","refresh_token":"replacement"}"""))
        val updated = api.renew(session)
        assertEquals("replacement", updated.refreshToken)
        assertEquals(3000L, updated.expiresAt)
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token"))
    }
    @Test fun serviceAndAuthFailuresAreDistinctAndRedacted() = runBlocking {
        for ((status, kind) in listOf(401 to FailureKind.AUTH, 403 to FailureKind.FORBIDDEN, 503 to FailureKind.SERVER)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive-account-data"))
            try { api.usage(session); fail() } catch (error: ApiFailure) { assertEquals(kind, error.kind); assertFalse(error.toString().contains("sensitive-account-data")) }
        }
    }
    @Test fun disconnectedTransportIsReportedAsNetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try { api.usage(session); fail() }
        catch (error: ApiFailure) { assertEquals(FailureKind.NETWORK, error.kind) }
    }
    @Test fun redirectCannotForwardCredentialsToAnotherEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
        try { api.usage(session); fail() }
        catch (error: ApiFailure) { assertEquals(FailureKind.SERVER, error.kind) }
        assertEquals(1, server.requestCount)
        assertEquals("/usage", server.takeRequest().path)
    }
}
