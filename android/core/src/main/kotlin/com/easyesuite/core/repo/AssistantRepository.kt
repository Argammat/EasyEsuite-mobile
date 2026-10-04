package com.easyesuite.core.repo

import com.easyesuite.core.Endpoints
import com.easyesuite.core.net.ApiClient
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The ERP Copilot (`ai/ai_copilot_agent_v2/`). The request/response payload is decoded leniently
 * because only the path has been verified — copy the exact body from the web Copilot when wiring.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CopilotRequest(
    val message: String,
    @SerialName("thread_id") val threadId: String? = null,
    /** Lets the backend tailor tool scoping / answer style for a phone screen. */
    @EncodeDefault val client: String = "mobile",
)

data class AssistantReply(
    val text: String,
    val threadId: String?,
    val suggestions: List<String>,
    val navigation: String?,
    val raw: JsonElement,
)

class AssistantRepository(private val client: ApiClient) {

    suspend fun ask(message: String, threadId: String?): AssistantReply {
        val raw: JsonElement = client.post(Endpoints.COPILOT, CopilotRequest(message.trim(), threadId))
        return parse(raw, threadId)
    }

    suspend fun featureSettings(): JsonElement = client.get(Endpoints.AI_FEATURE_SETTINGS)

    suspend fun credits(): JsonElement = client.get(Endpoints.AI_CREDITS)

    companion object {
        private val TEXT_KEYS = listOf("answer", "response", "message", "content", "output", "text", "reply")

        fun parse(raw: JsonElement, previousThread: String?): AssistantReply {
            val obj = raw as? JsonObject
            if (obj == null) {
                return AssistantReply((raw as? JsonPrimitive)?.contentOrNull ?: raw.toString(), previousThread, emptyList(), null, raw)
            }
            val text = TEXT_KEYS.firstNotNullOfOrNull { k ->
                when (val v = obj[k]) {
                    is JsonPrimitive -> v.contentOrNull
                    is JsonObject -> (v["text"] as? JsonPrimitive)?.contentOrNull ?: (v["content"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            } ?: (obj["data"] as? JsonObject)?.let { d -> TEXT_KEYS.firstNotNullOfOrNull { (d[it] as? JsonPrimitive)?.contentOrNull } }
                ?: "(no answer)"
            val thread = listOf("thread_id", "threadId", "session_id", "conversation_id")
                .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull } ?: previousThread
            val suggestions = (obj["suggestions"] as? JsonArray)?.mapNotNull { s ->
                (s as? JsonPrimitive)?.contentOrNull ?: ((s as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()
            val navigation = (obj["navigate_to"] as? JsonPrimitive)?.contentOrNull ?: (obj["route"] as? JsonPrimitive)?.contentOrNull
            return AssistantReply(text, thread, suggestions, navigation, raw)
        }
    }
}
