package io.github.roman_n1.pagination.page

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest

class PageWindowsTest {
    private data class Row(val id: String, val text: String = id)
    private fun source(id: String, vararg items: Row) = SourcePage(
        PageId(id), items.toList().toImmutableList(), Cursor.At("before-$id"), Cursor.At("after-$id"))

    @Test
    fun disjointWindowsKeepIndependentRangesCursorsAndAnchors() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val first = store.addPage(store.state.value.generation, source("p1", Row("a"), Row("b"))).loaded()
        val far = store.addPage(store.state.value.generation, source("p2", Row("z"))).loaded()
        val opened = store.windows.open(WindowId("first"), WindowSeed.At("offset-73")).applied().window
        val attached = store.windows.attach(opened.token, persistentListOf(first.token.pageId)).applied().window
        store.windows.recordAnchor(attached.token, ViewportAnchor.Item("b", 12)).applied()
        val distant = store.windows.open(WindowId("distant"), WindowSeed.Around("z")).applied().window
        store.windows.attach(distant.token, persistentListOf(far.token.pageId)).applied()
        val selected = store.windows.active().loaded()
        assertEquals(persistentListOf(Row("z")), selected.items)
        assertEquals(Cursor.At("before-p2"), selected.before)
        assertEquals(Cursor.At("after-p2"), selected.after)
        val firstSnapshot = store.windows.window(WindowId("first")).loaded()
        assertEquals(persistentListOf(Row("a"), Row("b")), firstSnapshot.items)
        assertEquals(ViewportAnchor.Item("b", 12), firstSnapshot.window.anchor)
        val activated = store.windows.activate(WindowId("first")).applied().window
        assertEquals(ViewportAnchor.Item("b", 12), activated.pendingRestore)
        assertEquals(firstSnapshot.copy(window = activated), store.windows.active().loaded())
        assertEquals(WindowSelection.Selected(WindowId("first")), store.state.value.activeWindow)
        assertEquals(persistentListOf(first, far), store.state.value.pages)
    }

    @Test
    fun emptyLoadedWindowIsDifferentFromUnloadedAndOverlapUsesExplicitPageOrder() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val opened = store.windows.open(WindowId("window"), WindowSeed.Newest).applied().window
        assertEquals(WindowLookup.Unloaded(opened), store.windows.active())
        val empty = store.addPage(store.state.value.generation, source("empty")).loaded()
        val bound = store.windows.attach(opened.token, persistentListOf(empty.token.pageId)).applied().window
        val snapshot = store.windows.active().loaded()
        assertEquals(persistentListOf(), snapshot.items)
        assertEquals(Cursor.At("after-empty"), snapshot.after)
        val first = store.addPage(store.state.value.generation, source("first", Row("b", "new"), Row("a"))).loaded()
        val second = store.addPage(store.state.value.generation, source("second", Row("b", "old"), Row("c"))).loaded()
        store.windows.attach(bound.token, persistentListOf(first.token.pageId, second.token.pageId)).applied()
        val overlapping = store.windows.active().loaded()
        assertEquals(persistentListOf(Row("b", "new"), Row("a"), Row("c")), overlapping.items)
        assertEquals(Cursor.At("before-first"), overlapping.before)
        assertEquals(Cursor.At("after-second"), overlapping.after)
    }

    @Test
    fun activePagesAreProtectedAndInactiveEvictionUnloadsTheWholeRange() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxItems = 3))
        val generation = store.state.value.generation
        val a = store.addPage(generation, source("a", Row("a"))).loaded()
        val b = store.addPage(generation, source("b", Row("b"))).loaded()
        val history = store.windows.open(WindowId("history"), WindowSeed.At("old-offset")).applied().window
        store.windows.attach(history.token, persistentListOf(a.token.pageId, b.token.pageId)).applied()
        val live = store.windows.open(WindowId("live"), WindowSeed.Newest).applied().window
        val c = store.addPage(generation, source("c", Row("c"))).loaded()
        store.windows.attach(live.token, persistentListOf(c.token.pageId)).applied()
        val beforeLive = store.windows.active().loaded()
        val added = store.addPage(generation, source("d", Row("d"))).applied()
        assertEquals(persistentListOf(PageId("a")), added.evictedPages)
        val unloaded = store.state.value.windows.first { it.token.windowId == WindowId("history") }
        assertEquals(WindowContent.Unloaded, unloaded.content)
        assertEquals(WindowSeed.At("old-offset"), unloaded.seed)
        assertEquals(WindowLookup.Unloaded(unloaded), store.windows.window(WindowId("history")))
        assertEquals(beforeLive, store.windows.active().loaded())
        // One incoming page fits alone, but it cannot displace the active page.
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.CacheCapacityExceeded(2, 4, 4, 64, 3)),
            store.addPage(generation, source("huge", Row("x"), Row("y"), Row("z"))))
        assertEquals(before, store.state.value)
    }

    @Test
    fun deletionRestoresNextOldNeighbourThenPreviousAndPreservesPixelOffset() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val page = store.addPage(store.state.value.generation, source("page", Row("a"), Row("b"), Row("c"), Row("d"))).loaded()
        val opened = store.windows.open(WindowId("window"), WindowSeed.Newest).applied().window
        val attached = store.windows.attach(opened.token, persistentListOf(page.token.pageId)).applied().window
        store.windows.recordAnchor(attached.token, ViewportAnchor.Item("b", 12)).applied()
        val changed = store.editPage(page.token) { remove("b"); remove("c") }.loaded()
        val next = store.windows.active().loaded()
        assertEquals(ViewportAnchor.Item("d", 12), next.window.anchor)
        assertEquals(next.window.anchor, next.window.pendingRestore)
        assertEquals(attached.token.copy(revision = attached.token.revision + 1), next.window.token)
        val originalCursors = next.before to next.after
        val oldToken = attached.token
        assertEquals(WindowChange.Rejected(WindowRejection.RevisionChanged(oldToken, next.window.token)),
            store.windows.recordAnchor(oldToken, ViewportAnchor.Item("a")))
        store.windows.acknowledgeRestore(next.window.token).applied()
        assertEquals(ViewportAnchor.None, store.windows.active().loaded().window.pendingRestore)
        val withoutD = store.editPage(changed.token) { remove("d") }.loaded()
        assertEquals(ViewportAnchor.Item("a", 12), store.windows.active().loaded().window.anchor)
        store.editPage(withoutD.token) { remove("a") }.loaded()
        assertEquals(ViewportAnchor.None, store.windows.active().loaded().window.anchor)
        assertEquals(ViewportAnchor.None, store.windows.active().loaded().window.pendingRestore)
        assertEquals(originalCursors, store.windows.active().loaded().let { it.before to it.after })
    }

    @Test
    fun invalidAttachmentsAndAnchorsLeaveEveryWindowUntouched() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val page = store.addPage(store.state.value.generation, source("page", Row("a"))).loaded()
        val window = store.windows.open(WindowId("window"), WindowSeed.Newest).applied().window
        val before = store.state.value
        assertEquals(WindowChange.Rejected(WindowRejection.EmptyRange), store.windows.attach(window.token, persistentListOf()))
        assertEquals(WindowChange.Rejected(WindowRejection.MissingPage(PageId("missing"))),
            store.windows.attach(window.token, persistentListOf(PageId("missing"))))
        assertEquals(WindowChange.Rejected(WindowRejection.DuplicatePages(persistentSetOf(page.token.pageId))),
            store.windows.attach(window.token, persistentListOf(page.token.pageId, page.token.pageId)))
        assertEquals(WindowChange.Rejected(WindowRejection.AlreadyOpen(WindowId("window"))),
            store.windows.open(WindowId("window"), WindowSeed.At("wrong")))
        assertEquals(before, store.state.value)
        val attached = store.windows.attach(window.token, persistentListOf(page.token.pageId)).applied().window
        val bound = store.state.value
        assertEquals(WindowChange.Rejected(WindowRejection.InvalidAnchor("missing")),
            store.windows.requestRestore(attached.token, ViewportAnchor.Item("missing")))
        assertEquals(bound, store.state.value)
        store.windows.requestRestore(attached.token, ViewportAnchor.Item("a", 7)).applied()
        assertEquals(ViewportAnchor.Item("a", 7), store.windows.active().loaded().window.pendingRestore)
        // Recording geometry does not change source-range ownership or request a scroll.
        val recorded = store.windows.recordAnchor(attached.token, ViewportAnchor.Item("a", 9)).applied().window
        assertEquals(attached.token, recorded.token)
        assertEquals(ViewportAnchor.Item("a", 9), recorded.anchor)
        assertEquals(ViewportAnchor.None, recorded.pendingRestore)
    }

    @Test
    fun windowSlotsUseLruAndProtectLocalEditsUntilExplicitClose() = runTest {
        assertFailsWith<IllegalArgumentException>(block = fun() { PageCacheConfig(maxWindows = 0) })
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxWindows = 2))
        val a = store.windows.open(WindowId("a"), WindowSeed.At("a")).applied().window
        store.windows.open(WindowId("b"), WindowSeed.At("b")).applied()
        store.windows.activate(WindowId("a")).applied()
        val c = store.windows.open(WindowId("c"), WindowSeed.At("c")).applied()
        assertEquals(persistentListOf(WindowId("b")), c.evictedWindows)
        assertEquals(WindowLookup.Missing, store.windows.window(WindowId("b")))
        assertEquals(WindowLookup.Unloaded(a), store.windows.window(WindowId("a")))
        val protected = PageStore<String, Row>(Row::id, PageCacheConfig(maxWindows = 1))
        val page = protected.addPage(protected.state.value.generation, source("page", Row("a"))).loaded()
        val opened = protected.windows.open(WindowId("dirty"), WindowSeed.Newest).applied().window
        protected.windows.attach(opened.token, persistentListOf(page.token.pageId)).applied()
        protected.editPage(page.token) { append(Row("local")) }.loaded()
        val current = protected.windows.active().loaded()
        val before = protected.state.value
        assertEquals(WindowChange.Rejected(WindowRejection.CapacityExceeded(1)),
            protected.windows.open(WindowId("other"), WindowSeed.Newest))
        assertEquals(before, protected.state.value)
        assertEquals(WindowChange.Closed(WindowId("dirty")), protected.windows.close(current.window.token))
        assertEquals(WindowLookup.Missing, protected.windows.active())
        assertEquals(persistentListOf(Row("a"), Row("local")), protected.state.value.pages.single().items)
        protected.windows.open(WindowId("other"), WindowSeed.Newest).applied()
    }

    @Test
    fun closedReopenedAndResetWindowsRejectLateBindingTokens() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val page = store.addPage(store.state.value.generation, source("page", Row("a"))).loaded()
        val first = store.windows.open(WindowId("window"), WindowSeed.Newest).applied().window
        store.windows.close(first.token)
        val second = store.windows.open(WindowId("window"), WindowSeed.Newest).applied().window
        assertEquals(first.token.instance + 1, second.token.instance)
        val before = store.state.value
        assertEquals(WindowChange.Rejected(WindowRejection.InstanceChanged(first.token, second.token)),
            store.windows.attach(first.token, persistentListOf(page.token.pageId)))
        assertEquals(before, store.state.value)
        val generation = store.reset()
        assertEquals(WindowChange.Rejected(WindowRejection.GenerationChanged(second.token.generation, generation)),
            store.windows.attach(second.token, persistentListOf(page.token.pageId)))
        assertEquals(WindowLookup.Missing, store.windows.active())
        assertEquals(persistentListOf(), store.state.value.windows)
        assertEquals(WindowSelection.None, store.state.value.activeWindow)
    }

    @Test
    fun inactiveLoadCompletionDoesNotSelectOrOverwriteTheActiveWindow() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val page = store.addPage(store.state.value.generation, source("page", Row("a"))).loaded()
        val old = store.windows.open(WindowId("old"), WindowSeed.At("old")).applied().window
        val active = store.windows.open(WindowId("active"), WindowSeed.At("active")).applied().window
        store.windows.attach(old.token, persistentListOf(page.token.pageId)).applied()
        assertEquals(WindowLookup.Unloaded(active), store.windows.active())
        assertEquals(persistentListOf(Row("a")), store.windows.window(WindowId("old")).loaded().items)
        assertEquals(WindowSelection.Selected(WindowId("active")), store.state.value.activeWindow)
    }

    @Test
    fun anchorFallbackUsesDeduplicatedDisplayOrderAcrossOverlappingPages() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val first = store.addPage(store.state.value.generation, source("first", Row("a"), Row("b"))).loaded()
        val second = store.addPage(store.state.value.generation, source("second", Row("a"), Row("c"))).loaded()
        val opened = store.windows.open(WindowId("window"), WindowSeed.Newest).applied().window
        val attached = store.windows.attach(opened.token, persistentListOf(first.token.pageId, second.token.pageId)).applied().window
        store.windows.recordAnchor(attached.token, ViewportAnchor.Item("b", 6)).applied()
        store.editPage(first.token) { remove("b") }.loaded()
        assertEquals(persistentListOf(Row("a"), Row("c")), store.windows.active().loaded().items)
        assertEquals(ViewportAnchor.Item("c", 6), store.windows.active().loaded().window.anchor)
        assertEquals(ViewportAnchor.Item("c", 6), store.windows.active().loaded().window.pendingRestore)
    }

    @Test
    fun resettingOneWindowInvalidatesOnlyItsPendingBindings() = runTest {
        val store = PageStore<String, Row>(Row::id)
        val page = store.addPage(store.state.value.generation, source("page", Row("a"))).loaded()
        val first = store.windows.open(WindowId("first"), WindowSeed.Newest).applied().window
        val attached = store.windows.attach(first.token, persistentListOf(page.token.pageId)).applied().window
        store.windows.recordAnchor(attached.token, ViewportAnchor.Item("a", 11)).applied()
        val other = store.windows.open(WindowId("other"), WindowSeed.At("other")).applied().window
        val reset = store.windows.reset(attached.token, WindowSeed.At("new-position")).applied().window
        assertEquals(attached.token.copy(revision = attached.token.revision + 1), reset.token)
        assertEquals(WindowSeed.At("new-position"), reset.seed)
        assertEquals(WindowContent.Unloaded, reset.content)
        assertEquals(ViewportAnchor.None, reset.anchor)
        assertEquals(ViewportAnchor.None, reset.pendingRestore)
        assertEquals(WindowLookup.Unloaded(other), store.windows.active())
        assertEquals(WindowChange.Rejected(WindowRejection.RevisionChanged(attached.token, reset.token)),
            store.windows.attach(attached.token, persistentListOf(page.token.pageId)))
        assertEquals(PageGeneration(0), store.state.value.generation)
        assertEquals(PageLookup.Loaded(page), store.page(page.token.pageId))
    }

    private fun <K : Any> WindowChange<K>.applied(): WindowChange.Applied<K> = when (this) {
        is WindowChange.Applied -> this
        is WindowChange.Rejected -> error("Unexpected rejection: $reason")
        is WindowChange.Closed -> error("Unexpected close: $id")
    }
    private fun <K : Any, T : Any> PageChange<K, T>.applied(): PageChange.Applied<K, T> = when (this) {
        is PageChange.Applied -> this
        is PageChange.Rejected -> error("Unexpected rejection: $reason")
    }
    private fun <K : Any, T : Any> PageChange<K, T>.loaded(): PageSnapshot<K, T> = applied().page
    private fun <K : Any, T : Any> WindowLookup<K, T>.loaded(): WindowSnapshot<K, T> = when (this) {
        is WindowLookup.Loaded -> snapshot
        is WindowLookup.Unloaded -> error("Unloaded window: $window")
        WindowLookup.Missing -> error("Missing window")
    }
}
