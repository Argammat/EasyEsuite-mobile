package com.easyesuite.core.model

import kotlinx.serialization.Serializable

/** Django REST Framework limit/offset page. */
@Serializable
data class Page<T>(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<T> = emptyList(),
) {
    val hasMore: Boolean get() = next != null

    companion object {
        fun <T> empty(): Page<T> = Page()
    }
}

/** Query that every paged list accepts. */
data class PageQuery(val limit: Int = 25, val offset: Int = 0) {
    fun next() = copy(offset = offset + limit)
    fun asMap(): Map<String, Any?> = mapOf("limit" to limit, "offset" to offset)
}
