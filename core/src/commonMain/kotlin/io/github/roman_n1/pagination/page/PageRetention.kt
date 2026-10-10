package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal sealed interface RetentionResult<out K : Any, out T : Any> {
    data class Retained<K : Any, T : Any>(
        val pages: ImmutableList<PageSnapshot<K, T>>,
        val evicted: ImmutableList<PageId>,
    ) : RetentionResult<K, T>
    data class Rejected(val reason: PageRejection) : RetentionResult<Nothing, Nothing>
}

/** Work on private copies: failed admission must not evict pages before it rejects. */
internal fun <K : Any, T : Any> retainPages(
    pages: ImmutableList<PageSnapshot<K, T>>,
    incoming: PageSnapshot<K, T>,
    config: PageCacheConfig,
    accessOrder: Map<PageId, Long>,
): RetentionResult<K, T> {
    fun capacity(values: List<PageSnapshot<K, T>>): PageRejection.CacheCapacityExceeded =
        PageRejection.CacheCapacityExceeded(
            values.size, values.sumOf { it.source.items.size.toLong() }, values.sumOf { it.items.size.toLong() },
            config.maxPages, config.maxItems,
        )
    fun fits(values: List<PageSnapshot<K, T>>): Boolean =
        values.size <= config.maxPages &&
            values.sumOf { it.source.items.size.toLong() } <= config.maxItems &&
            values.sumOf { it.items.size.toLong() } <= config.maxItems

    if (!fits(listOf(incoming))) return RetentionResult.Rejected(capacity(listOf(incoming)))
    val journalUnits = editUnits(incoming.edits)
    if (journalUnits > config.maxEditUnitsPerPage) {
        return RetentionResult.Rejected(PageRejection.EditCapacityExceeded(journalUnits, config.maxEditUnitsPerPage))
    }
    val retained = pages.toMutableList()
    val evicted = mutableListOf<PageId>()
    while (!fits(retained)) {
        val candidates = retained.filter { it.token.pageId != incoming.token.pageId && it.edits.operations.isEmpty() }
        if (candidates.isEmpty()) return RetentionResult.Rejected(capacity(retained))
        val victim = candidates.reduce { oldest, candidate ->
            if (accessOrder.getValue(candidate.token.pageId) < accessOrder.getValue(oldest.token.pageId)) candidate else oldest
        }
        retained.remove(victim)
        evicted.add(victim.token.pageId)
    }
    return RetentionResult.Retained(retained.toImmutableList(), evicted.toImmutableList())
}

/** Count payload references as well as operations; a hidden replacement still consumes memory. */
private fun <T : Any> editUnits(edits: PageEdits<T>): Long = edits.operations.sumOf { edit ->
    when (edit) {
        is PageEdit.Update -> 2L
        is PageEdit.Insert -> 2L
        is PageEdit.Remove -> 1L
        is PageEdit.Move -> 2L
        is PageEdit.Reorder -> maxOf(1, edit.ids.size).toLong()
        is PageEdit.ReplaceItems -> maxOf(1, edit.items.size).toLong()
    }
}
