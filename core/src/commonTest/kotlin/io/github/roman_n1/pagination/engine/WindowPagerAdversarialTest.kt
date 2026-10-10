package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig

@OptIn(ExperimentalCoroutinesApi::class)
class WindowPagerAdversarialTest {
    @Test
    fun oldTopCompletionCannotSettleAnInFlightJump() =
        runTest {
            assertJumpRemainsLoading(WindowIntent.LoadTop)
        }

    @Test
    fun oldBottomCompletionCannotSettleAnInFlightJump() =
        runTest {
            assertJumpRemainsLoading(WindowIntent.LoadBottom)
        }

    @Test
    fun closingDuringJumpDoesNotPublishAnErrorAfterClose() =
        runTest {
            assertCloseDuringWindowLoad(WindowIntent.JumpTo("50"))
        }

    @Test
    fun closingDuringJumpToNewestDoesNotPublishAnErrorAfterClose() =
        runTest {
            assertCloseDuringWindowLoad(WindowIntent.JumpToNewest)
        }

    @Test
    fun closingDuringLiveReconcileDoesNotPublishAnErrorAfterClose() =
        runTest {
            assertCloseDuringWindowLoad(WindowIntent.ReconcileLiveEdge)
        }

    @Test
    fun backgroundReconcileDoesNotLeaveStaleEdgeLoadingForever() =
        runTest {
            val source = GatedSource()
            val refreshed = CompletableDeferred<LoadResult<Int>>()
            source.backgroundReconcile = BackgroundReconcile { ReconcileResult.Updated(run { refreshed.await() }) }
            val pager = newPager(source)
            runCurrent()
            val old = source.pauseEdge()
            pager.dispatch(WindowIntent.LoadTop)
            runCurrent()
            refreshed.complete(page(50))
            runCurrent()
            old.complete(page(100))
            runCurrent()
            assertEquals(listOf(51, 50, 49), pager.state.value.items)
            assertEquals(LoadState.Idle, pager.state.value.loadStateTop)
            pager.close()
        }

    @Test
    fun staleTopFailureCannotPoisonTheWindowAfterJump() =
        runTest {
            assertStaleFailureIgnored(WindowIntent.LoadTop)
        }

    @Test
    fun staleBottomFailureCannotPoisonTheWindowAfterJump() =
        runTest {
            assertStaleFailureIgnored(WindowIntent.LoadBottom)
        }

    @Test
    fun staleTopSuccessCannotUnlockANewerTopRequest() =
        runTest {
            assertNewerLoadRemainsOwned(WindowIntent.LoadTop, failure = false)
        }

    @Test
    fun staleBottomSuccessCannotUnlockANewerBottomRequest() =
        runTest {
            assertNewerLoadRemainsOwned(WindowIntent.LoadBottom, failure = false)
        }

    @Test
    fun staleTopFailureCannotReplaceANewerTopLoadingState() =
        runTest {
            assertNewerLoadRemainsOwned(WindowIntent.LoadTop, failure = true)
        }

    @Test
    fun staleBottomFailureCannotReplaceANewerBottomLoadingState() =
        runTest {
            assertNewerLoadRemainsOwned(WindowIntent.LoadBottom, failure = true)
        }

    @Test
    fun closingDuringBootstrapDoesNotPublishAnErrorAfterClose() =
        runTest {
            val source = GatedSource()
            source.pauseInitial = Presence.Present(CompletableDeferred())
            val pager = newPager(source)
            runCurrent()
            val beforeClose = pager.state.value
            pager.close()
            runCurrent()
            assertEquals(beforeClose, pager.state.value)
        }

    @Test
    fun closingDuringTopLoadDoesNotPublishAnErrorAfterClose() =
        runTest {
            assertCloseDuringLoad(WindowIntent.LoadTop)
        }

    @Test
    fun closingDuringBottomLoadDoesNotPublishAnErrorAfterClose() =
        runTest {
            assertCloseDuringLoad(WindowIntent.LoadBottom)
        }

    @Test
    fun topPrefetchStormStartsOnlyOnePendingRequest() =
        runTest {
            assertPrefetchStorm(WindowIntent.LoadTop)
        }

    @Test
    fun bottomPrefetchStormStartsOnlyOnePendingRequest() =
        runTest {
            assertPrefetchStorm(WindowIntent.LoadBottom)
        }

    @Test
    fun closingOnePagerDoesNotCancelAnotherPagerOnTheSameScope() =
        runTest {
            val first = newPager(GatedSource())
            val second = newPager(GatedSource())
            runCurrent()
            first.close()
            second.dispatch(WindowIntent.JumpTo("50"))
            runCurrent()
            assertEquals(listOf(51, 50, 49), second.state.value.items)
            assertEquals(Focus.Item("50"), second.state.value.pendingFocus)
            second.close()
        }

    private fun TestScope.assertJumpRemainsLoading(direction: WindowIntent) {
        val source = GatedSource()
        val pager = newPager(source)
        runCurrent()
        val old = source.pauseEdge()
        pager.dispatch(direction)
        runCurrent()
        source.pauseAround = Presence.Present(CompletableDeferred())
        pager.dispatch(WindowIntent.JumpTo("50"))
        runCurrent()
        old.complete(page(100))
        runCurrent()
        assertEquals(LoadState.Loading, edgeState(pager, direction))
        source.pauseAround.getOrElse { error("around gate not set") }.complete(page(50))
        runCurrent()
        assertEquals(listOf(51, 50, 49), pager.state.value.items)
        pager.close()
    }

