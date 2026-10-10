package io.github.roman_n1.pagination.page

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Page ownership and source metadata shared by the window engine and page transactions. */
class PageStore<K : Any, T : Any>(private val itemId: (T) -> String) {
    private val mutableState = MutableStateFlow(PageStoreState<K, T>())
    val state: StateFlow<PageStoreState<K, T>> = mutableState.asStateFlow()

    fun page(id: PageId): PageLookup<K, T> = PageLookup.Missing

    suspend fun addPage(generation: PageGeneration, page: SourcePage<K, T>): PageChange<K, T> =
        PageChange.Rejected(PageRejection.GenerationChanged(generation, state.value.generation))

    suspend fun reset(): PageGeneration = state.value.generation
}
