package com.tsonglew.quodex.data

import com.tsonglew.quodex.diagnostics.Diagnostics
import com.tsonglew.quodex.network.RequestRetry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ApiCreditsApiTest {
    @Test fun readOnlyBenefitRequestsKeepCredentialsInHeadersAndStayOutOfAuthDiagnostics() = runBlocking {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("api-benefit-test").toFile()
        server.start()
        try {
            val diagnostics = Diagnostics(File(directory, "events.log"))
            val api = CodexApi(apiCreditsBaseUrl = server.url("/accounts").toString(), diagnostics = diagnostics,
                requestRetry = RequestRetry(maxAttempts = 1))
            val session = Session("secret-access", "secret-refresh", "private-account", Long.MAX_VALUE)
            server.enqueue(MockResponse().setBody("""{"eligible":false,"ineligibility_reason":"not_qualified","offer":null}"""))
            assertFalse(api.apiCredits(session).eligible)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/accounts/private-account/benefits/chatgpt_api_credits_promo?destination_type=api_org", request.path)
            assertEquals(0L, request.bodySize)
            assertEquals("Bearer secret-access", request.getHeader("Authorization"))
            assertEquals("private-account", request.getHeader("ChatGPT-Account-Id"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("private-error"))
            try { api.apiCredits(session); fail() } catch (failure: ApiFailure) { assertEquals(FailureKind.FORBIDDEN, failure.kind) }
            val report = diagnostics.report()
            assertTrue(report.contains("HTTP_START 4"))
            assertTrue(report.contains("HTTP_END 4 403"))
            assertTrue(report.contains("usageRequests=0 authRequests=0 httpNon2xx=0"))
            listOf("secret-access", "secret-refresh", "private-account", "private-error", "not_qualified").forEach {
                assertFalse(report.contains(it))
            }
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
}
