package com.easyesuite.core.auth

import com.easyesuite.core.ApiConfig
import com.easyesuite.core.Endpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A workspace as the global directory describes it. */
data class TenantLookup(val slug: String, val displayName: String?, val firebaseTenantId: String?)

/**
 * Resolves the company name typed at login to its workspace and Identity Platform tenant id.
 * The web app stores the result as `tenantId` / `firebaseTenantId` / `companyDisplayName`;
 * the endpoint that produces it is ASSUMED (`{global root}clients/clients/?name=`) — see docs/API_MAP.md.
 * Any failure returns null so the login screen can fall back to a manual tenant id.
 */
class TenantDirectory(
    private val apiRoot: String = ApiConfig.DEFAULT_API_ROOT,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun lookup(companyName: String): TenantLookup? = withContext(Dispatchers.IO) {
        val slug = companyName.trim().lowercase()
        if (slug.isEmpty()) return@withContext null
        val url = (apiRoot.trimEnd('/') + "/api/v1/" + Endpoints.TENANT_LOOKUP).toHttpUrl().newBuilder()
            .addQueryParameter("name", slug).addQueryParameter("search", slug).build()
        val body = runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (r.code in 200..299) r.body?.string() else null
            }
        }.getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return@withContext null
        val candidates: List<JsonObject> = when (root) {
            is JsonArray -> root.filterIsInstance<JsonObject>()
            is JsonObject -> (root["results"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(root)
            else -> emptyList()
        }
        val match = candidates.firstOrNull { o ->
            listOf("slug", "schema_name", "name", "company_name", "tenant_id").any { k -> str(o[k])?.lowercase() == slug }
        } ?: candidates.singleOrNull() ?: return@withContext null
        TenantLookup(
            slug = listOf("slug", "schema_name", "tenant_id", "name").firstNotNullOfOrNull { str(match[it]) } ?: slug,
            displayName = listOf("display_name", "company_display_name", "company_name", "name").firstNotNullOfOrNull { str(match[it]) },
            firebaseTenantId = listOf("firebase_tenant_id", "firebaseTenantId", "identity_tenant_id").firstNotNullOfOrNull { str(match[it]) },
        )
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
