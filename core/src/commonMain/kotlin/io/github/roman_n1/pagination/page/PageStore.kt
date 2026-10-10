package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Page ownership and source metadata shared by the window engine and page transactions. */
class PageStore<K : Any, T : Any>(private val itemId: (T) -> String) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PageStoreState<K, T>())
    val state: StateFlow<PageStoreState<K, T>> = mutableState.asStateFlow()

    fun page(id: PageId): PageLookup<K, T> {
        for (page in state.value.pages) {
            if (page.token.pageId == id) return PageLookup.Loaded(page)
        }
        return PageLookup.Missing
    }

    /** Capture generation before loading; a reset makes that response ineligible for registration. */
    suspend fun addPage(generation: PageGeneration, page: SourcePage<K, T>): PageChange<K, T> = mutex.withLock {
        val current = mutableState.value
        if (generation != current.generation) {
            return@withLock PageChange.Rejected(PageRejection.GenerationChanged(generation, current.generation))
        }
        if (current.pages.any { it.token.pageId == page.id }) {
            return@withLock PageChange.Rejected(PageRejection.PageAlreadyLoaded(page.id))
        }
        val duplicates = page.items.groupBy(itemId).filterValues { it.size > 1 }.keys.toImmutableSet()
        if (duplicates.isNotEmpty()) return@withLock PageChange.Rejected(PageRejection.DuplicateItems(duplicates))
        val snapshot = PageSnapshot(PageToken(generation, page.id, 0), page, page.items)
        mutableState.value = current.copy(pages = (current.pages + snapshot).toImmutableList())
        PageChange.Applied(snapshot)
    }

    suspend fun editPage(token: PageToken, edit: PageEditor<T>.() -> Unit): PageChange<K, T> {
        PageEditor<T>().apply(edit).freeze()
        return PageChange.Rejected(PageRejection.MissingPage(token.pageId))
    }

    suspend fun replacePage(token: PageToken, source: SourcePage<K, T>): PageChange<K, T> =
        PageChange.Rejected(PageRejection.PageIdentityChanged(token.pageId, source.id))

    suspend fun reconcilePage(token: PageToken, source: SourcePage<K, T>): PageChange<K, T> =
        PageChange.Rejected(PageRejection.PageIdentityChanged(token.pageId, source.id))

    /** Clear the store and invalidate all pending loads, even when the store was already empty. */
    suspend fun reset(): PageGeneration = mutex.withLock {
        val generation = PageGeneration(mutableState.value.generation.value + 1)
        mutableState.value = PageStoreState(generation = generation)
        generation
    }
}
