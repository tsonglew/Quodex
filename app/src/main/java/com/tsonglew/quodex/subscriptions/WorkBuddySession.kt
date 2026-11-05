package com.tsonglew.quodex.subscriptions

import com.tsonglew.quodex.data.AppJson
import kotlinx.serialization.json.*

/** WorkBuddy binds its split login cookie to the originating browser user agent. */
class WorkBuddySession(val session: String, val chunk: String, val userAgent: String) {
    fun encode(): String = buildJsonObject {
        put("session", session); put("session_2", chunk); put("userAgent", userAgent)
    }.toString()

    override fun toString() = "WorkBuddySession(credentials=redacted)"

    companion object {
        fun parse(raw: String): WorkBuddySession? = runCatching {
            require(raw.length <= 18_000)
            val root = AppJson.parseToJsonElement(raw).jsonObject
            require(root.keys == setOf("session", "session_2", "userAgent"))
            fun value(key: String) = root.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val session = value("session")
            val chunk = value("session_2")
            val agent = value("userAgent")
            require(SubscriptionApi.validCredential(session) && SubscriptionApi.validCredential(chunk))
            require(agent.length in 1..512 && agent.all { it.code in 32..126 })
            WorkBuddySession(session, chunk, agent)
        }.getOrNull()
    }
}
