package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

internal data class WindowMutation<K : Any, T : Any>(val state: PageStoreState<K, T>, val change: WindowChange<K>)

/** Window metadata and page contents publish under the same store mutex and StateFlow. */
class PageWindows<K : Any, T : Any> internal constructor(
    private val read: () -> PageStoreState<K, T>,
    private val mutate: suspend ((PageStoreState<K, T>) -> WindowMutation<K, T>) -> WindowChange<K>,
    private val itemId: (T) -> String,
    private val config: PageCacheConfig,
) {
    private val accessOrder = mutableMapOf<WindowId, Long>()
    private var accessClock = 0L
    private var nextInstance = 0L

    fun window(id: WindowId): WindowLookup<K, T> = lookup(read(), id)
    fun active(): WindowLookup<K, T> {
        val current = read()
        return when (val selection = current.activeWindow) {
            WindowSelection.None -> WindowLookup.Missing
            is WindowSelection.Selected -> lookup(current, selection.id)
        }
    }

    private fun lookup(current: PageStoreState<K, T>, id: WindowId): WindowLookup<K, T> {
        for (window in current.windows) if (window.token.windowId == id) return projectWindow(window, current, itemId)
        return WindowLookup.Missing
    }

    suspend fun open(id: WindowId, seed: WindowSeed<K>): WindowChange<K> = mutate { current ->
        if (current.windows.any { it.token.windowId == id }) return@mutate rejected(current, WindowRejection.AlreadyOpen(id))
        val windows = current.windows.toMutableList()
        val evicted = mutableListOf<WindowId>()
        if (windows.size >= config.maxWindows) {
            val candidates = windows.filter { window -> when (val content = window.content) {
                WindowContent.Unloaded -> true
                is WindowContent.Loaded -> content.pageIds.none { pageId -> current.pages.first { it.token.pageId == pageId }.edits.operations.isNotEmpty() }
            } }
            if (candidates.isEmpty()) return@mutate rejected(current, WindowRejection.CapacityExceeded(config.maxWindows))
            val victim = candidates.reduce { oldest, candidate ->
                if (accessOrder.getValue(candidate.token.windowId) < accessOrder.getValue(oldest.token.windowId)) candidate else oldest
            }
            windows.remove(victim)
            evicted.add(victim.token.windowId)
        }
        val window = PageWindow(WindowToken(current.generation, id, 0, nextInstance), seed)
        windows.add(window)
        for (victim in evicted) accessOrder.remove(victim)
        touch(id)
        nextInstance++
        WindowMutation(current.copy(windows = windows.toImmutableList(), activeWindow = WindowSelection.Selected(id)),
            WindowChange.Applied(window, evicted.toImmutableList()))
    }

    suspend fun activate(id: WindowId): WindowChange<K> = mutate { current ->
        val index = current.windows.indexOfFirst { it.token.windowId == id }
        if (index < 0) return@mutate rejected(current, WindowRejection.Missing(id))
        val window = current.windows[index].let { it.copy(pendingRestore = it.anchor) }
        touch(id)
        applied(current.copy(activeWindow = WindowSelection.Selected(id)), index, window)
    }

    suspend fun attach(token: WindowToken, pageIds: ImmutableList<PageId>): WindowChange<K> = change(token) { current, index, window ->
        if (pageIds.isEmpty()) return@change rejected(current, WindowRejection.EmptyRange)
        val duplicates = pageIds.groupBy { it }.filterValues { it.size > 1 }.keys.toImmutableSet()
        if (duplicates.isNotEmpty()) return@change rejected(current, WindowRejection.DuplicatePages(duplicates))
        for (id in pageIds) if (current.pages.none { it.token.pageId == id }) return@change rejected(current, WindowRejection.MissingPage(id))
        val previousItems = when (val snapshot = projectWindow(window, current, itemId)) {
            is WindowLookup.Loaded -> snapshot.snapshot.items
            is WindowLookup.Unloaded, WindowLookup.Missing -> persistentListOf()
        }
        val nextItems = pageIds.flatMap { id -> current.pages.first { it.token.pageId == id }.items }.distinctBy(itemId)
        val anchor = restoreAnchor(window.anchor, previousItems, nextItems, itemId)
        val updated = window.copy(token = token.copy(revision = token.revision + 1), content = WindowContent.Loaded(pageIds),
            anchor = anchor, pendingRestore = if (anchor != window.anchor) anchor else window.pendingRestore)
        touch(token.windowId)
        applied(current, index, updated)
    }

    suspend fun recordAnchor(token: WindowToken, anchor: ViewportAnchor): WindowChange<K> = updateAnchor(token, anchor, restore = false)
    suspend fun requestRestore(token: WindowToken, anchor: ViewportAnchor): WindowChange<K> = updateAnchor(token, anchor, restore = true)

    private suspend fun updateAnchor(token: WindowToken, anchor: ViewportAnchor, restore: Boolean): WindowChange<K> = change(token) { current, index, window ->
        if (anchor is ViewportAnchor.Item) {
            val content = window.content
            if (content !is WindowContent.Loaded || content.pageIds.none { id ->
                    current.pages.first { it.token.pageId == id }.items.any { itemId(it) == anchor.id }
                }) return@change rejected(current, WindowRejection.InvalidAnchor(anchor.id))
        }
        touch(token.windowId)
        applied(current, index, window.copy(anchor = anchor, pendingRestore = if (restore) anchor else ViewportAnchor.None))
    }

    suspend fun acknowledgeRestore(token: WindowToken): WindowChange<K> = change(token) { current, index, window ->
        applied(current, index, window.copy(pendingRestore = ViewportAnchor.None))
    }

    suspend fun reset(token: WindowToken, seed: WindowSeed<K>): WindowChange<K> = change(token) { current, index, window ->
        touch(token.windowId)
        applied(current, index, window.copy(token = token.copy(revision = token.revision + 1), seed = seed,
            content = WindowContent.Unloaded, anchor = ViewportAnchor.None, pendingRestore = ViewportAnchor.None))
    }

    suspend fun close(token: WindowToken): WindowChange<K> = change(token) { current, index, window ->
        val windows = current.windows.toMutableList().apply { removeAt(index) }
        val selection = if (current.activeWindow == WindowSelection.Selected(token.windowId)) WindowSelection.None else current.activeWindow
        accessOrder.remove(window.token.windowId)
        WindowMutation(current.copy(windows = windows.toImmutableList(), activeWindow = selection), WindowChange.Closed(token.windowId))
    }

    private suspend fun change(
        token: WindowToken,
        transform: (PageStoreState<K, T>, Int, PageWindow<K>) -> WindowMutation<K, T>,
    ): WindowChange<K> = mutate { current ->
        if (token.generation != current.generation) return@mutate rejected(current, WindowRejection.GenerationChanged(token.generation, current.generation))
        val index = current.windows.indexOfFirst { it.token.windowId == token.windowId }
        if (index < 0) return@mutate rejected(current, WindowRejection.Missing(token.windowId))
        val window = current.windows[index]
        if (token.instance != window.token.instance) return@mutate rejected(current, WindowRejection.InstanceChanged(token, window.token))
        if (token != window.token) return@mutate rejected(current, WindowRejection.RevisionChanged(token, window.token))
        transform(current, index, window)
    }

    private fun rejected(current: PageStoreState<K, T>, reason: WindowRejection) = WindowMutation(current, WindowChange.Rejected(reason))
    private fun applied(current: PageStoreState<K, T>, index: Int, window: PageWindow<K>): WindowMutation<K, T> {
        val windows = current.windows.toMutableList()
        windows[index] = window
        return WindowMutation(current.copy(windows = windows.toImmutableList()), WindowChange.Applied(window))
    }
    private fun touch(id: WindowId) { accessOrder[id] = accessClock++ }
    internal fun clearRecency() { accessOrder.clear() }
}
