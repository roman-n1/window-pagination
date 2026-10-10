package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf

data class WindowId(val value: String)
data class WindowToken(val generation: PageGeneration, val windowId: WindowId, val revision: Long, val instance: Long)

sealed interface WindowSeed<out K : Any> {
    data object Newest : WindowSeed<Nothing>
    data class Around(val itemId: String) : WindowSeed<Nothing>
    data class At<K : Any>(val key: K) : WindowSeed<K>
}

sealed interface WindowContent {
    data object Unloaded : WindowContent
    data class Loaded(val pageIds: ImmutableList<PageId>) : WindowContent
}

sealed interface WindowSelection {
    data object None : WindowSelection
    data class Selected(val id: WindowId) : WindowSelection
}

sealed interface ViewportAnchor {
    data object None : ViewportAnchor
    data class Item(val id: String, val offsetPixels: Int = 0) : ViewportAnchor
}

data class PageWindow<out K : Any>(
    val token: WindowToken,
    val seed: WindowSeed<K>,
    val content: WindowContent = WindowContent.Unloaded,
    val anchor: ViewportAnchor = ViewportAnchor.None,
    val pendingRestore: ViewportAnchor = ViewportAnchor.None,
)

data class WindowSnapshot<out K : Any, out T : Any>(
    val window: PageWindow<K>,
    val pages: ImmutableList<PageSnapshot<K, T>>,
    val items: ImmutableList<T>,
    val before: Cursor<K>,
    val after: Cursor<K>,
)

sealed interface WindowLookup<out K : Any, out T : Any> {
    data object Missing : WindowLookup<Nothing, Nothing>
    data class Unloaded<K : Any>(val window: PageWindow<K>) : WindowLookup<K, Nothing>
    data class Loaded<K : Any, T : Any>(val snapshot: WindowSnapshot<K, T>) : WindowLookup<K, T>
}

sealed interface WindowChange<out K : Any> {
    data class Applied<K : Any>(val window: PageWindow<K>, val evictedWindows: ImmutableList<WindowId> = persistentListOf()) : WindowChange<K>
    data class Closed(val id: WindowId) : WindowChange<Nothing>
    data class Rejected(val reason: WindowRejection) : WindowChange<Nothing>
}

sealed interface WindowRejection {
    data class GenerationChanged(val expected: PageGeneration, val actual: PageGeneration) : WindowRejection
    data class AlreadyOpen(val id: WindowId) : WindowRejection
    data class Missing(val id: WindowId) : WindowRejection
    data class InstanceChanged(val expected: WindowToken, val actual: WindowToken) : WindowRejection
    data class RevisionChanged(val expected: WindowToken, val actual: WindowToken) : WindowRejection
    data class MissingPage(val id: PageId) : WindowRejection
    data class DuplicatePages(val ids: ImmutableSet<PageId>) : WindowRejection
    data object EmptyRange : WindowRejection
    data class InvalidAnchor(val id: String) : WindowRejection
    data class CapacityExceeded(val maxWindows: Int) : WindowRejection
}
