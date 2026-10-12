package com.easyesuite.core.net

import com.easyesuite.core.FirebaseConfig
import com.easyesuite.core.auth.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One Firestore document with its typed values flattened to plain JSON. */
data class FirestoreDocument(
    /** Full resource name `projects/…/documents/tenants/42/…/abc`. */
    val name: String,
    val fields: JsonObject,
    val createTime: String? = null,
    val updateTime: String? = null,
) {
    val id: String get() = name.substringAfterLast('/')
    operator fun get(key: String): JsonElement? = fields[key]
}

/** Firestore's typed value encoding (`{"stringValue": "x"}`, `{"mapValue": {"fields": …}}` …) → plain JSON. */
object FirestoreValue {
    fun decode(value: JsonElement): JsonElement {
        val o = value as? JsonObject ?: return value
        if (o.size != 1) return value
        val (kind, raw) = o.entries.first()
        return when (kind) {
            "stringValue", "referenceValue", "timestampValue", "bytesValue", "doubleValue", "booleanValue", "geoPointValue" -> raw
            "integerValue" -> (raw as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: raw   // integers arrive as strings
            "nullValue" -> JsonNull
            "mapValue" -> fields((raw as? JsonObject)?.get("fields"))
            "arrayValue" -> JsonArray(((raw as? JsonObject)?.get("values") as? JsonArray).orEmpty().map(::decode))
            else -> value
        }
    }

    fun fields(value: JsonElement?): JsonObject {
        val o = value as? JsonObject ?: return JsonObject(emptyMap())
        return JsonObject(o.mapValues { decode(it.value) })
    }

    fun document(json: JsonElement): FirestoreDocument? {
        val o = json as? JsonObject ?: return null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        return FirestoreDocument(
            name = name,
            fields = fields(o["fields"]),
            createTime = (o["createTime"] as? JsonPrimitive)?.contentOrNull,
            updateTime = (o["updateTime"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}

/**
 * Read-only Firestore REST client for the screens the web app serves from Firestore (Copilot conversations,
 * connector status — "connector" database; in-app notifications — "notifications" database). Documents live
 * under `tenants/{session.tenantId}/…`; the exact paths come from the backend team per screen.
 * Authenticates with the Firebase ID token from the custom-token sign-in (refreshed by [AuthService]).
 */
class FirestoreClient(
    val config: FirebaseConfig,
    val database: String,
    private val auth: AuthService,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = DefaultJson,
) {
    /** `GET …/documents/{path}` — one document. */
    suspend fun document(path: String): FirestoreDocument {
        val el = get(url(path, emptyMap()))
        return FirestoreValue.document(el) ?: throw ApiException.Decoding(IllegalStateException("not a Firestore document"), null)
    }

    /** `GET …/documents/{collectionPath}?pageSize=&orderBy=&pageToken=` — a page of a collection. */
    suspend fun list(collectionPath: String, pageSize: Int = 50, orderBy: String? = null, pageToken: String? = null): Pair<List<FirestoreDocument>, String?> {
        val el = get(url(collectionPath, mapOf("pageSize" to pageSize.toString(), "orderBy" to orderBy, "pageToken" to pageToken)))
        val o = el as? JsonObject
        val docs = (o?.get("documents") as? JsonArray).orEmpty().mapNotNull(FirestoreValue::document)
        return docs to (o?.get("nextPageToken") as? JsonPrimitive)?.contentOrNull
    }

    private fun url(path: String, query: Map<String, String?>): String {
        val b = (config.firestoreRoot(database) + path.trim('/')).toHttpUrl().newBuilder()
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    private suspend fun get(url: String): JsonElement = withContext(Dispatchers.IO) {
        val token = auth.firebaseIdToken()
            ?: throw ApiException.Http(401, null, "Live data is unavailable for this workspace (no Firebase sign-in).")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json").get().build()
        val response = try { http.newCall(request).execute() } catch (e: IOException) { throw ApiException.Network(e) }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code !in 200..299) {
                val message = runCatching { ((json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull()
                throw ApiException.Http(r.code, text, message ?: "Firestore request failed (HTTP ${r.code}).")
            }
            runCatching { json.parseToJsonElement(text) }.getOrElse { throw ApiException.Decoding(it, text) }
        }
    }
}
