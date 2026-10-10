package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal fun <K : Any, T : Any> projectWindow(
    window: PageWindow<K>, state: PageStoreState<K, T>, itemId: (T) -> String,
): WindowLookup<K, T> = when (val content = window.content) {
    WindowContent.Unloaded -> WindowLookup.Unloaded(window)
    is WindowContent.Loaded -> {
        val pages = content.pageIds.map { id -> state.pages.first { it.token.pageId == id } }.toImmutableList()
        WindowLookup.Loaded(WindowSnapshot(window, pages,
            pages.flatMap { it.items }.distinctBy(itemId).toImmutableList(),
            pages.first().source.before, pages.last().source.after))
    }
}

internal fun <K : Any, T : Any> activePageIds(state: PageStoreState<K, T>): Set<PageId> {
    when (val active = state.activeWindow) {
        WindowSelection.None -> return emptySet()
        is WindowSelection.Selected -> for (window in state.windows) {
            if (window.token.windowId == active.id) return when (val content = window.content) {
                WindowContent.Unloaded -> emptySet()
                is WindowContent.Loaded -> content.pageIds.toSet()
            }
        }
    }
    return emptySet()
}

internal fun <T : Any> restoreAnchor(old: ViewportAnchor, previous: List<T>, next: List<T>, itemId: (T) -> String): ViewportAnchor {
    if (old !is ViewportAnchor.Item) return ViewportAnchor.None
    val nextIds = next.map(itemId).toSet()
    if (old.id in nextIds) return old
    val index = previous.indexOfFirst { itemId(it) == old.id }
    if (index < 0) return ViewportAnchor.None
    for (position in index + 1 until previous.size) {
        val id = itemId(previous[position])
        if (id in nextIds) return old.copy(id = id)
    }
    for (position in index - 1 downTo 0) {
        val id = itemId(previous[position])
        if (id in nextIds) return old.copy(id = id)
    }
    return ViewportAnchor.None
}

/** Never project a partially evicted range; all metadata changes publish with the pages. */
internal fun <K : Any, T : Any> reconcileWindows(
    previous: PageStoreState<K, T>, pages: ImmutableList<PageSnapshot<K, T>>, itemId: (T) -> String,
): ImmutableList<PageWindow<K>> {
    return previous.windows.map { window ->
        when (val content = window.content) {
            WindowContent.Unloaded -> window
            is WindowContent.Loaded -> {
                val token = window.token.copy(revision = window.token.revision + 1)
                if (content.pageIds.any { id -> pages.none { it.token.pageId == id } }) {
                    window.copy(token = token, content = WindowContent.Unloaded,
                        anchor = ViewportAnchor.None, pendingRestore = ViewportAnchor.None)
                } else {
                    val oldPages = content.pageIds.map { id -> previous.pages.first { it.token.pageId == id } }
                    val newPages = content.pageIds.map { id -> pages.first { it.token.pageId == id } }
                    if (oldPages.map { it.token } == newPages.map { it.token }) window else {
                        val anchor = restoreAnchor(window.anchor, oldPages.flatMap { it.items }, newPages.flatMap { it.items }, itemId)
                        window.copy(token = token, anchor = anchor,
                            pendingRestore = if (anchor != window.anchor) anchor else window.pendingRestore)
                    }
                }
            }
        }
    }.toImmutableList()
}
