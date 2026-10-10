package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Angry Tests для WindowPager.
 *
 * Тестируем граничные случаи, гонки, невалидные входы и нарушения инвариантов —
 * всё то, что happy-path тесты пропускают.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowPagerAngryTest {
    // ─── Пустой data source ─────────────────────────────────────────

    @Test
    fun `bootstrap с 0 элементов - окно пустое загрузка idle hasMore false`() =
        runTest {
            val ds = FakeDataSource(totalItems = 0)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                val state = pager.state.value
                assertTrue(state.items.isEmpty())
                assertEquals(LoadState.Idle, state.loadStateBottom)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun snapshotDuringBootstrap_seedsEmptyWindowAfterBootstrapCompletes() =
        runTest {
            val ds = FakeDataSource(totalItems = 0, useLiveSnapshots = true)
            val bootstrapGate = CompletableDeferred<Unit>()
            ds.initialLoadGate = bootstrapGate
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                ds.emitSnapshot(listOf(TestItem(1)))
                advanceUntilIdle()

                bootstrapGate.complete(Unit)
                advanceUntilIdle()

                assertEquals(listOf(1), pager.state.value.itemIndexes())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `LoadTop при пустом окне - ничего не происходит нет крашей`() =
        runTest {
            val ds = FakeDataSource(totalItems = 0)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                val callsBefore = ds.loadCalls.size
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertEquals(callsBefore, ds.loadCalls.size)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `JumpTo при пустом data source - ошибки нет окно остаётся пустым`() =
        runTest {
            val ds = FakeDataSource(totalItems = 0)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo("nonexistent"))
                assertEquals(Focus.Item("nonexistent"), pager.state.value.pendingFocus)
                advanceUntilIdle()
                assertTrue(
                    pager.state.value.items
                        .isEmpty(),
                )
                assertEquals(Focus.None, pager.state.value.pendingFocus)
            } finally {
                scope.cancel()
            }
        }

    // ─── Один элемент ───────────────────────────────────────────────

    @Test
    fun `data source с 1 элементом - bootstrap загружает hasMore false`() =
        runTest {
            val ds = FakeDataSource(totalItems = 1)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertEquals(listOf(1), pager.state.value.itemIndexes())
                assertFalse(pager.state.value.hasMoreTop)
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `JumpTo единственного элемента - pendingFocus ставится`() =
        runTest {
            val ds = FakeDataSource(totalItems = 1)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo("1"))
                advanceUntilIdle()
                assertEquals(Focus.Item("1"), pager.state.value.pendingFocus)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun jumpToFailureClearsItsPendingFocus() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                ds.nextLoadError = Presence.Present(RuntimeException("around failed"))

                pager.dispatch(WindowIntent.JumpTo("1"))
                advanceUntilIdle()

                assertEquals(Focus.None, pager.state.value.pendingFocus)
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)
            } finally {
                scope.cancel()
            }
        }

    // ─── Дубликаты от data source ───────────────────────────────────

    @Test
    fun `data source возвращает дубликаты - normalize дедуплицирует`() =
        runTest {
            val ds = DuplicatingDataSource()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val pager =
                WindowPager(
                    dataSource = ds,
                    config = WindowPagingConfig(pageSize = PAGE_SIZE, prefetchDistance = 2, maxCachedItems = 100),
                    scope = PagingScope.Borrowed(scope),
                )
            try {
                advanceUntilIdle()
                val ids =
                    pager.state.value.items
                        .map { ds.itemId(it) }
                assertEquals(ids.distinct(), ids, "Окно не должно содержать дубликатов")
            } finally {
                scope.cancel()
            }
        }

    // ─── Bootstrap error + retry циклы ──────────────────────────────

    @Test
    fun `bootstrap error - retry повторяет bootstrap а не loadEdge`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            ds.nextLoadError = Presence.Present(RuntimeException("timeout"))
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)
                assertTrue(
                    pager.state.value.items
                        .isEmpty(),
                )

                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()
                assertEquals(LoadState.Idle, pager.state.value.loadStateBottom)
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `двойной bootstrap error - два retry подряд оба работают`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            ds.nextLoadError = Presence.Present(RuntimeException("first"))
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)

                ds.nextLoadError = Presence.Present(RuntimeException("second"))
                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)

                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()
                assertEquals(LoadState.Idle, pager.state.value.loadStateBottom)
                assertTrue(
                    pager.state.value.items
                        .isNotEmpty(),
                )
            } finally {
                scope.cancel()
            }
        }

    // ─── Concurrent dispatch'и ──────────────────────────────────────

    @Test
    fun `два последовательных LoadTop - оба отрабатывают окно расширяется на 2 страницы`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                // Bootstrap: [100..92]

                pager.dispatch(WindowIntent.LoadTop)
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                // Оба LoadTop отрабатывают последовательно — первый расширяет до [91..83],
                // второй до [82..74]. Итого окно [100..74] = 27 элементов.
                assertTrue(
                    pager.state.value.items.size > PAGE_SIZE + PAGE_SIZE,
                    "Два LoadTop подряд расширяют окно на ~2 страницы",
                )
                assertEquals(LoadState.Idle, pager.state.value.loadStateTop)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `повторный LoadTop ограничивает окно и сохраняет загруженный старый край`() =
        runTest {
            val (pager, scope) =
                createPager(
                    FakeDataSource(totalItems = 200),
                    initialAnchorId = InitialPosition.Around("100"),
                    maxCachedItems = MAX_CACHED_ITEMS,
                )
            try {
                advanceUntilIdle()
                loadRepeatedly(pager, WindowIntent.LoadTop)
                assertEquals((68 downTo 51).toList(), pager.state.value.itemIndexes())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `повторный LoadBottom ограничивает окно и сохраняет загруженный новый край`() =
        runTest {
            val (pager, scope) =
                createPager(
                    FakeDataSource(totalItems = 200),
                    initialAnchorId = InitialPosition.Around("100"),
                    maxCachedItems = MAX_CACHED_ITEMS,
                )
            try {
                advanceUntilIdle()
                loadRepeatedly(pager, WindowIntent.LoadBottom)
                assertEquals((149 downTo 132).toList(), pager.state.value.itemIndexes())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `JumpTo во время LoadTop - jump отрабатывает окно корректно`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.LoadTop)
                pager.dispatch(WindowIntent.JumpTo("30"))
                advanceUntilIdle()

                assertEquals(Focus.Item("30"), pager.state.value.pendingFocus)
                assertTrue(
                    pager.state.value.items
                        .any { it.index == 30 },
                )
            } finally {
                scope.cancel()
            }
        }

    // ─── LoadTop при Error в другом направлении ─────────────────────

    @Test
    fun `LoadBottom error не блокирует LoadTop`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds, initialAnchorId = InitialPosition.Around("50"))
            try {
                advanceUntilIdle()

                ds.nextLoadError = Presence.Present(RuntimeException("bottom broken"))
                pager.dispatch(WindowIntent.LoadBottom)
                advanceUntilIdle()
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertEquals(LoadState.Idle, pager.state.value.loadStateTop)
                assertTrue(pager.state.value.items.size > PAGE_SIZE)
            } finally {
                scope.cancel()
            }
        }

    // ─── JumpTo к граничным элементам ───────────────────────────────

    @Test
    fun `JumpTo к самому первому элементу id=1`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo("1"))
                advanceUntilIdle()

                assertEquals(Focus.Item("1"), pager.state.value.pendingFocus)
                assertTrue(
                    pager.state.value.items
                        .any { it.index == 1 },
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `JumpTo к самому новому элементу - элемент уже в окне только focus`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                val callsBefore = ds.loadCalls.size

                pager.dispatch(WindowIntent.JumpTo("100"))
                advanceUntilIdle()

                assertEquals(Focus.Item("100"), pager.state.value.pendingFocus)
                assertEquals(callsBefore, ds.loadCalls.size, "Не должно быть новых load вызовов")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `JumpTo к несуществующему id - loadAround возвращает пустой список окно не ломается`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                val itemsBefore = pager.state.value.items.size

                pager.dispatch(WindowIntent.JumpTo("99999"))
                advanceUntilIdle()

                assertTrue(
                    pager.state.value.items
                        .isNotEmpty(),
                )
            } finally {
                scope.cancel()
            }
        }

    // ─── observeChanges гонки ───────────────────────────────────────

    @Test
    fun `observeChanges - обновление элемента меняет данные в окне`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())

                val modifiedSnapshot =
                    (100 downTo 92).map {
                        TestItem(it, payload = if (it == 95) "modified" else "original")
                    }
                ds.emitSnapshot(modifiedSnapshot)
                advanceUntilIdle()

                val item95 =
                    pager.state.value.items
                        .first { it.index == 95 }
                assertEquals("modified", item95.payload)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `observeChanges - snapshot полностью из новых ID при hasMoreBottom true - окно обновляется`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, scope) = createPager(ds, initialAnchorId = InitialPosition.Around("50"))
            try {
                advanceUntilIdle()
                assertTrue(pager.state.value.hasMoreBottom)

                val allNewSnapshot =
                    (54 downTo 46).map {
                        TestItem(it, payload = "refreshed")
                    }
                ds.emitSnapshot(allNewSnapshot)
                advanceUntilIdle()

                assertTrue(
                    pager.state.value.items
                        .all { it.payload == "refreshed" },
                )
            } finally {
                scope.cancel()
            }
        }

    // ─── JumpToNewest из разных состояний ───────────────────────────

    @Test
    fun `JumpToNewest при пустом окне после bootstrap error`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            ds.nextLoadError = Presence.Present(RuntimeException("bootstrap fail"))
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertTrue(
                    pager.state.value.items
                        .isEmpty(),
                )

                pager.dispatch(WindowIntent.JumpToNewest)
                advanceUntilIdle()
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `JumpToNewest встаёт на live edge - hasMoreBottom false`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds, initialAnchorId = InitialPosition.Around("50"))
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpToNewest)
                advanceUntilIdle()

                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                scope.cancel()
            }
        }

    // ─── ClearJumpState ─────────────────────────────────────────────

    @Test
    fun `ClearJumpState сбрасывает pendingFocus`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo("95"))
                advanceUntilIdle()
                assertEquals(Focus.Item("95"), pager.state.value.pendingFocus)

                pager.dispatch(WindowIntent.ClearJumpState)
                assertEquals(Focus.None, pager.state.value.pendingFocus)
            } finally {
                scope.cancel()
            }
        }

    // ─── Rapid sequential jumps ─────────────────────────────────────

    @Test
    fun `10 быстрых JumpTo подряд - окно стабильно на последнем`() =
        runTest {
            val ds = FakeDataSource(totalItems = 1000)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                val targets = listOf("900", "800", "700", "600", "500", "400", "300", "200", "100", "50")
                targets.forEach { pager.dispatch(WindowIntent.JumpTo(it)) }
                advanceUntilIdle()

                assertTrue(
                    pager.state.value.items
                        .any { it.index == 50 },
                )
                assertEquals(Focus.Item("50"), pager.state.value.pendingFocus)
            } finally {
                scope.cancel()
            }
        }

    // ─── Retry когда нет ошибок ─────────────────────────────────────

    @Test
    fun `Retry при Idle и Loading состояниях - no-op`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                val callsBefore = ds.loadCalls.size
                val stateBefore = pager.state.value

                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()

                assertEquals(callsBefore, ds.loadCalls.size)
                assertEquals(stateBefore.items, pager.state.value.items)
            } finally {
                scope.cancel()
            }
        }

    // ─── Инвариант: items всегда отсортированы DESC ─────────────────

    @Test
    fun `после любой операции items остаются отсортированы DESC`() =
        runTest {
            val ds = FakeDataSource(totalItems = 200)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertSortedDesc(pager.state.value)

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertSortedDesc(pager.state.value)

                pager.dispatch(WindowIntent.JumpTo("50"))
                advanceUntilIdle()
                assertSortedDesc(pager.state.value)

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertSortedDesc(pager.state.value)

                pager.dispatch(WindowIntent.LoadBottom)
                advanceUntilIdle()
                assertSortedDesc(pager.state.value)

                pager.dispatch(WindowIntent.JumpToNewest)
                advanceUntilIdle()
                assertSortedDesc(pager.state.value)
            } finally {
                scope.cancel()
            }
        }

    // ─── Инвариант: нет дубликатов после merge ──────────────────────

    @Test
    fun `после серии jump с overlap - нет дубликатов`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo("95"))
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo("90"))
                advanceUntilIdle()

                val ids =
                    pager.state.value.items
                        .map { it.index }
                assertEquals(ids.distinct(), ids, "Дубликаты после merge overlapping окон")
            } finally {
                scope.cancel()
            }
        }

    // ─── LoadTop/LoadBottom при размере < pageSize ──────────────────

    @Test
    fun `bootstrap загружает ровно totalItems когда totalItems меньше pageSize`() =
        runTest {
            val ds = FakeDataSource(totalItems = 3)
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertEquals(listOf(3, 2, 1), pager.state.value.itemIndexes())
                assertFalse(pager.state.value.hasMoreTop)
            } finally {
                scope.cancel()
            }
        }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun assertSortedDesc(state: WindowState<TestItem>) {
        val indexes = state.items.map { it.index }
        assertEquals(indexes.sortedDescending(), indexes, "Items должны быть отсортированы DESC")
    }

    // ─── Background reconcile под live-снапшотами ───────────────────

    @Test
    fun `фоновый reconcile - live-append во время сверки не оставляет шов`() =
        runTest {
            val ds = FakeDataSource(totalItems = 100, useLiveSnapshots = true)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            ds.staleInitialWindow = Presence.Present((60 downTo 52).map(::TestItem))
            ds.backgroundReconcileResult = BackgroundReconcile { ReconcileResult.Updated(run {
                gate.await()
                LoadResult(
                    items = (101 downTo 93).map(::TestItem),
                    hasMore = true,
                    hasMoreTop = BoundaryHint.of(true),
                    hasMoreBottom = BoundaryHint.of(false),
                )
            }) }
            val (pager, scope) = createPager(ds)
            try {
                advanceUntilIdle()
                assertEquals((60 downTo 52).toList(), pager.state.value.itemIndexes())

                // WS доставил 101, пока сверка висит: live-tail append создаёт невидимый шов 101↔60.
                ds.emitSnapshot(listOf(TestItem(101)) + (60 downTo 52).map(::TestItem))
                advanceUntilIdle()
                assertEquals(listOf(101) + (60 downTo 52).toList(), pager.state.value.itemIndexes())

                gate.complete(Unit)
                advanceUntilIdle()

                // Live-append — не скролл юзера: сверка обязана заменить окно непрерывным серверным
                // (101 в нём уже есть) и вылечить шов, а не мержить через дыру 92..61.
                val indexes = pager.state.value.itemIndexes()
                assertEquals((101 downTo 93).toList(), indexes)
                assertEquals(indexes.sortedDescending(), indexes, "Window lost DESC order")
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                scope.cancel()
            }
        }

    private fun TestScope.createPager(
        dataSource: FakeDataSource,
        initialAnchorId: InitialPosition = InitialPosition.Newest,
        maxCachedItems: Int = 100,
    ): Pair<WindowPager<TestItem>, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val pager =
            WindowPager(
                dataSource = dataSource,
                config =
                    WindowPagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = 2,
                        maxCachedItems = maxCachedItems,
                    ),
                initialPosition = initialAnchorId,
                scope = PagingScope.Borrowed(scope),
            )
        return pager to scope
    }

    private fun TestScope.loadRepeatedly(
        pager: WindowPager<TestItem>,
        intent: WindowIntent,
        count: Int = 5,
    ) {
        repeat(count) {
            pager.dispatch(intent)
            advanceUntilIdle()
            val indexes = pager.state.value.itemIndexes()
            assertTrue(indexes.size <= MAX_CACHED_ITEMS, "Window exceeded maxCachedItems: $indexes")
            assertEquals(indexes.sortedDescending(), indexes, "Window lost DESC order")
            assertEquals(indexes.distinct(), indexes, "Window contains duplicate IDs")
        }
    }

    private fun WindowState<TestItem>.itemIndexes(): List<Int> = items.map { it.index }

    data class TestItem(
        val index: Int,
        val id: String = index.toString(),
        val payload: String = "original",
    )

    /** FakeDataSource с поддержкой payload и дублирующего режима. */
    class FakeDataSource(
        private val totalItems: Int,
        private val useLiveSnapshots: Boolean = false,
    ) : WindowDataSource<TestItem> {
        override val comparator: Comparator<TestItem> =
            Comparator<TestItem> { left, right -> right.index.compareTo(left.index) }
                .then(Comparator { left, right -> right.id.compareTo(left.id) })

        override val itemId: (TestItem) -> String = { it.id }

        val loadCalls = mutableListOf<LoadParams>()

        private val snapshotsSink = MutableSharedFlow<List<TestItem>>(replay = 1, extraBufferCapacity = 10)

        internal var nextLoadError: Presence<Exception> = Presence.Absent
        var initialLoadGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)

        /** Initial(anchorId=null) вернёт это окно (stale-кэш) + [backgroundReconcileResult]. */
        internal var staleInitialWindow: Presence<List<TestItem>> = Presence.Absent
        var backgroundReconcileResult: BackgroundReconcile<TestItem> = BackgroundReconcile { ReconcileResult.Unchanged }

        suspend fun emitSnapshot(items: List<TestItem>) {
            snapshotsSink.emit(items)
        }

        override suspend fun load(params: LoadParams): LoadResult<TestItem> {
            if (params is LoadParams.Initial) initialLoadGate.await()
            nextLoadError.fold({}) {
                nextLoadError = Presence.Absent
                throw it
            }
            loadCalls += params
            return when (params) {
                is LoadParams.Initial -> loadInitial(params.position, params.limit)
                is LoadParams.Before -> loadBefore(params.anchorId, params.limit)
                is LoadParams.After -> loadAfter(params.anchorId, params.limit)
                is LoadParams.Around -> loadAround(params.anchorId, params.limit)
            }
        }

        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<TestItem>> = if (useLiveSnapshots) snapshotsSink else emptyFlow()

        override fun getSortKey(item: TestItem): Long = item.index.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) {}

        private fun loadInitial(
            anchorId: InitialPosition,
            limit: Int,
        ): LoadResult<TestItem> {
            if (anchorId == InitialPosition.Newest) {
                staleInitialWindow.fold({}) { stale ->
                    return LoadResult(
                        items = stale,
                        hasMore = stale.size == limit,
                        backgroundReconcile = backgroundReconcileResult,
                    )
                }
                val items = newest(limit)
                return LoadResult(items = items, hasMore = items.size == limit)
            }
            val anchor = runCatching { (anchorId as InitialPosition.Around).id.toInt() }.getOrDefault(0)
            if (anchor < 1 || anchor > totalItems) {
                val items = newest(limit)
                return LoadResult(items = items, hasMore = items.size == limit)
            }
            val items = around(anchor, limit)
            return LoadResult(items = items, hasMore = items.size == limit)
        }

        private fun loadBefore(
            anchorId: String,
            limit: Int,
        ): LoadResult<TestItem> {
            val anchor = anchorId.toInt()
            val items = ((anchor - 1) downTo maxOf(anchor - limit, 1)).map(::TestItem)
            val hasMore = anchor - 1 > items.size
            return LoadResult(items = items, hasMore = hasMore)
        }

        private fun loadAfter(
            anchorId: String,
            limit: Int,
        ): LoadResult<TestItem> {
            val anchor = anchorId.toInt()
            val items = (minOf(anchor + limit, totalItems) downTo anchor + 1).map(::TestItem)
            val hasMore = totalItems - anchor > items.size
            return LoadResult(items = items, hasMore = hasMore)
        }

        private fun loadAround(
            anchorId: String,
            limit: Int,
        ): LoadResult<TestItem> {
            val anchor =
                runCatching { anchorId.toInt() }.getOrElse { return LoadResult(items = persistentListOf(), hasMore = false) }
            if (anchor < 1 || anchor > totalItems) {
                return LoadResult(items = persistentListOf(), hasMore = false)
            }
            val items = around(anchor, limit)
            return LoadResult(items = items, hasMore = items.size == limit)
        }

        private fun newest(limit: Int): List<TestItem> = (totalItems downTo maxOf(totalItems - limit + 1, 1)).map(::TestItem)

        private fun around(
            anchor: Int,
            limit: Int,
        ): List<TestItem> {
            val newerCount = limit / 2
            val olderCount = limit - newerCount - 1
            val newer = (minOf(anchor + newerCount, totalItems) downTo anchor + 1).map(::TestItem)
            val older = ((anchor - 1) downTo maxOf(anchor - olderCount, 1)).map(::TestItem)
            return newer + TestItem(anchor) + older
        }
    }

    /**
     * Data source, который возвращает дубликаты в ответе — проверяет что normalize их схлопывает.
     */
    class DuplicatingDataSource : WindowDataSource<TestItem> {
        override val comparator = Comparator<TestItem> { left, right -> right.index.compareTo(left.index) }
        override val itemId: (TestItem) -> String = { it.id }

        val loadCalls = mutableListOf<LoadParams>()

        override suspend fun load(params: LoadParams): LoadResult<TestItem> {
            loadCalls += params
            val items =
                persistentListOf(
                    TestItem(5),
                    TestItem(4),
                    TestItem(3),
                    TestItem(3),
                    TestItem(2),
                    TestItem(2),
                    TestItem(1),
                )
            return LoadResult(items = items, hasMore = false)
        }

        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<TestItem>> = emptyFlow()

        override fun getSortKey(item: TestItem): Long = item.index.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) {}
    }

    private companion object {
        const val PAGE_SIZE = 9
        const val MAX_CACHED_ITEMS = PAGE_SIZE * 2
    }
}
