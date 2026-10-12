package com.easyesuite.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
