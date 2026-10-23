package com.tsonglew.quodex.data

import com.tsonglew.quodex.diagnostics.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DeviceChallenge(val deviceAuthId: String, val userCode: String, val intervalSeconds: Long, val expiresAt: Long)
data class DeviceGrant(val code: String, val verifier: String)

/** Experimental adapter for the protocol used by the open-source Codex client. */
class CodexApi(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
    private val authBase: String = "https://auth.openai.com",
    private val usageUrl: String = "https://chatgpt.com/backend-api/wham/usage",
    private val now: () -> Long = { Instant.now().epochSecond },
    private val diagnostics: Diagnostics? = null,
    private val tokenUsageUrl: String = "https://chatgpt.com/backend-api/wham/profiles/me",
) {
    companion object {
        // Public client identifier from openai/codex; not a client secret.
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val VERIFICATION_URL = "https://auth.openai.com/codex/device"
    }

    private data class Reply(val status: Int, val body: String, val retryAfter: String?)
    private suspend fun send(request: Request): Reply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val operation = when (request.url.toString()) {
                usageUrl -> 0L
                tokenUsageUrl -> 2L
                else -> 1L
            }
            val started = System.nanoTime()
            diagnostics?.record(DiagnosticEvent.HTTP_START, operation)
            val call = client.newCall(request.newBuilder().header("User-Agent", "Quodex/0.1 Android")
                .header("Accept", "application/json").build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    diagnostics?.record(DiagnosticEvent.HTTP_NETWORK_FAILURE, operation, if (call.isCanceled()) 1 else 0)
                    if (cont.isActive) cont.resumeWithException(ApiFailure(FailureKind.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    diagnostics?.record(DiagnosticEvent.HTTP_END, operation, response.code.toLong(), (System.nanoTime() - started) / 1_000_000)
                    response.use {
                        try {
                            val body = it.body ?: throw ApiFailure(FailureKind.PROTOCOL)
                            val source = body.source()
                            source.request(512 * 1024 + 1L)
                            if (source.buffer.size > 512 * 1024) throw ApiFailure(FailureKind.PROTOCOL)
                            val reply = Reply(it.code, source.readUtf8(), it.header("Retry-After"))
                            if (cont.isActive) cont.resume(reply)
                        } catch (_: IOException) {
                            diagnostics?.record(DiagnosticEvent.HTTP_NETWORK_FAILURE, operation, if (call.isCanceled()) 1 else 0)
                            if (cont.isActive) cont.resumeWithException(ApiFailure(FailureKind.NETWORK))
                        } catch (_: Exception) {
                            if (cont.isActive) cont.resumeWithException(ApiFailure(FailureKind.PROTOCOL))
                        }
                    }
                }
            })
        }
    }

    private fun check(reply: Reply, tokenEndpoint: Boolean = false): String {
        if (reply.status in 200..299) return reply.body
        val kind = when (reply.status) {
            400 -> if (tokenEndpoint) FailureKind.AUTH else FailureKind.PROTOCOL
            401 -> FailureKind.AUTH
            403 -> FailureKind.FORBIDDEN
            429 -> FailureKind.LIMITED
            else -> FailureKind.SERVER
        }
        val retry = reply.retryAfter?.toLongOrNull()?.let { now() + it.coerceAtLeast(0) }
            ?: runCatching { ZonedDateTime.parse(reply.retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
        throw ApiFailure(kind, if (kind == FailureKind.LIMITED) retry ?: now() + 60 else null)
    }

    private fun obj(raw: String): JsonObject = try { AppJson.parseToJsonElement(raw).jsonObject }
        catch (_: Exception) { throw ApiFailure(FailureKind.PROTOCOL) }

    private fun required(root: JsonObject, key: String) = root.str(key)?.takeIf { it.isNotBlank() }
        ?: throw ApiFailure(FailureKind.PROTOCOL)

    private fun jsonPost(url: String, data: JsonObject) = Request.Builder().url(url)
        .post(data.toString().toRequestBody("application/json".toMediaType())).build()

    suspend fun challenge(): DeviceChallenge {
        val root = obj(check(send(jsonPost("$authBase/api/accounts/deviceauth/usercode", buildJsonObject { put("client_id", CLIENT_ID) }))))
        return DeviceChallenge(required(root, "device_auth_id"), root.str("user_code") ?: required(root, "usercode"),
            root.str("interval")?.toLongOrNull()?.coerceIn(1, 60) ?: 5, now() + 900)
    }

    suspend fun poll(challenge: DeviceChallenge): DeviceGrant? {
        val reply = send(jsonPost("$authBase/api/accounts/deviceauth/token", buildJsonObject {
            put("device_auth_id", challenge.deviceAuthId); put("user_code", challenge.userCode)
        }))
        if (reply.status == 403 || reply.status == 404) return null
        val root = obj(check(reply))
        return DeviceGrant(required(root, "authorization_code"), required(root, "code_verifier"))
    }

    suspend fun exchange(grant: DeviceGrant): Session {
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CLIENT_ID)
            .add("code", grant.code).add("code_verifier", grant.verifier)
            .add("redirect_uri", "$authBase/deviceauth/callback").build()
        val root = obj(check(send(Request.Builder().url("$authBase/oauth/token").post(form).build()), true))
        return sessionFrom(root)
    }

    suspend fun renew(session: Session): Session {
        val root = obj(check(send(jsonPost("$authBase/oauth/token", buildJsonObject {
            put("grant_type", "refresh_token"); put("client_id", session.clientId); put("refresh_token", session.refreshToken)
        })), true))
        val result = sessionFrom(root, session)
        if (result.accountId != session.accountId) throw ApiFailure(FailureKind.AUTH)
        return result
    }

    private fun sessionFrom(root: JsonObject, previous: Session? = null): Session {
        val access = required(root, "access_token")
        // Claims are only routing/expiry hints. The usage service validates the token and account.
        val claims = jwtClaims(access)
        val account = claims.obj("https://api.openai.com/auth")?.str("chatgpt_account_id")
            ?: previous?.accountId ?: throw ApiFailure(FailureKind.PROTOCOL)
        return Session(access, root.str("refresh_token") ?: previous?.refreshToken ?: throw ApiFailure(FailureKind.PROTOCOL),
            account, root.long("expires_in")?.let { now() + it } ?: claims.long("exp") ?: now() + 3600,
            previous?.clientId ?: CLIENT_ID)
    }

    suspend fun usage(session: Session): UsageSnapshot {
        val reply = send(Request.Builder().url(usageUrl).header("Authorization", "Bearer ${session.accessToken}")
            .header("ChatGPT-Account-Id", session.accountId).get().build())
        return UsageParser.parse(check(reply), session.accountId, now())
    }

    suspend fun tokenUsage(session: Session): TokenUsageSnapshot = kotlinx.coroutines.withTimeoutOrNull(10_000) {
        val reply = send(Request.Builder().url(tokenUsageUrl).header("Authorization", "Bearer ${session.accessToken}")
            .header("ChatGPT-Account-Id", session.accountId).get().build())
        TokenUsageParser.parse(check(reply), now())
    } ?: throw ApiFailure(FailureKind.NETWORK)

    private fun jwtClaims(token: String): JsonObject = try {
        val part = token.split('.')[1]
        obj(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8))
    } catch (_: Exception) { throw ApiFailure(FailureKind.PROTOCOL) }
}
