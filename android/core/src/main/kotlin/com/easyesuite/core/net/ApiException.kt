package com.easyesuite.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Non-2xx response. `detail` is the human-readable message extracted from the DRF body. */
    class Http(val status: Int, val body: String?, val detail: String) : ApiException("HTTP $status: $detail") {
        val isNotFound get() = status == 404
        val isValidation get() = status == 400
        val isForbidden get() = status == 403
    }

    /** The session could not be refreshed; the user has to log in again. */
    class Unauthorized(detail: String = "Session expired, please sign in again.") : ApiException(detail)

    class Network(cause: Throwable) : ApiException(cause.message ?: "Network error", cause)

    class Decoding(cause: Throwable, val body: String?) : ApiException("Could not read the server response: ${cause.message}", cause)

    companion object {
        /** Pulls a readable message out of DRF error bodies: {"detail": ".."} or {"field": ["msg"]}. */
        fun describe(status: Int, body: String?, json: Json): String {
            if (body.isNullOrBlank()) return defaultMessage(status)
            val el = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return body.take(200)
            return when (el) {
                is JsonObject -> {
                    (el["detail"] as? JsonPrimitive)?.contentOrNull
                        ?: (el["error"] as? JsonPrimitive)?.contentOrNull
                        ?: (el["message"] as? JsonPrimitive)?.contentOrNull
                        ?: el.entries.joinToString("; ") { (k, v) ->
                            val msg = when (v) {
                                is JsonPrimitive -> v.contentOrNull ?: v.toString()
                                is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                                else -> v.toString()
                            }
                            if (k == "non_field_errors") msg else "$k: $msg"
                        }.ifBlank { defaultMessage(status) }
                }
                is JsonArray -> el.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                is JsonPrimitive -> el.contentOrNull ?: defaultMessage(status)
            }
        }

        fun defaultMessage(status: Int) = when (status) {
            400 -> "The server rejected the request."
            401 -> "Not signed in."
            403 -> "You don't have permission to do that."
            404 -> "Not found."
            429 -> "Too many requests, slow down."
            in 500..599 -> "Server error ($status)."
            else -> "Request failed ($status)."
        }
    }
}
