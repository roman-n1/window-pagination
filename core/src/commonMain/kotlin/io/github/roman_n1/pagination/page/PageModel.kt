package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf

/** A cursor belongs to the source order, independent of displayed item order. */
sealed interface Cursor<out K : Any> {
    data object End : Cursor<Nothing>
    data class At<K : Any>(val key: K) : Cursor<K>
}

data class PageId(val value: String)
data class PageGeneration(val value: Long)
data class PageToken(val generation: PageGeneration, val pageId: PageId, val revision: Long)

/** An empty page can still have before/after continuation cursors. */
data class SourcePage<out K : Any, out T : Any>(
    val id: PageId,
    val items: ImmutableList<T>,
    val before: Cursor<K> = Cursor.End,
    val after: Cursor<K> = Cursor.End,
)

data class PageSnapshot<out K : Any, out T : Any>(
    val token: PageToken,
    val source: SourcePage<K, T>,
    val items: ImmutableList<T>,
)

data class PageStoreState<out K : Any, out T : Any>(
    val generation: PageGeneration = PageGeneration(0),
    val pages: ImmutableList<PageSnapshot<K, T>> = persistentListOf(),
)

sealed interface PageLookup<out K : Any, out T : Any> {
    data object Missing : PageLookup<Nothing, Nothing>
    data class Loaded<K : Any, T : Any>(val page: PageSnapshot<K, T>) : PageLookup<K, T>
}

sealed interface PageChange<out K : Any, out T : Any> {
    data class Applied<K : Any, T : Any>(val page: PageSnapshot<K, T>) : PageChange<K, T>
    data class Rejected(val reason: PageRejection) : PageChange<Nothing, Nothing>
}

sealed interface PageRejection {
    data class GenerationChanged(val expected: PageGeneration, val actual: PageGeneration) : PageRejection
    data class PageAlreadyLoaded(val pageId: PageId) : PageRejection
    data class DuplicateItems(val ids: ImmutableSet<String>) : PageRejection
}
