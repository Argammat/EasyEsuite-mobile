package com.easyesuite.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class UserGroup(val id: Int? = null, val name: String = "")

/** `users/users/me/` */
@Serializable
data class UserProfile(
    val id: Long? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val email: String = "",
    val image: String? = null,
    @SerialName("is_superuser") val isSuperuser: Boolean = false,
    @SerialName("is_staff") val isStaff: Boolean = false,
    val groups: List<UserGroup> = emptyList(),
    @SerialName("finished_onboarding") val finishedOnboarding: Boolean? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").trim().ifBlank { email }
    val isAdmin: Boolean get() = isSuperuser || groups.any { it.name.equals("Admin", ignoreCase = true) }
}

/** One workspace from `users/me/tenants/`. The shape is unconfirmed, so it is parsed leniently. */
data class TenantInfo(val slug: String, val name: String) {
    companion object {
        fun listFrom(element: JsonElement?): List<TenantInfo> {
            val arr: JsonArray = when (element) {
                is JsonArray -> element
                is JsonObject -> element["results"] as? JsonArray ?: element["tenants"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull (e as? JsonPrimitive)?.contentOrNull?.let { TenantInfo(it, it) }
                val slug = listOf("slug", "schema_name", "tenant", "tenant_id", "company_name", "name")
                    .firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
                val name = listOf("display_name", "company_display_name", "name", "company_name")
                    .firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull } ?: slug
                TenantInfo(slug, name)
            }
        }
    }
}
