package io.github.roman_n1.pagination.page

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PageTransactionsTest {
    private data class Row(val id: String, val text: String = id)
    private fun rows(vararg ids: String): ImmutableList<Row> = ids.map { Row(it) }.let { persistentListOf(*it.toTypedArray()) }
    private fun source() = SourcePage(PageId("first"), rows("a", "b", "c"), Cursor.At(73), Cursor.At(97))

    @Test
    fun transactionPublishesOneRevisionAndKeepsSourceCursorsAndOtherPages() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val other = store.addPage(store.state.value.generation, SourcePage<Int, Row>(PageId("other"), rows("z"))).loaded()
        val observed = mutableListOf<PageStoreState<Int, Row>>()
        val collector = backgroundScope.async {
            store.state.collect { observed.add(it) }
        }
        runCurrent()
        val changed = store.editPage(original.token) {
            update("a", Row("a", "edited"))
            append(Row("d"))
            moveBefore("c", "a")
            insertAfter("a", Row("x"))
            remove("b")
        }.loaded()
        runCurrent()
        assertEquals(persistentListOf(Row("c"), Row("a", "edited"), Row("x"), Row("d")), changed.items)
        assertEquals(original.source, changed.source)
        assertEquals(original.token.copy(revision = 1), changed.token)
        assertEquals(5, changed.edits.operations.size)
        assertEquals(persistentListOf(changed, other), store.state.value.pages)
        assertEquals(2, observed.size)
        assertEquals(store.state.value, observed.last())
        collector.cancel()
    }

    @Test
    fun reorderAndReplaceDisplayDoNotNormalizeOrderOrChangeSource() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val reordered = store.editPage(original.token) { reorder(persistentListOf("c", "a", "b")) }.loaded()
        assertEquals(rows("c", "a", "b"), reordered.items)
        assertEquals(original.source, reordered.source)
        val replaced = store.editPage(reordered.token) { replaceItems(rows("z", "w")) }.loaded()
        assertEquals(rows("z", "w"), replaced.items)
        assertEquals(original.source, replaced.source)
        assertEquals(2, replaced.edits.operations.size)
        assertEquals(2L, replaced.token.revision)
    }

    @Test
    fun explicitSourceReplacementClearsEditsAndAcceptsEmptyContinuation() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val edited = store.editPage(original.token) { append(Row("x")) }.loaded()
        val replacement = SourcePage<Int, Row>(PageId("first"), persistentListOf(), after = Cursor.At(200))
        val replaced = store.replacePage(edited.token, replacement).loaded()
        assertEquals(replacement, replaced.source)
        assertEquals(persistentListOf(), replaced.items)
        assertEquals(PageEdits<Row>(), replaced.edits)
        assertEquals(edited.token.copy(revision = 2), replaced.token)
    }

    @Test
    fun invalidTransactionRollsBackAllOperationsAndRevision() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.DuplicateItems(persistentSetOf("a"))),
            store.editPage(original.token) { update("a", Row("a", "changed")); append(Row("a")) })
        assertEquals(before, store.state.value)
        assertEquals(PageChange.Rejected(PageRejection.ItemIdentityChanged("a", "z")),
            store.editPage(original.token) { update("a", Row("z")) })
        assertEquals(PageChange.Rejected(PageRejection.UnknownItem("missing")),
            store.editPage(original.token) { remove("missing") })
        assertEquals(PageChange.Rejected(PageRejection.UnknownItem("missing")),
            store.editPage(original.token) { insertBefore("missing", Row("x")) })
        assertEquals(PageChange.Rejected(PageRejection.UnknownItem("missing")),
            store.editPage(original.token) { moveAfter("a", "missing") })
        val badOrder = persistentListOf("a", "a", "c")
        assertEquals(PageChange.Rejected(PageRejection.InvalidOrder(persistentListOf("a", "b", "c"), badOrder)),
            store.editPage(original.token) { reorder(badOrder) })
        assertEquals(before, store.state.value)
        assertEquals(PageChange.Rejected(PageRejection.PageIdentityChanged(PageId("first"), PageId("wrong"))),
            store.replacePage(original.token, source().copy(id = PageId("wrong"))))
        assertEquals(PageChange.Rejected(PageRejection.DuplicateItems(persistentSetOf("z"))),
            store.replacePage(original.token, source().copy(items = rows("z", "z"))))
        assertEquals(before, store.state.value)
    }

    @Test
    fun tokenGuardsRevisionGenerationAndPageIdentity() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val edited = store.editPage(original.token) { remove("b") }.loaded()
        val before = store.state.value
        val stale = PageChange.Rejected(PageRejection.RevisionChanged(original.token, edited.token))
        assertEquals(stale, store.editPage(original.token) { append(Row("x")) })
        assertEquals(stale, store.replacePage(original.token, source()))
        assertEquals(stale, store.reconcilePage(original.token, source()))
        assertEquals(before, store.state.value)
        val missing = original.token.copy(pageId = PageId("missing"))
        assertEquals(PageChange.Rejected(PageRejection.MissingPage(PageId("missing"))), store.editPage(missing) {})
        val generation = store.reset()
        assertEquals(PageChange.Rejected(PageRejection.GenerationChanged(original.token.generation, generation)),
            store.editPage(edited.token) { append(Row("x")) })
        assertEquals(PageStoreState<Int, Row>(generation = generation), store.state.value)
    }

    @Test
    fun refreshFinishingAfterEditCannotOverwriteNewerPage() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val response = CompletableDeferred<SourcePage<Int, Row>>()
        val loading = async { store.reconcilePage(original.token, response.await()) }
        runCurrent()
        val edited = store.editPage(original.token) { update("a", Row("a", "local")) }.loaded()
        response.complete(source().copy(items = rows("a", "b", "c", "server")))
        assertEquals(PageChange.Rejected(PageRejection.RevisionChanged(original.token, edited.token)), loading.await())
        assertEquals(PageLookup.Loaded(edited), store.page(PageId("first")))
        val refreshed = store.reconcilePage(edited.token, source()).loaded()
        assertEquals(PageChange.Rejected(PageRejection.RevisionChanged(edited.token, refreshed.token)),
            store.editPage(edited.token) { append(Row("late")) })
    }

    @Test
    fun reconcileReplaysLocalEditsOnFreshSourceAndReportsConflicts() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val edited = store.editPage(original.token) {
            update("a", Row("a", "local")); moveToStart("c"); append(Row("x"))
        }.loaded()
        val fresh = source().copy(items = rows("a", "b", "c", "server"), after = Cursor.At(150))
        val reconciled = store.reconcilePage(edited.token, fresh).loaded()
        assertEquals(persistentListOf(Row("c"), Row("a", "local"), Row("b"), Row("server"), Row("x")), reconciled.items)
        assertEquals(fresh, reconciled.source)
        assertEquals(edited.edits, reconciled.edits)
        assertEquals(edited.token.copy(revision = 2), reconciled.token)
        val before = store.state.value
        assertEquals(PageChange.Rejected(PageRejection.EditsConflict(PageRejection.UnknownItem("a"))),
            store.reconcilePage(reconciled.token, fresh.copy(items = rows("b", "c"))))
        assertEquals(before, store.state.value)
        assertEquals(PageChange.Rejected(PageRejection.EditsConflict(PageRejection.DuplicateItems(persistentSetOf("x")))),
            store.reconcilePage(reconciled.token, fresh.copy(items = rows("a", "b", "c", "x"))))
        assertEquals(before, store.state.value)
    }

    @Test
    fun frozenBuilderCannotMutateCommittedStateAndEmptyEditKeepsToken() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val captured = CompletableDeferred<PageEditor<Row>>()
        val edited = store.editPage(original.token) { captured.complete(this); append(Row("x")) }.loaded()
        captured.await().append(Row("uncommitted"))
        assertEquals(rows("a", "b", "c", "x"), edited.items)
        assertEquals(1, edited.edits.operations.size)
        assertEquals(PageLookup.Loaded(edited), store.page(PageId("first")))
        assertEquals(PageChange.Applied(edited), store.editPage(edited.token) {})
        assertEquals(edited.token, store.page(PageId("first")).loaded().token)
    }

    @Test
    fun positionsUseCurrentTransactionOrderIncludingSelfMoves() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val edited = store.editPage(original.token) {
            prepend(Row("p")); insertBefore("c", Row("x")); moveAfter("a", "c")
            moveToEnd("p"); moveBefore("c", "c"); moveAfter("x", "x")
        }.loaded()
        assertEquals(rows("b", "x", "c", "a", "p"), edited.items)
        assertEquals(original.source, edited.source)
    }

    @Test
    fun competingTransactionsFromSameRevisionHaveExactlyOneWinner() = runTest {
        val store = PageStore<Int, Row>(Row::id)
        val original = store.addPage(store.state.value.generation, source()).loaded()
        val first = async { store.editPage(original.token) { append(Row("first")) } }
        val second = async { store.editPage(original.token) { append(Row("second")) } }
        val winner = first.await().loaded()
        assertEquals(PageChange.Rejected(PageRejection.RevisionChanged(original.token, winner.token)), second.await())
        assertEquals(rows("a", "b", "c", "first"), winner.items)
        assertEquals(PageLookup.Loaded(winner), store.page(PageId("first")))
    }

    private fun <K : Any, T : Any> PageChange<K, T>.loaded(): PageSnapshot<K, T> = when (this) {
        is PageChange.Applied -> page
        is PageChange.Rejected -> error("Unexpected rejection: $reason")
    }
    private fun <K : Any, T : Any> PageLookup<K, T>.loaded(): PageSnapshot<K, T> = when (this) {
        is PageLookup.Loaded -> page
        PageLookup.Missing -> error("Missing page")
    }
}
