package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Многопоточный стресс-тест WindowPager на РЕАЛЬНОМ [Dispatchers.Default] (не кооперативный
 * StandardTestDispatcher). Страж корня К2 / находки C5: edge-load и live-snapshot конкурируют
 * на разных потоках, и apply результата загрузки (merge окна + выставление loadState/hasMore)
 * должен быть атомарным.
 *
 * Проверяем СТРУКТУРНЫЕ инварианты (сортировка DESC, отсутствие дубликатов, loadState не завис),
 * а не точное содержимое окна — содержимое зависит от тайминга гонки и было бы флейки.
 * Консистентность держится тогда и только тогда, когда apply неделим: если кто-то снова разорвёт
 * критическую секцию в loadEdge, конкурентный observeChanges вклинится в промежуточное состояние
 * и порвёт окно — этот тест начнёт падать.
 *
 * jvmTest (не commonTest): runBlocking + многопоточный Dispatchers.Default есть только на JVM/Native.
 */
class WindowPagerConcurrencyStressTest {
    @Test
    fun observerBeforeIdSync_keepsAnchoredBootstrapWindow() {
        val dispatcher = ManualCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val dataSource = AnchorRaceDataSource()
        val pager =
            WindowPager(
                dataSource = dataSource,
                config = WindowPagingConfig(pageSize = 1, prefetchDistance = 1, maxCachedItems = 20),
                initialPosition = InitialPosition.Around(ANCHOR_ID),
                scope = PagingScope.Borrowed(scope),
            )
        try {
            dispatcher.runTask(0) // bootstrap
            assertEquals(
                listOf(ANCHOR_ID),
                pager.state.value.items
                    .map(dataSource.itemId),
            )

            // init enqueues sync before observer; run observer first to reproduce the adverse schedule.
            dispatcher.runTask(1)
            dispatcher.runUntilIdle()

            assertEquals(
                listOf(ANCHOR_ID),
                pager.state.value.items
                    .map(dataSource.itemId),
            )
        } finally {
            pager.close()
            scope.cancel()
            dispatcher.runUntilIdle()
        }
    }

    @Test
    fun `конкурентные edge-load и live-snapshot - окно остаётся консистентным`() =
        runBlocking {
            repeat(ITERATIONS) { iteration ->
                val ds = WindowPagerAngryTest.FakeDataSource(totalItems = 500, useLiveSnapshots = true)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val pager =
                    WindowPager(
                        dataSource = ds,
                        config = WindowPagingConfig(pageSize = 20, prefetchDistance = 4, maxCachedItems = 200),
                        initialPosition = InitialPosition.Around("250"),
                        scope = PagingScope.Borrowed(scope),
                    )
                try {
                    pager.settle()

                    // Шторм: три edge-load'а и live-snapshot будят конкурирующие корутины на Default —
                    // loadEdge×3 + observeChanges гонятся за _state одновременно.
                    pager.dispatch(WindowIntent.LoadTop)
                    pager.dispatch(WindowIntent.LoadBottom)
                    pager.dispatch(WindowIntent.LoadTop)
                    val current = pager.state.value.items
                    if (current.isNotEmpty()) {
                        ds.emitSnapshot(current.map { it.copy(payload = "live$iteration") })
                    }

                    pager.settle()

                    val state = pager.state.value
                    assertEquals(
                        state.items.sortedWith(ds.comparator),
                        state.items,
                        "DESC-инвариант нарушен (iter=$iteration): порванный порядок окна",
                    )
                    val ids = state.items.map { ds.itemId(it) }
                    assertEquals(ids.distinct(), ids, "дубликаты в окне (iter=$iteration)")
                    assertTrue(
                        state.loadStateTop !is LoadState.Loading,
                        "loadStateTop завис в Loading (iter=$iteration)",
                    )
                    assertTrue(
                        state.loadStateBottom !is LoadState.Loading,
                        "loadStateBottom завис в Loading (iter=$iteration)",
                    )
                } finally {
                    scope.cancel()
                }
            }
        }

    /**
     * Ждёт стабилизации состояния: loadState idle в обоих направлениях И снимок не меняется
     * несколько опросов подряд. Виртуального времени на Dispatchers.Default нет — поллим.
     */
    private suspend fun WindowPager<WindowPagerAngryTest.TestItem>.settle(timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            var stableStreak = 0
            var prev: Presence<WindowState<WindowPagerAngryTest.TestItem>> = Presence.Absent
            while (stableStreak < STABLE_STREAK) {
                val snapshot = state.value
                val idle =
                    snapshot.loadStateTop !is LoadState.Loading &&
                        snapshot.loadStateBottom !is LoadState.Loading
                if (idle && Presence.Present(snapshot) == prev) stableStreak++ else stableStreak = 0
                prev = Presence.Present(snapshot)
                delay(POLL_MS)
            }
        }
    }

    private class AnchorRaceDataSource : WindowDataSource<WindowPagerAngryTest.TestItem> {
        override val comparator = Comparator<WindowPagerAngryTest.TestItem> { left, right -> right.index.compareTo(left.index) }
        override val itemId: (WindowPagerAngryTest.TestItem) -> String = { it.id }

        override suspend fun load(params: LoadParams) =
            when (params) {
                is LoadParams.Initial -> {
                    LoadResult(
                        items = listOf(WindowPagerAngryTest.TestItem(ANCHOR_ID.toInt())),
                        hasMore = true,
                        hasMoreTop = BoundaryHint.of(true),
                        hasMoreBottom = BoundaryHint.of(true),
                    )
                }

                else -> {
                    error("Unexpected load: $params")
                }
            }

        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<WindowPagerAngryTest.TestItem>> =
            flow {
                val items =
                    if (windowIds.value.isEmpty() && observeLiveTail.value) {
                        listOf(WindowPagerAngryTest.TestItem(NEWEST_ID.toInt()))
                    } else {
                        listOf(WindowPagerAngryTest.TestItem(ANCHOR_ID.toInt()))
                    }
                emit(items)
            }

        override fun getSortKey(item: WindowPagerAngryTest.TestItem): Long = item.index.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) = Unit
    }

    private class ManualCoroutineDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks += block
        }

        fun runTask(index: Int) {
            tasks.removeAt(index).run()
        }

        fun runUntilIdle() {
            while (tasks.isNotEmpty()) runTask(0)
        }
    }

    private companion object {
        const val ANCHOR_ID = "250"
        const val NEWEST_ID = "500"
        const val ITERATIONS = 200
        const val STABLE_STREAK = 3
        const val POLL_MS = 10L
    }
}
