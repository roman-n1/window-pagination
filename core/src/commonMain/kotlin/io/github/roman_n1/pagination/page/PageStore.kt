package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.toImmutableList
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
        val duplicates = duplicateIds(page.items, itemId)
        if (duplicates.isNotEmpty()) return@withLock PageChange.Rejected(PageRejection.DuplicateItems(duplicates))
        val snapshot = PageSnapshot(PageToken(generation, page.id, 0), page, page.items)
        mutableState.value = current.copy(pages = (current.pages + snapshot).toImmutableList())
        PageChange.Applied(snapshot)
    }

    suspend fun editPage(token: PageToken, edit: PageEditor<T>.() -> Unit): PageChange<K, T> {
        val edits = PageEditor<T>().apply(edit).freeze()
        return mutatePage(token) { page ->
            if (edits.operations.isEmpty()) return@mutatePage PageChange.Applied(page)
            when (val result = applyPageEdits(page.items, edits, itemId)) {
                is EditResult.Rejected -> PageChange.Rejected(result.reason)
                is EditResult.Items -> PageChange.Applied(page.copy(
                    token = page.token.copy(revision = page.token.revision + 1),
                    items = result.items,
                    edits = PageEdits((page.edits.operations + edits.operations).toImmutableList()),
                ))
            }
        }
    }

    /** Deliberately replace the complete source page and discard its local edits. */
    suspend fun replacePage(token: PageToken, source: SourcePage<K, T>): PageChange<K, T> =
        updateSource(token, source, preserveEdits = false)

    /** Replay local edits on a fresh source; any conflict leaves the previous snapshot intact. */
    suspend fun reconcilePage(token: PageToken, source: SourcePage<K, T>): PageChange<K, T> =
        updateSource(token, source, preserveEdits = true)

    private suspend fun updateSource(
        token: PageToken,
        source: SourcePage<K, T>,
        preserveEdits: Boolean,
    ): PageChange<K, T> = mutatePage(token) { page ->
        if (source.id != token.pageId) {
            return@mutatePage PageChange.Rejected(PageRejection.PageIdentityChanged(token.pageId, source.id))
        }
        val duplicates = duplicateIds(source.items, itemId)
        if (duplicates.isNotEmpty()) return@mutatePage PageChange.Rejected(PageRejection.DuplicateItems(duplicates))
        val edits = if (preserveEdits) page.edits else PageEdits()
        when (val result = applyPageEdits(source.items, edits, itemId)) {
            is EditResult.Rejected -> PageChange.Rejected(PageRejection.EditsConflict(result.reason))
            is EditResult.Items -> PageChange.Applied(page.copy(
                token = page.token.copy(revision = page.token.revision + 1),
                source = source,
                items = result.items,
                edits = edits,
            ))
        }
    }

    private suspend fun mutatePage(
        token: PageToken,
        change: (PageSnapshot<K, T>) -> PageChange<K, T>,
    ): PageChange<K, T> = mutex.withLock {
        val current = mutableState.value
        if (token.generation != current.generation) {
            return@withLock PageChange.Rejected(PageRejection.GenerationChanged(token.generation, current.generation))
        }
        val index = current.pages.indexOfFirst { it.token.pageId == token.pageId }
        if (index < 0) return@withLock PageChange.Rejected(PageRejection.MissingPage(token.pageId))
        val page = current.pages[index]
        if (token != page.token) return@withLock PageChange.Rejected(PageRejection.RevisionChanged(token, page.token))
        val result = change(page)
        if (result is PageChange.Applied) {
            val pages = current.pages.toMutableList()
            pages[index] = result.page
            mutableState.value = current.copy(pages = pages.toImmutableList())
        }
        result
    }

    /** Clear the store and invalidate all pending loads, even when the store was already empty. */
    suspend fun reset(): PageGeneration = mutex.withLock {
        val generation = PageGeneration(mutableState.value.generation.value + 1)
        mutableState.value = PageStoreState(generation = generation)
        generation
    }
}
