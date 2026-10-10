package io.github.roman_n1.pagination.page

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class PageStoreTest {
    private data class Row(val id: String)
    private data class Offset(val network: Int, val cache: Int)

    @Test
    fun storesOpaqueCursorsAndIndependentPages() = runTest {
        val store = PageStore<Offset, Row>(Row::id)
        val source = SourcePage(PageId("first"), persistentListOf(Row("a")),
            Cursor.At(Offset(73, 12)), Cursor.At(Offset(97, 28)))
        val other = SourcePage<Offset, Row>(PageId("distant"), persistentListOf(Row("z")))
        val generation = store.state.value.generation
        val added = store.addPage(generation, source).loaded()
        val second = store.addPage(generation, other).loaded()
        assertEquals(PageToken(PageGeneration(0), PageId("first"), 0), added.token)
        assertEquals(source, added.source)
        assertEquals(persistentListOf(Row("a")), added.items)
        assertEquals(persistentListOf(added, second), store.state.value.pages)
        assertEquals(PageLookup.Loaded(second), store.page(PageId("distant")))
        assertEquals(PageLookup.Missing, store.page(PageId("unknown")))
    }

    @Test
    fun emptyPageRetainsContinuationAndIsDifferentFromMissing() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val source = SourcePage<Int, Row>(PageId("empty"), persistentListOf(),
            before = Cursor.End, after = Cursor.At(40))
        val added = store.addPage(store.state.value.generation, source).loaded()
        assertEquals(persistentListOf(), added.items)
        assertEquals(Cursor.At(40), added.source.after)
        assertEquals(PageLookup.Loaded(added), store.page(PageId("empty")))
        assertEquals(PageLookup.Missing, store.page(PageId("other")))
    }

    @Test
    fun rejectsLateSourceAfterResetAndKeepsNewGenerationIntact() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val oldGeneration = store.state.value.generation
        store.addPage(oldGeneration, SourcePage(PageId("old"), persistentListOf(Row("old")), after = Cursor.At(10)))
        val generation = store.reset()
        assertEquals(PageGeneration(1), generation)
        assertEquals(PageStoreState<Int, Row>(generation = generation), store.state.value)
        val newPage = store.addPage(generation, SourcePage(PageId("new"), persistentListOf(Row("new")), after = Cursor.At(90))).loaded()
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.GenerationChanged(oldGeneration, generation)),
            store.addPage(oldGeneration, SourcePage(PageId("late"), persistentListOf(Row("late")), after = Cursor.At(20))))
        assertEquals(before, store.state.value)
        assertEquals(PageLookup.Loaded(newPage), store.page(PageId("new")))
        assertEquals(PageLookup.Missing, store.page(PageId("old")))
    }

    @Test
    fun duplicateIdsAndDuplicatePageRegistrationLeaveStateUntouched() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val generation = store.state.value.generation
        val source = SourcePage(PageId("first"), persistentListOf(Row("a")), after = Cursor.At(2))
        store.addPage(generation, source).loaded()
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.PageAlreadyLoaded(PageId("first"))),
            store.addPage(generation, source.copy(items = persistentListOf(Row("replacement")))))
        assertEquals(before, store.state.value)
        assertEquals(PageChange.Rejected(PageRejection.DuplicateItems(persistentSetOf("x"))),
            store.addPage(generation, SourcePage(PageId("other"), persistentListOf(Row("x"), Row("x")), after = Cursor.At(3))))
        assertEquals(before, store.state.value)
    }

    @Test
    fun concurrentRegistrationsKeepEveryIndependentPage() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val generation = store.state.value.generation
        val changes = (1..30).map { index ->
            async { store.addPage(generation, SourcePage(PageId("p$index"), persistentListOf(Row("r$index")), after = Cursor.At(index))) }
        }.awaitAll()
        assertEquals((1..30).map { "r$it" }.toSet(), store.state.value.pages.flatMap { it.items }.map { it.id }.toSet())
        assertEquals(30, changes.map { it.loaded().token.pageId }.distinct().size)
    }

    private fun <K : Any, T : Any> PageChange<K, T>.loaded(): PageSnapshot<K, T> = when (this) {
        is PageChange.Applied -> page
        is PageChange.Rejected -> error("Unexpected rejection: $reason")
    }
}
