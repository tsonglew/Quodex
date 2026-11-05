package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.*
import com.tsonglew.quodex.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface SubscriptionSource {
    suspend fun fetch(provider: SubscriptionProvider, credential: String): SubscriptionSnapshot
}

class SubscriptionApi(
    client: OkHttpClient = OkHttpClient(),
    private val endpoints: Map<SubscriptionProvider, String> = mapOf(
        SubscriptionProvider.DEEPSEEK to "https://api.deepseek.com/user/balance",
        SubscriptionProvider.OPENCODE_GO to "https://opencode.ai/zen/go/v1/usage",
        SubscriptionProvider.OPENCODE_ZEN to "https://opencode.ai/console/api/billing/status",
        SubscriptionProvider.CURSOR to "https://cursor.com/api/usage-summary",
        SubscriptionProvider.KIMI to "https://www.kimi.com/apiv2/kimi.gateway.membership.v2.MembershipService/GetSubscriptionStats",
        SubscriptionProvider.WORKBUDDY to "https://www.workbuddy.ai/billing/meter/get-user-resource",
        SubscriptionProvider.GROK to "https://grok.com/dashboard/api/dashboard/",
        SubscriptionProvider.KIMI_CODE to "https://api.kimi.com/coding/v1/usages",
        SubscriptionProvider.KIMI_API to "https://api.moonshot.cn/v1/users/me/balance",
        SubscriptionProvider.KIMI_API_GLOBAL to "https://api.moonshot.ai/v1/users/me/balance",
        SubscriptionProvider.GROK_API to "https://management-api.x.ai/",
        SubscriptionProvider.CURSOR_BILLING to "https://api.cursor.com/teams/spend",
    ),
    private val now: () -> Long = { Instant.now().epochSecond },
    private val retry: RequestRetry = RequestRetry(now),
) : SubscriptionSource {
    // A redirect must never carry an API key or session cookie to a second host.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()

    private val grokWalletRetryAt = AtomicLong()

    override suspend fun fetch(provider: SubscriptionProvider, credential: String): SubscriptionSnapshot {
        require(validCredential(provider, credential))
        return try {
        val raw = when (provider) {
            SubscriptionProvider.GROK_API -> grokBalance(credential)
            SubscriptionProvider.GROK -> grokUsage(credential)
            SubscriptionProvider.WORKBUDDY -> workbuddyResources(credential)
            SubscriptionProvider.KIMI -> read(request(provider, credential).header("connect-protocol-version", "1")
                .post("{}".toRequestBody("application/json".toMediaType())).build())
            SubscriptionProvider.CURSOR_BILLING -> cursorSpend(credential)
            else -> read(request(provider, credential).build())
        }
        SubscriptionParser.parse(provider, raw, now())
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: ApiFailure) { throw failure }
        catch (_: Exception) { throw ApiFailure(FailureKind.PROTOCOL) }
    }

    private fun request(provider: SubscriptionProvider, credential: String): Request.Builder =
        Request.Builder().url(endpoints.getValue(provider))
            .header("Accept", "application/json").header("User-Agent", "Quodex/0.1 Android")
            .apply {
                when (provider) {
                    SubscriptionProvider.WORKBUDDY -> {
                        val session = requireNotNull(WorkBuddySession.parse(credential))
                        header("Cookie", "session=${session.session}; session_2=${session.chunk}")
                        header("User-Agent", session.userAgent)
                        header("X-Client-Platform", "web")
                        header("Origin", "https://www.workbuddy.ai")
                        header("Referer", "https://www.workbuddy.ai/profile/plans-usage")
                    }
                    SubscriptionProvider.GROK -> header("Cookie", "WorkosCursorSessionToken=$credential")
                    SubscriptionProvider.CURSOR -> {
                        header("Cookie", "WorkosCursorSessionToken=$credential")
                        header("Origin", "https://cursor.com")
                    }
                    SubscriptionProvider.CURSOR_BILLING -> header("Authorization", Credentials.basic(credential, ""))
                    else -> header("Authorization", "Bearer $credential")
                }
            }

    private suspend fun read(request: Request): String = retry.run(classify = { error ->
        (error as? ApiFailure)?.takeIf { it.retryable }?.let { RetryDirective(it.retryAt) }
    }) { send(request) }

    private fun objectFrom(raw: String): JsonObject = try {
        AppJson.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) { throw ApiFailure(FailureKind.PROTOCOL) }

    private suspend fun grokBalance(credential: String): String {
        val provider = SubscriptionProvider.GROK_API
        val base = endpoints.getValue(provider).toHttpUrl()
        fun url(vararg path: String) = base.newBuilder().apply { path.forEach(::addPathSegment) }.build()
        val identity = objectFrom(read(request(provider, credential)
            .url(url("auth", "management-keys", "validation")).build()))
        val scope = identity.str("scope")
        // Organization keys cannot silently select an arbitrary team.
        if (scope != null && scope != "SCOPE_TEAM") throw ApiFailure(FailureKind.FORBIDDEN)
        val team = identity.str("scopeId") ?: identity.str("teamId")
        if (team == null || !team.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw ApiFailure(FailureKind.PROTOCOL)
        return read(request(provider, credential).url(url("v1", "billing", "teams", team, "prepaid", "balance")).build())
    }

    private suspend fun grokUsage(credential: String): String {
        val provider = SubscriptionProvider.GROK
        val base = endpoints.getValue(provider).toHttpUrl()
        val usageUrl = base.newBuilder().addPathSegment("v2").addPathSegment("usage-status").addQueryParameter("scope", "user").build()
        val usage = objectFrom(read(request(provider, credential).url(usageUrl).build()))
        val quotaOnly = usage.toString()
        // Validate the primary response before attempting a supplementary wallet read.
        SubscriptionParser.parse(provider, quotaOnly, now())
        if (now() < grokWalletRetryAt.get()) return quotaOnly
        return try {
            val balanceUrl = base.newBuilder().addPathSegment("get-complat-credit-balance").build()
            val balance = objectFrom(read(request(provider, credential).url(balanceUrl)
                .post("{}".toRequestBody("application/json".toMediaType())).build()))
            val combined = JsonObject(usage + ("accountBalance" to balance)).toString()
            // Invalid wallet fields are also optional; preserve valid quota and spending.
            SubscriptionParser.parse(provider, combined, now())
            combined
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: ApiFailure) {
            failure.retryAt?.let { deadline -> grokWalletRetryAt.updateAndGet { maxOf(it, deadline) } }
            quotaOnly
        }
    }

    private suspend fun workbuddyResources(credential: String): String {
        val rows = mutableListOf<JsonElement>()
        val ids = mutableSetOf<String>()
        var total: Int? = null
        var page = 1
        do {
            val payload = buildJsonObject {
                put("PageNumber", page); put("PageSize", 200); put("ProductCode", "p_tcaca")
                put("Status", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(3))))
                put("OnlyValidPeriod", true)
            }
            val root = objectFrom(read(request(SubscriptionProvider.WORKBUDDY, credential)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()))
            if (root["code"]?.jsonPrimitive?.intOrNull != 0) throw ApiFailure(FailureKind.PROTOCOL)
            val data = root.obj("data")?.obj("Response")?.obj("Data") ?: throw ApiFailure(FailureKind.PROTOCOL)
            val count = data["TotalCount"]?.jsonPrimitive?.intOrNull ?: throw ApiFailure(FailureKind.PROTOCOL)
            val accounts = data["Accounts"] as? JsonArray ?: throw ApiFailure(FailureKind.PROTOCOL)
            if (count !in 0..4000 || (total != null && count != total) || accounts.size > 200 ||
                (accounts.isEmpty() && rows.size < count)) throw ApiFailure(FailureKind.PROTOCOL)
            total = count
            accounts.forEach { element ->
                val row = element.jsonObject
                val id = row.str("AccountId") ?: throw ApiFailure(FailureKind.PROTOCOL)
                if (id.length !in 1..200 || !ids.add(id)) throw ApiFailure(FailureKind.PROTOCOL)
                // Keep only fields needed to describe the current quota package.
                rows += JsonObject(row.filterKeys { it in setOf("PackageName", "CapacityUnit", "CycleCapacityRemain",
                    "CycleCapacityRemainPrecise", "CycleCapacitySize", "CycleCapacitySizePrecise", "CycleEndTime") })
            }
            page++
        } while (rows.size < total!! && page <= 20)
        if (rows.size != total) throw ApiFailure(FailureKind.PROTOCOL)
        return buildJsonObject {
            put("code", 0)
            put("data", buildJsonObject { put("Response", buildJsonObject { put("Data", buildJsonObject {
                put("TotalCount", rows.size); put("Accounts", JsonArray(rows))
            }) }) })
        }.toString()
    }

    private suspend fun cursorSpend(credential: String): String {
        val rows = mutableListOf<JsonElement>()
        val ids = mutableSetOf<String>()
        var pages = 1
        var members: Int? = null
        var cycle: String? = null
        var page = 1
        do {
            val body = buildJsonObject { put("page", page); put("pageSize", 500) }.toString()
            val root = objectFrom(read(request(SubscriptionProvider.CURSOR_BILLING, credential)
                .post(body.toRequestBody("application/json".toMediaType())).build()))
            val totalPages = root["totalPages"]?.jsonPrimitive?.intOrNull
            val totalMembers = root["totalMembers"]?.jsonPrimitive?.intOrNull
            val currentCycle = root["subscriptionCycleStart"]?.jsonPrimitive?.contentOrNull
            val current = root["teamMemberSpend"] as? JsonArray
            if (totalPages == null || totalPages !in 0..20 || totalMembers == null || totalMembers !in 0..10_000 ||
                currentCycle == null || current == null || current.size > 500 ||
                (page > 1 && (totalPages != pages || totalMembers != members || currentCycle != cycle))) {
                throw ApiFailure(FailureKind.PROTOCOL)
            }
            pages = totalPages
            members = totalMembers
            cycle = currentCycle
            current.forEach { element ->
                val row = element as? JsonObject ?: throw ApiFailure(FailureKind.PROTOCOL)
                val id = row.str("userId") ?: throw ApiFailure(FailureKind.PROTOCOL)
                if (id.length !in 1..200 || !ids.add(id)) throw ApiFailure(FailureKind.PROTOCOL)
                // Names, email addresses and identifiers are unnecessary for aggregate display.
                rows += buildJsonObject {
                    row["spendCents"]?.let { put("spendCents", it) }
                    row["overallSpendCents"]?.let { put("overallSpendCents", it) }
                }
            }
            page++
        } while (page <= pages)
        if (rows.size != members) throw ApiFailure(FailureKind.PROTOCOL)
        return buildJsonObject { put("teamMemberSpend", JsonArray(rows)) }.toString()
    }

    private suspend fun send(request: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        ApiFailure(FailureKind.NETWORK, retryable = isTransientTransport(e)))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val kind = when (it.code) {
                                    401 -> FailureKind.AUTH
                                    403 -> FailureKind.FORBIDDEN
                                    408 -> FailureKind.NETWORK
                                    429 -> FailureKind.LIMITED
                                    in 500..599 -> FailureKind.SERVER
                                    else -> FailureKind.PROTOCOL
                                }
                                throw ApiFailure(kind, retryAfterAt(it.header("Retry-After"), now())
                                    ?: if (it.code == 429) now() + 60 else null,
                                    it.code in setOf(408, 429, 500, 502, 503, 504))
                            }
                            val source = it.body?.source() ?: throw ApiFailure(FailureKind.PROTOCOL)
                            source.request(256 * 1024 + 1L)
                            if (source.buffer.size > 256 * 1024) throw ApiFailure(FailureKind.PROTOCOL)
                            val raw = source.readUtf8()
                            if (continuation.isActive) continuation.resume(raw)
                        } catch (failure: Exception) {
                            val safe = when (failure) {
                                is ApiFailure -> failure
                                is IOException -> ApiFailure(FailureKind.NETWORK, retryable = isTransientTransport(failure))
                                else -> ApiFailure(FailureKind.PROTOCOL)
                            }
                            if (continuation.isActive) continuation.resumeWithException(safe)
                        }
                    }
                }
            })
        }
    }

    companion object {
        fun validCredential(provider: SubscriptionProvider, value: String): Boolean =
            if (provider == SubscriptionProvider.WORKBUDDY) WorkBuddySession.parse(value) != null else validCredential(value)

        fun validCredential(value: String): Boolean = value.length in 1..8192 &&
            value.all { it.code in 33..126 && it !in ";,\"\\" }
    }
}
