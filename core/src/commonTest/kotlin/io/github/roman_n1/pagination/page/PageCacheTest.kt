package io.github.roman_n1.pagination.page

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest

class PageCacheTest {
    private data class Row(val id: String, val text: String = id)
    private fun source(id: String, count: Int = 2) = SourcePage(
        PageId(id), (1..count).map { Row("$id-$it") }.toImmutableList(), after = Cursor.At("cursor-$id"))

    @Test
    fun explicitAccessControlsLeastRecentlyUsedEviction() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxPages = 2, maxItems = 4))
        val generation = store.state.value.generation
        val first = store.addPage(generation, source("first")).loaded()
        store.addPage(generation, source("second")).loaded()
        assertEquals(PageLookup.Loaded(first), store.accessPage(PageId("first")))
        assertEquals(PageLookup.Missing, store.accessPage(PageId("missing")))
        val added = store.addPage(generation, source("third")).applied()
        assertEquals(persistentListOf(PageId("second")), added.evictedPages)
        assertEquals(PageLookup.Missing, store.page(PageId("second")))
        assertEquals(PageLookup.Loaded(first), store.page(PageId("first")))
        assertEquals(persistentListOf(first, added.page), store.state.value.pages)
    }

    @Test
    fun emptyPagesConsumePageBudgetAndOversizedPageRejectsWithoutEviction() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxPages = 2, maxItems = 3))
        val generation = store.state.value.generation
        store.addPage(generation, source("first", 0)).loaded()
        store.addPage(generation, source("second", 0)).loaded()
        val third = store.addPage(generation, source("third", 0)).applied()
        assertEquals(persistentListOf(PageId("first")), third.evictedPages)
        assertEquals(2, store.state.value.pages.size)
        assertEquals(Cursor.At("cursor-third"), third.page.source.after)
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.CacheCapacityExceeded(1, 4, 4, 2, 3)),
            store.addPage(generation, source("huge", 4)))
        assertEquals(before, store.state.value)
    }

    @Test
    fun sourceAndDisplayGrowthObeyTheSameHardBudget() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxPages = 5, maxItems = 4))
        val generation = store.state.value.generation
        val first = store.addPage(generation, source("first")).loaded()
        store.addPage(generation, source("second")).loaded()
        val edited = store.editPage(first.token) { append(Row("x")); append(Row("y")) }.applied()
        assertEquals(persistentListOf(PageId("second")), edited.evictedPages)
        assertEquals(4, edited.page.items.size)
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.CacheCapacityExceeded(1, 2, 5, 5, 4)),
            store.editPage(edited.page.token) { append(Row("z")) })
        assertEquals(before, store.state.value)
        assertEquals(PageChange.Rejected(PageRejection.CacheCapacityExceeded(1, 5, 5, 5, 4)),
            store.replacePage(edited.page.token, source("first", 5)))
        assertEquals(before, store.state.value)
    }

    @Test
    fun dirtyPagesAreNotSilentlyEvictedToFitAnotherLoad() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxPages = 2, maxItems = 4))
        val generation = store.state.value.generation
        val first = store.addPage(generation, source("first", 1)).loaded()
        val second = store.addPage(generation, source("second", 1)).loaded()
        store.editPage(first.token) { append(Row("local-first")) }.loaded()
        store.editPage(second.token) { append(Row("local-second")) }.loaded()
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.CacheCapacityExceeded(3, 3, 5, 2, 4)),
            store.addPage(generation, source("third", 1)))
        assertEquals(before, store.state.value)
        assertEquals(PageLookup.Missing, store.page(PageId("third")))
    }

    @Test
    fun evictedAndReloadedPageCannotReviveAnOldToken() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxPages = 1, maxItems = 2))
        val generation = store.state.value.generation
        val original = store.addPage(generation, source("first")).loaded()
        store.addPage(generation, source("other")).loaded()
        val reloaded = store.addPage(generation, source("first")).loaded()
        assertEquals(0L, original.token.instance)
        assertEquals(2L, reloaded.token.instance)
        assertEquals(original.token.generation, reloaded.token.generation)
        assertEquals(original.token.revision, reloaded.token.revision)
        val before = store.state.value
        val rejected = PageChange.Rejected(PageRejection.PageInstanceChanged(original.token, reloaded.token))
        assertEquals(rejected, store.editPage(original.token) { remove("first-1") })
        assertEquals(rejected, store.replacePage(original.token, source("first", 0)))
        assertEquals(rejected, store.reconcilePage(original.token, source("first", 0)))
        assertEquals(before, store.state.value)
    }

    @Test
    fun journalBudgetCountsPayloadEvenWhenLaterOperationsRemoveIt() = runTest {
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxItems = 10, maxEditUnitsPerPage = 3))
        val original = store.addPage(store.state.value.generation, source("first")).loaded()
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.EditCapacityExceeded(5, 3)),
            store.editPage(original.token) { replaceItems(source("temporary", 4).items); replaceItems(persistentListOf()) })
        assertEquals(before, store.state.value)
        val edited = store.editPage(original.token) { update("first-1", Row("first-1", "changed")) }.loaded()
        assertEquals(PageChange.Rejected(PageRejection.EditCapacityExceeded(4, 3)),
            store.editPage(edited.token) { update("first-2", Row("first-2", "changed")) })
        val replaced = store.replacePage(edited.token, source("first")).loaded()
        val next = store.editPage(replaced.token) { remove("first-1") }.loaded()
        assertEquals(persistentListOf(Row("first-2")), next.items)
        assertEquals(1, next.edits.operations.size)
    }

    @Test
    fun cacheConfigurationRejectsInvalidLimitsAndZeroJournalIsReadOnly() = runTest {
        assertFailsWith<IllegalArgumentException>(block = fun() { PageCacheConfig(maxPages = 0) })
        assertFailsWith<IllegalArgumentException>(block = fun() { PageCacheConfig(maxItems = 0) })
        assertFailsWith<IllegalArgumentException>(block = fun() { PageCacheConfig(maxEditUnitsPerPage = -1) })
        val store = PageStore<String, Row>(Row::id, PageCacheConfig(maxEditUnitsPerPage = 0))
        val original = store.addPage(store.state.value.generation, source("first")).loaded()
        assertEquals(PageChange.Applied(original), store.editPage(original.token) {})
        assertEquals(PageChange.Rejected(PageRejection.EditCapacityExceeded(1, 0)),
            store.editPage(original.token) { remove("first-1") })
        assertEquals(PageLookup.Loaded(original), store.page(PageId("first")))
    }

    private fun <K : Any, T : Any> PageChange<K, T>.applied(): PageChange.Applied<K, T> = when (this) {
        is PageChange.Applied -> this
        is PageChange.Rejected -> error("Unexpected rejection: $reason")
    }
    private fun <K : Any, T : Any> PageChange<K, T>.loaded(): PageSnapshot<K, T> = applied().page
}
