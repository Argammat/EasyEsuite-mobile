package com.easyesuite.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PageQuery
import com.easyesuite.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot load of a single record. */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun Throwable.userMessage(): String = when (this) {
    is ApiException.Unauthorized -> "Your session expired. Please sign in again."
    is ApiException.Http -> detail
    is ApiException.Network -> "Can't reach EasyEsuite. Check your connection."
    is ApiException.Decoding -> "Unexpected response from the server."
    is CancellationException -> throw this
    else -> message ?: "Something went wrong."
}

/** Infinite-scroll list state shared by every paged screen. */
data class ListState<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,       // first page / refresh
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty() && !loading && error == null
}

/**
 * Base for list screens: subclasses implement [fetch] and call [refresh] whenever a filter changes.
 * Cancels in-flight loads on refresh so stale pages never land on top of new filters.
 */
abstract class PagedListViewModel<T>(private val pageSize: Int = 25) : ViewModel() {
    private val _state = MutableStateFlow(ListState<T>())
    val state: StateFlow<ListState<T>> = _state

    private var job: Job? = null
    private var nextOffset = 0

    protected abstract suspend fun fetch(page: PageQuery): Page<T>

    /** Open so a screen can refresh companion data (totals, counts) alongside the list. */
    open fun refresh() { load(reset = true) }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        job?.cancel()
        if (reset) nextOffset = 0
        _state.update { if (reset) it.copy(loading = true, error = null) else it.copy(loadingMore = true, error = null) }
        job = viewModelScope.launch {
            try {
                val page = fetch(PageQuery(limit = pageSize, offset = nextOffset))
                nextOffset += page.results.size
                _state.update {
                    it.copy(
                        items = if (reset) page.results else it.items + page.results,
                        total = page.count,
                        loading = false,
                        loadingMore = false,
                        hasMore = page.hasMore && page.results.isNotEmpty(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadingMore = false, error = e.userMessage()) }
            }
        }
    }

    /** Replace one row in place after an edit (e.g. after fulfilling an order). */
    protected fun replace(predicate: (T) -> Boolean, transform: (T) -> T) {
        _state.update { s -> s.copy(items = s.items.map { if (predicate(it)) transform(it) else it }) }
    }
}
