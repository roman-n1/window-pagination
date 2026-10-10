package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Page ownership and source metadata shared by the window engine and page transactions. */
class PageStore<K : Any, T : Any>(
    private val itemId: (T) -> String,
    private val cacheConfig: PageCacheConfig = PageCacheConfig(),
) {
    private val mutex = Mutex()
    private val accessOrder = mutableMapOf<PageId, Long>()
    private var accessClock = 0L
    private var nextInstance = 0L
    private val mutableState = MutableStateFlow(PageStoreState<K, T>())
    val state: StateFlow<PageStoreState<K, T>> = mutableState.asStateFlow()
    val windows: PageWindows<K, T> = PageWindows(read = { state.value }, mutate = ::mutateWindows,
        itemId = itemId, config = cacheConfig)

    fun page(id: PageId): PageLookup<K, T> {
        for (page in state.value.pages) {
            if (page.token.pageId == id) return PageLookup.Loaded(page)
        }
        return PageLookup.Missing
    }

    /** Explicitly mark a cached page as recently used. Missing lookups have no side effects. */
    suspend fun accessPage(id: PageId): PageLookup<K, T> = mutex.withLock {
        val lookup = page(id)
        if (lookup is PageLookup.Loaded) recordAccess(id)
        lookup
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
        val snapshot = PageSnapshot(PageToken(generation, page.id, 0, instance = nextInstance), page, page.items)
        val result = commitPage(current, snapshot)
        if (result is PageChange.Applied) nextInstance++
        result
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
        if (token.instance != page.token.instance) {
            return@withLock PageChange.Rejected(PageRejection.PageInstanceChanged(token, page.token))
        }
        if (token != page.token) return@withLock PageChange.Rejected(PageRejection.RevisionChanged(token, page.token))
        val result = change(page)
        if (result is PageChange.Applied) commitPage(current, result.page) else result
    }

    private fun commitPage(current: PageStoreState<K, T>, page: PageSnapshot<K, T>): PageChange<K, T> {
        val pages = current.pages.toMutableList()
        val index = pages.indexOfFirst { it.token.pageId == page.token.pageId }
        if (index < 0) pages.add(page) else pages[index] = page
        return when (val retention = retainPages(pages.toImmutableList(), page, cacheConfig, accessOrder, activePageIds(current))) {
            is RetentionResult.Rejected -> PageChange.Rejected(retention.reason)
            is RetentionResult.Retained -> {
                val reconciled = reconcileWindows(current, retention.pages, itemId)
                for (id in retention.evicted) accessOrder.remove(id)
                recordAccess(page.token.pageId)
                mutableState.value = current.copy(pages = retention.pages, windows = reconciled)
                PageChange.Applied(page, retention.evicted)
            }
        }
    }

    private suspend fun mutateWindows(transform: (PageStoreState<K, T>) -> WindowMutation<K, T>): WindowChange<K> = mutex.withLock {
        val mutation = transform(mutableState.value)
        val change = mutation.change
        if (change is WindowChange.Applied) {
            when (val content = change.window.content) {
                WindowContent.Unloaded -> Unit
                is WindowContent.Loaded -> for (id in content.pageIds) recordAccess(id)
            }
        }
        mutableState.value = mutation.state
        change
    }

    private fun recordAccess(id: PageId) {
        accessOrder[id] = accessClock++
    }

    /** Clear the store and invalidate all pending loads, even when the store was already empty. */
    suspend fun reset(): PageGeneration = mutex.withLock {
        val generation = PageGeneration(mutableState.value.generation.value + 1)
        accessOrder.clear()
        windows.clearRecency()
        mutableState.value = PageStoreState(generation = generation)
        generation
    }
}