    private fun TestScope.assertCloseDuringWindowLoad(intent: WindowIntent) {
        val source = GatedSource()
        val pager = newPager(source)
        runCurrent()
        source.pauseInitial = Presence.Present(CompletableDeferred())
        source.pauseAround = Presence.Present(CompletableDeferred())
        pager.dispatch(intent)
        runCurrent()
        assertEquals(LoadState.Loading, pager.state.value.loadStateBottom)
        val beforeClose = pager.state.value
        pager.close()
        runCurrent()
        assertEquals(beforeClose, pager.state.value)
    }

    private fun TestScope.assertStaleFailureIgnored(direction: WindowIntent) {
        val source = GatedSource()
        val pager = newPager(source)
        runCurrent()
        val old = source.pauseEdge()
        pager.dispatch(direction)
        runCurrent()
        pager.dispatch(WindowIntent.JumpTo("50"))
        runCurrent()
        val afterJump = pager.state.value
        old.completeExceptionally(IllegalStateException("old page failed"))
        runCurrent()
        assertEquals(afterJump, pager.state.value, "old segment cannot change the new segment")
        pager.close()
    }

    private fun TestScope.assertNewerLoadRemainsOwned(
        direction: WindowIntent,
        failure: Boolean,
    ) {
        val source = GatedSource()
        val pager = newPager(source)
        runCurrent()
        val old = source.pauseEdge()
        pager.dispatch(direction)
        runCurrent()
        pager.dispatch(WindowIntent.JumpTo("50"))
        runCurrent()
        val current = source.pauseEdge()
        pager.dispatch(direction)
        runCurrent()
        if (failure) old.completeExceptionally(IllegalStateException("obsolete")) else old.complete(page(100))
        runCurrent()
        assertEquals(LoadState.Loading, edgeState(pager, direction), "old completion cannot settle the new request")
        repeat(32) { pager.dispatch(direction) }
        runCurrent()
        assertEquals(2, source.edgeCalls, "loading must continue excluding duplicate prefetch")
        current.complete(page(if (direction == WindowIntent.LoadTop) 47 else 53))
        runCurrent()
        assertEquals(LoadState.Idle, edgeState(pager, direction))
        assertTrue(
            pager.state.value.items
                .all { it < 60 },
            "obsolete page must not be merged",
        )
        pager.close()
    }

    private fun TestScope.assertCloseDuringLoad(direction: WindowIntent) {
        val source = GatedSource()
        val pager = newPager(source)
        runCurrent()
        source.pauseEdge()
        pager.dispatch(direction)
        runCurrent()
        val beforeClose = pager.state.value
        pager.close()
        runCurrent()
        assertEquals(beforeClose, pager.state.value)
    }

    private fun TestScope.assertPrefetchStorm(direction: WindowIntent) {
        val source = GatedSource()
        val pager = newPager(source)
        runCurrent()
        val response = source.pauseEdge()
        repeat(128) { pager.dispatch(direction) }
        runCurrent()
        assertEquals(1, source.edgeCalls)
        response.complete(page(if (direction == WindowIntent.LoadTop) 97 else 103))
        runCurrent()
        assertEquals(LoadState.Idle, edgeState(pager, direction))
        pager.close()
    }

    private fun TestScope.newPager(source: GatedSource) =
        WindowPager(
            dataSource = source,
            config = WindowPagingConfig(pageSize = 3, prefetchDistance = 1, maxCachedItems = 30),
            initialPosition = InitialPosition.Around("100"),
            scope = PagingScope.Borrowed(backgroundScope),
        )

    private fun edgeState(
        pager: WindowPager<Int>,
        direction: WindowIntent,
    ): LoadState = if (direction == WindowIntent.LoadTop) pager.state.value.loadStateTop else pager.state.value.loadStateBottom

    private class GatedSource : WindowDataSource<Int> {
        override val comparator: Comparator<Int> = compareByDescending { it }
        override val itemId: (Int) -> String = Int::toString
        var pauseInitial: Presence<CompletableDeferred<LoadResult<Int>>> = Presence.Absent
        var pauseAround: Presence<CompletableDeferred<LoadResult<Int>>> = Presence.Absent
        var backgroundReconcile: BackgroundReconcile<Int> = BackgroundReconcile { ReconcileResult.Unchanged }
        private val edges = ArrayDeque<CompletableDeferred<LoadResult<Int>>>()
        var edgeCalls = 0

        fun pauseEdge() = CompletableDeferred<LoadResult<Int>>().also { edges.addLast(it) }

        override suspend fun load(params: LoadParams): LoadResult<Int> =
            when (params) {
                is LoadParams.Initial -> {
                    pauseInitial.fold({ page(100).copy(backgroundReconcile = backgroundReconcile) }) { it.await() }
                }

                is LoadParams.Around -> {
                    pauseAround.fold({ page(params.anchorId.toInt()) }) { it.await() }
                }

                is LoadParams.Before, is LoadParams.After -> {
                    edgeCalls++
                    check(edges.isNotEmpty()) { "unexpected duplicate edge request" }
                    edges.removeFirst().await()
                }
            }

        // suspend снят вслед за интерфейсом: шаг A-10 кампании ADR-0022 убрал модификатор с
        // PagingSource.observeChanges (SuspendFunWithFlowReturnType — сборка потока не приостановка).
        // Тест приехал из main новым файлом, конфликта git не было, поймала компиляция.
        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<Int>> = emptyFlow()

        override fun getSortKey(item: Int): Long = item.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) = Unit
    }

    private companion object {
        fun page(center: Int) =
            LoadResult(
                items = listOf(center + 1, center, center - 1),
                hasMore = true,
                hasMoreTop = BoundaryHint.of(true),
                hasMoreBottom = BoundaryHint.of(true),
            )
    }
}
