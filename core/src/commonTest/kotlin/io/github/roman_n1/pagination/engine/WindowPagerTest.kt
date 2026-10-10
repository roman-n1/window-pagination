package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

@OptIn(ExperimentalCoroutinesApi::class)
class WindowPagerTest {
    // ─── JumpTo ──────────────────────────────────────────────────

    @Test
    fun `jump к элементу в окне - только pendingFocus без загрузки`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "97"))
                advanceUntilIdle()

                assertEquals(
                    listOf(100, 99, 98, 97, 96, 95, 94, 93, 92),
                    pager.state.value.itemIndexes(),
                )
                assertEquals(Focus.Item("97"), pager.state.value.pendingFocus)
                assertEquals(
                    listOf<LoadParams>(LoadParams.Initial(position = InitialPosition.Newest, limit = PAGE_SIZE)),
                    dataSource.loadCalls,
                )
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `jump с overlap - merge окон в один сегмент`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "90"))
                advanceUntilIdle()

                assertEquals((100 downTo 86).toList(), pager.state.value.itemIndexes())
                assertEquals(Focus.Item("90"), pager.state.value.pendingFocus)
                assertEquals(
                    listOf(
                        LoadParams.Initial(position = InitialPosition.Newest, limit = PAGE_SIZE),
                        LoadParams.Around(anchorId = "90", limit = PAGE_SIZE),
                    ),
                    dataSource.loadCalls,
                )
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `jump без overlap - замена окна старый сегмент отбрасывается`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "20"))
                advanceUntilIdle()

                assertEquals(
                    listOf(24, 23, 22, 21, 20, 19, 18, 17, 16),
                    pager.state.value.itemIndexes(),
                )
                assertEquals(Focus.Item("20"), pager.state.value.pendingFocus)
                assertTrue(100 !in pager.state.value.itemIndexes())
                assertEquals(
                    listOf(
                        LoadParams.Initial(position = InitialPosition.Newest, limit = PAGE_SIZE),
                        LoadParams.Around(anchorId = "20", limit = PAGE_SIZE),
                    ),
                    dataSource.loadCalls,
                )
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `серия дальних jump - остаётся только последнее окно LoadBottom грузит от него`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "70"))
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo(itemId = "40"))
                advanceUntilIdle()
                pager.dispatch(WindowIntent.JumpTo(itemId = "10"))
                advanceUntilIdle()

                assertEquals(
                    listOf(14, 13, 12, 11, 10, 9, 8, 7, 6),
                    pager.state.value.itemIndexes(),
                )

                pager.dispatch(WindowIntent.LoadBottom)
                advanceUntilIdle()

                assertEquals(
                    LoadParams.After(anchorId = "14", anchorSortKey = 14L, limit = PAGE_SIZE),
                    dataSource.loadCalls.last(),
                )
                assertEquals((23 downTo 6).toList(), pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `loadEdge кладёт canonicalId источника в Before и After`() =
        runTest {
            // Источник с двойной идентичностью: itemId — стабильный UI-ключ, canonicalId — PK.
            // Движок обязан донести PK до LoadParams, а не заставлять источник восстанавливать его.
            val dataSource =
                FakeWindowDataSource(
                    totalItems = 100,
                    canonicalIdFor = { "pk-${it.index}" },
                )
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "50"))
                advanceUntilIdle()

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertEquals(
                    LoadParams.Before(
                        anchorId = "46",
                        anchorSortKey = 46L,
                        limit = PAGE_SIZE,
                        anchorCanonicalId = "pk-46",
                    ),
                    dataSource.loadCalls.last(),
                )

                pager.dispatch(WindowIntent.LoadBottom)
                advanceUntilIdle()
                assertEquals(
                    LoadParams.After(
                        anchorId = "54",
                        anchorSortKey = 54L,
                        limit = PAGE_SIZE,
                        anchorCanonicalId = "pk-54",
                    ),
                    dataSource.loadCalls.last(),
                )
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── hasMore после jump ───────────────────────────────────────

    @Test
    fun `jump всегда ставит hasMore true в обоих направлениях`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Jump без overlap
                pager.dispatch(WindowIntent.JumpTo(itemId = "20"))
                advanceUntilIdle()

                assertTrue(pager.state.value.hasMoreTop, "hasMoreTop after jump without overlap")
                assertTrue(pager.state.value.hasMoreBottom, "hasMoreBottom after jump without overlap")

                // Jump с overlap (текущее окно [24..16], прыгаем к 28 -> around [32..24] пересекается на 24)
                pager.dispatch(WindowIntent.JumpTo(itemId = "28"))
                advanceUntilIdle()

                assertTrue(pager.state.value.hasMoreTop, "hasMoreTop after jump with overlap")
                assertTrue(pager.state.value.hasMoreBottom, "hasMoreBottom after jump with overlap")
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `jump у края данных - hasMore true скролл потом уточнит границу`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Jump к элементу у самого начала данных - loadAround вернёт < pageSize
                pager.dispatch(WindowIntent.JumpTo(itemId = "3"))
                advanceUntilIdle()

                // around(3, 9): newer=[7,6,5,4], older=[2,1] -> 7 элементов (< PAGE_SIZE=9)
                assertEquals(listOf(7, 6, 5, 4, 3, 2, 1), pager.state.value.itemIndexes())
                assertTrue(
                    pager.state.value.hasMoreTop,
                    "hasMoreTop despite loadAround returning < pageSize",
                )
                assertTrue(
                    pager.state.value.hasMoreBottom,
                    "hasMoreBottom despite loadAround returning < pageSize",
                )

                // Проверяем что LoadTop реально работает (старых элементов нет, но pager пробует)
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                // loadBefore("1", 9) -> пустой результат -> hasMoreTop = false
                assertFalse(pager.state.value.hasMoreTop)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `jump to newest заменяет окно на live edge`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource, initialAnchorId = InitialPosition.Around("60"))
            try {
                advanceUntilIdle()

                assertEquals(
                    listOf(64, 63, 62, 61, 60, 59, 58, 57, 56),
                    pager.state.value.itemIndexes(),
                )
                assertTrue(pager.state.value.hasMoreBottom)

                pager.dispatch(WindowIntent.JumpToNewest)
                advanceUntilIdle()

                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
                assertEquals(Focus.Item("100"), pager.state.value.pendingFocus)
                assertFalse(pager.state.value.hasMoreBottom)
                assertTrue(pager.state.value.hasMoreTop)
                assertEquals(
                    listOf<LoadParams>(
                        LoadParams.Initial(position = InitialPosition.Around("60"), limit = PAGE_SIZE),
                        LoadParams.Initial(position = InitialPosition.Newest, limit = PAGE_SIZE),
                    ),
                    dataSource.loadCalls,
                )
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `jump to newest на live edge не перезагружает initial window`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 5)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                val callsAfterBootstrap = dataSource.loadCalls.toList()

                pager.dispatch(WindowIntent.JumpToNewest)
                advanceUntilIdle()

                assertEquals(callsAfterBootstrap, dataSource.loadCalls)
                assertEquals(Focus.Item("5"), pager.state.value.pendingFocus)
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `reconcile live edge принудительно заменяет stale окно authoritative newest`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 5)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                assertEquals(listOf(5, 4, 3, 2, 1), pager.state.value.itemIndexes())
                assertFalse(pager.state.value.hasMoreBottom)

                dataSource.totalItems = 8
                pager.dispatch(WindowIntent.ReconcileLiveEdge)
                advanceUntilIdle()

                assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1), pager.state.value.itemIndexes())
                assertEquals(Focus.Item("8"), pager.state.value.pendingFocus)
                assertEquals(1, dataSource.refreshNewestCalls)
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `retry после ошибки reconcile повторяет authoritative newest`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 5)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                dataSource.nextLoadError = Presence.Present(RuntimeException("network down"))

                pager.dispatch(WindowIntent.ReconcileLiveEdge)
                advanceUntilIdle()
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)

                dataSource.totalItems = 8
                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()

                assertEquals(2, dataSource.refreshNewestCalls)
                assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1), pager.state.value.itemIndexes())
                assertEquals(LoadState.Idle, pager.state.value.loadStateBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `retry после ошибки jump повторяет исходный target`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                dataSource.nextLoadError = Presence.Present(RuntimeException("network down"))

                pager.dispatch(WindowIntent.JumpTo(itemId = "20"))
                advanceUntilIdle()
                assertIs<LoadState.Error>(pager.state.value.loadStateTop)
                assertIs<LoadState.Error>(pager.state.value.loadStateBottom)

                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()

                assertEquals(
                    2,
                    dataSource.loadCalls.count { it == LoadParams.Around(anchorId = "20", limit = PAGE_SIZE) },
                )
                assertTrue(20 in pager.state.value.itemIndexes())
                assertEquals(Focus.Item("20"), pager.state.value.pendingFocus)
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── LoadTop / LoadBottom ─────────────────────────────────────

    @Test
    fun `LoadTop расширяет окно старыми элементами`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [100..92]
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                // loadBefore("92", 9) -> [91..83]
                assertEquals((100 downTo 83).toList(), pager.state.value.itemIndexes())
                assertTrue(pager.state.value.hasMoreTop)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `LoadTop останавливается при достижении начала данных`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 15)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [15..7]
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                // loadBefore("7", 9) -> [6..1] (6 items < pageSize) -> hasMoreTop = false
                assertEquals((15 downTo 1).toList(), pager.state.value.itemIndexes())
                assertFalse(pager.state.value.hasMoreTop)

                val callsBefore = dataSource.loadCalls.size
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                // canLoad возвращает false -> новых вызовов нет
                assertEquals(callsBefore, dataSource.loadCalls.size)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `LoadBottom игнорируется на live edge`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap без anchor -> hasMoreBottom = false (мы на live edge)
                assertFalse(pager.state.value.hasMoreBottom)

                val callsBefore = dataSource.loadCalls.size
                pager.dispatch(WindowIntent.LoadBottom)
                advanceUntilIdle()

                assertEquals(callsBefore, dataSource.loadCalls.size)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `LoadTop после jump грузит старые элементы от новой позиции окна`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "50"))
                advanceUntilIdle()

                // Window: [54..46]
                assertEquals(
                    listOf(54, 53, 52, 51, 50, 49, 48, 47, 46),
                    pager.state.value.itemIndexes(),
                )

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                // loadBefore("46", 9) -> [45..37]
                assertEquals(
                    LoadParams.Before(anchorId = "46", anchorSortKey = 46L, limit = PAGE_SIZE),
                    dataSource.loadCalls.last(),
                )
                assertEquals((54 downTo 37).toList(), pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── Bootstrap ───────────────────────────────────────────────

    @Test
    fun `bootstrap с initialAnchorId - окно вокруг anchor`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource, initialAnchorId = InitialPosition.Around("60"))
            try {
                advanceUntilIdle()

                // Initial(anchorId="60", limit=9) -> around(60, 9) = [64..56]
                assertEquals(
                    listOf(64, 63, 62, 61, 60, 59, 58, 57, 56),
                    pager.state.value.itemIndexes(),
                )
                assertTrue(pager.state.value.hasMoreTop)
                assertTrue(pager.state.value.hasMoreBottom, "anchor found -> hasMoreBottom = true")
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `bootstrap around live edge - hasMoreBottom false без пустого LoadBottom`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource, initialAnchorId = InitialPosition.Around("98"))
            try {
                advanceUntilIdle()

                assertTrue(
                    pager.state.value
                        .itemIndexes()
                        .contains(100),
                    "окно вокруг lastRead у края содержит live edge",
                )
                assertFalse(
                    pager.state.value.hasMoreBottom,
                    "CHAT2 §2: у live edge не оставляем hasMoreBottom — иначе prefetch offset=-limit",
                )
                assertTrue(pager.state.value.hasMoreTop)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `bootstrap без anchor - загружает newest hasMoreBottom false`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
                assertTrue(pager.state.value.hasMoreTop)
                assertFalse(pager.state.value.hasMoreBottom, "no anchor -> hasMoreBottom = false")
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `bootstrap - fallback на newest когда initialAnchorId не найден`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            // Anchor "999" не существует в данных [1..100]
            val (pager, pagerScope) = createPager(dataSource, initialAnchorId = InitialPosition.Around("999"))
            try {
                advanceUntilIdle()

                // Fallback: загружаем newest, anchor не найден в результате
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
                assertTrue(pager.state.value.hasMoreTop)
                // anchor не найден -> hasMoreBottom = false (как при обычном bootstrap)
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── observeChanges ──────────────────────────────────────────

    @Test
    fun `observeChanges - удалённые элементы исчезают из окна`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [100..92]
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())

                // Snapshot без элемента 96 (удалён из БД)
                val snapshotWithout96 = (100 downTo 92).filter { it != 96 }.map { TestItem(it) }
                dataSource.emitSnapshot(snapshotWithout96)
                advanceUntilIdle()

                assertEquals((100 downTo 92).filter { it != 96 }, pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `observeChanges - live tail добавляется на live edge`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [100..92], hasMoreBottom = false (live edge)
                assertFalse(pager.state.value.hasMoreBottom)

                // Новые сообщения через websocket: 101, 102 (новее текущего newest)
                val snapshotWithNew = (102 downTo 92).map { TestItem(it) }
                dataSource.emitSnapshot(snapshotWithNew)
                advanceUntilIdle()

                // Live tail: 101 и 102 добавились
                assertEquals((102 downTo 92).toList(), pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `observeChanges - live tail НЕ добавляется при hasMoreBottom true`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, pagerScope) = createPager(dataSource, initialAnchorId = InitialPosition.Around("60"))
            try {
                advanceUntilIdle()

                // Bootstrap с anchor: [64..56], hasMoreBottom = true
                assertTrue(pager.state.value.hasMoreBottom)

                // Snapshot с новыми элементами (101, 102) - НЕ должны попасть в окно
                val snapshotWithNew =
                    listOf(TestItem(101), TestItem(102)) +
                        (64 downTo 56).map { TestItem(it) }
                dataSource.emitSnapshot(snapshotWithNew)
                advanceUntilIdle()

                // Окно не изменилось - live tail подавлен
                assertEquals((64 downTo 56).toList(), pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `observeChanges - свой send с sort-ключом внутри окна попадает в ленту на live edge`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [100..92], hasMoreBottom = false (live edge)
                assertFalse(pager.state.value.hasMoreBottom)

                // C-437: оптимистичная вставка своего send'а несёт createdAt КЛИЕНТСКИХ часов, а верх
                // окна проштампован сервером (и эхо-семантика upsert'а сохраняет клиентский createdAt
                // строки навсегда). Отстающие часы или свежее чужое сообщение делают свой send «старше»
                // newest окна — фильтр live tail «строго новее ВЕРХА окна» молча ронял его, и своё
                // сообщение не появлялось в открытой ленте до перезагрузки. На live edge окно покрывает
                // диапазон непрерывно — новый элемент с sort-ключом ВНУТРИ диапазона обязан мержиться.
                val ownSend = TestItem(index = 99, id = "own-send")
                val snapshot = listOf(ownSend) + (100 downTo 92).map { TestItem(it) }
                dataSource.emitSnapshot(snapshot)
                advanceUntilIdle()

                assertTrue(
                    pager.state.value.items
                        .any { it.id == "own-send" },
                    "свой send с sort-ключом внутри диапазона окна обязан появиться в открытой ленте",
                )
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `observeChanges - удаление нижнего ряда не сужает покрытый диапазон для мержа`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [100..92], live edge. В ОДНОМ снапшоте: низ окна (92) удалён из БД,
                // а новый элемент сортуется между 93 и бывшим 92. Загруженная граница — низ
                // ИСХОДНОГО окна: удаление ряда не сужает покрытый диапазон, вставка легальна.
                val inRange = TestItem(index = 92, id = "own-in-range")
                // Самопроверка фикстуры: вставка действительно НОВЕЕ исходной нижней границы (92) —
                // смена компаратора фикстуры не должна тихо ослабить то, что доказывает тест.
                assertTrue(dataSource.comparator.compare(inRange, TestItem(92)) < 0)
                val snapshot = (100 downTo 93).map { TestItem(it) } + inRange
                dataSource.emitSnapshot(snapshot)
                advanceUntilIdle()

                val ids =
                    pager.state.value.items
                        .map { it.id }
                assertTrue("own-in-range" in ids, "вставка внутри покрытого диапазона обязана мержиться")
                assertFalse("92" in ids, "удалённый нижний ряд обязан исчезнуть из окна")
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `observeChanges - элементы старше низа окна не доклеиваются на live edge`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100, useLiveSnapshots = true)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Bootstrap: [100..92], live edge. observeLatest(50) в проде видит и строки СТАРШЕ
                // низа окна — это незагруженная история: её доклейка мимо Before-страниц создала бы
                // невидимый шов (сообщения по разные стороны дыры рисуются соседями).
                val snapshot = (100 downTo 85).map { TestItem(it) }
                dataSource.emitSnapshot(snapshot)
                advanceUntilIdle()

                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── Retry ───────────────────────────────────────────────────

    @Test
    fun `retry повторяет LoadTop после ошибки`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Следующий load упадёт
                dataSource.nextLoadError = Presence.Present(RuntimeException("network error"))
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                assertIs<LoadState.Error>(pager.state.value.loadStateTop)
                // Окно не изменилось
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())

                // Retry - теперь load пройдёт успешно
                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()

                assertEquals(LoadState.Idle, pager.state.value.loadStateTop)
                assertEquals((100 downTo 83).toList(), pager.state.value.itemIndexes())
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `retry без ошибок - ничего не делает`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                val callsBefore = dataSource.loadCalls.size
                pager.dispatch(WindowIntent.Retry)
                advanceUntilIdle()

                // Нет ошибок -> retry не вызывает load
                assertEquals(callsBefore, dataSource.loadCalls.size)
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── trimCache ───────────────────────────────────────────────

    @Test
    fun `trimCache получает protectedIds текущего окна после jump`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(itemId = "50"))
                advanceUntilIdle()

                // trimCache вызван с protectedIds = текущее окно [54..46]
                val lastTrim = dataSource.trimCacheCalls.last()
                assertEquals(100, lastTrim.first) // maxCachedItems
                assertEquals(
                    (54 downTo 46).map { it.toString() }.toSet(),
                    lastTrim.second,
                )
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `trimCache не вызывается при edge load`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                dataSource.trimCacheCalls.clear()

                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()

                assertTrue(
                    dataSource.trimCacheCalls.isEmpty(),
                    "trimCache should not be called on edge load",
                )
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── Background reconcile (Paging 3-паритет: кэш сразу, сверка фоном) ─────────

    @Test
    fun `фоновый reconcile - кэш показан сразу нетронутое окно атомарно заменяется серверным`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val gate = CompletableDeferred<Unit>()
            dataSource.staleInitialWindow = Presence.Present((60 downTo 52).map(::TestItem))
            dataSource.backgroundReconcileResult = BackgroundReconcile { ReconcileResult.Updated(run {
                gate.await()
                LoadResult(
                    items = (100 downTo 92).map(::TestItem),
                    hasMore = true,
                    hasMoreTop = BoundaryHint.of(true),
                    hasMoreBottom = BoundaryHint.of(false),
                )
            }) }
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()

                // Кэш опубликован сразу, фоновая сверка ещё висит — Loading не показывается.
                assertEquals((60 downTo 52).toList(), pager.state.value.itemIndexes())
                assertEquals(LoadState.Idle, pager.state.value.loadStateBottom)

                gate.complete(Unit)
                advanceUntilIdle()

                // Разрыв больше страницы (92 > 60), окно не менялось → атомарный replace серверным.
                assertEquals((100 downTo 92).toList(), pager.state.value.itemIndexes())
                assertTrue(pager.state.value.hasMoreTop)
                assertFalse(pager.state.value.hasMoreBottom)
                assertEquals(Focus.None, pager.state.value.pendingFocus, "замена окна не дёргает скролл")
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `фоновый reconcile - merge при overlap сохраняет подгруженные юзером страницы`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 65)
            val gate = CompletableDeferred<Unit>()
            dataSource.staleInitialWindow = Presence.Present((60 downTo 52).map(::TestItem))
            dataSource.backgroundReconcileResult = BackgroundReconcile { ReconcileResult.Updated(run {
                gate.await()
                LoadResult(
                    items = (65 downTo 57).map(::TestItem),
                    hasMore = true,
                    hasMoreTop = BoundaryHint.of(true),
                    hasMoreBottom = BoundaryHint.of(false),
                )
            }) }
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertEquals((60 downTo 43).toList(), pager.state.value.itemIndexes())

                gate.complete(Unit)
                advanceUntilIdle()

                // Серверное окно пересекается с уехавшим (65..57 ∩ 60..43) → merge без потерь и швов.
                assertEquals((65 downTo 43).toList(), pager.state.value.itemIndexes())
                assertFalse(pager.state.value.hasMoreBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `фоновый reconcile - без overlap окно не трогается поднимается только hasMoreBottom`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val gate = CompletableDeferred<Unit>()
            dataSource.staleInitialWindow = Presence.Present((60 downTo 52).map(::TestItem))
            dataSource.backgroundReconcileResult = BackgroundReconcile { ReconcileResult.Updated(run {
                gate.await()
                LoadResult(
                    items = (100 downTo 92).map(::TestItem),
                    hasMore = true,
                    hasMoreTop = BoundaryHint.of(true),
                    hasMoreBottom = BoundaryHint.of(false),
                )
            }) }
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                val scrolled = pager.state.value.itemIndexes()
                assertEquals((60 downTo 43).toList(), scrolled)

                gate.complete(Unit)
                advanceUntilIdle()

                // Юзер уехал в историю, серверное окно не пересекается — не выдёргиваем его из чтения,
                // но stale-край перестаёт считаться live edge: вниз доедет честными After-страницами.
                assertEquals(scrolled, pager.state.value.itemIndexes())
                assertTrue(pager.state.value.hasMoreBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    @Test
    fun `фоновый reconcile - ошибка сверки оставляет кэш без Error-стейта`() =
        runTest {
            val dataSource = FakeWindowDataSource(totalItems = 100)
            val gate = CompletableDeferred<Unit>()
            dataSource.staleInitialWindow = Presence.Present((60 downTo 52).map(::TestItem))
            dataSource.backgroundReconcileResult = BackgroundReconcile { ReconcileResult.Updated(run {
                gate.await()
                throw RuntimeException("network down")
            }) }
            val (pager, pagerScope) = createPager(dataSource)
            try {
                advanceUntilIdle()
                gate.complete(Unit)
                advanceUntilIdle()

                // Окно уже показано — ошибка фоновой сверки не красит UI и не трогает кэш.
                assertEquals((60 downTo 52).toList(), pager.state.value.itemIndexes())
                assertEquals(LoadState.Idle, pager.state.value.loadStateTop)
                assertEquals(LoadState.Idle, pager.state.value.loadStateBottom)
            } finally {
                pagerScope.cancel()
            }
        }

    // ─── Helpers ──────────────────────────────────────────────────

    private fun TestScope.createPager(
        dataSource: FakeWindowDataSource,
        initialAnchorId: InitialPosition = InitialPosition.Newest,
        prefetchDistance: Int = PREFETCH_DISTANCE,
    ): Pair<WindowPager<TestItem>, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val pager =
            WindowPager(
                dataSource = dataSource,
                config =
                    WindowPagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = prefetchDistance,
                        maxCachedItems = 100,
                    ),
                initialPosition = initialAnchorId,
                scope = PagingScope.Borrowed(scope),
            )
        return pager to scope
    }

    private fun WindowState<TestItem>.itemIndexes(): List<Int> = items.map { it.index }

    data class TestItem(
        val index: Int,
        val id: String = index.toString(),
    )

    /**
     * Фейковый источник данных.
     * Моделирует линейный набор элементов 1..totalItems с DESC-сортировкой.
     *
     * @param useLiveSnapshots если true, observeChanges возвращает управляемый Flow
     *   (для тестов live-обновлений). Если false - emptyFlow() (для изолированных тестов пагинации).
     */
    class FakeWindowDataSource(
        var totalItems: Int,
        private val useLiveSnapshots: Boolean = false,
        /** canonicalId ≠ itemId — для тестов источника с двойной идентичностью. */
        private val canonicalIdFor: (TestItem) -> String = { it.id },
    ) : WindowDataSource<TestItem> {
        override val comparator: Comparator<TestItem> =
            Comparator<TestItem> { left: TestItem, right: TestItem -> right.index.compareTo(left.index) }
                .then(Comparator { left: TestItem, right: TestItem -> right.id.compareTo(left.id) })

        override val itemId: (TestItem) -> String = { it.id }

        override val canonicalId: (TestItem) -> String = { item ->
            canonicalIdFor(item)
        }

        val loadCalls = mutableListOf<LoadParams>()
        var refreshNewestCalls = 0
        val trimCacheCalls = mutableListOf<Pair<Int, Set<String>>>()

        /** Управляемый Flow для observeChanges. Используй [emitSnapshot] для эмита. */
        private val snapshotFlow = MutableSharedFlow<List<TestItem>>(extraBufferCapacity = 10)

        /** Если не null - следующий вызов load() выбросит это исключение (однократно). */
        internal var nextLoadError: Presence<Exception> = Presence.Absent

        /**
         * Если задан — Initial(anchorId=null) вернёт ЭТО окно (stale-кэш) вместо newest,
         * приложив [backgroundReconcileResult] как отложенную серверную сверку.
         */
        internal var staleInitialWindow: Presence<List<TestItem>> = Presence.Absent

        /** Отложенная сверка, прикладываемая к stale-initial (см. [staleInitialWindow]). */
        var backgroundReconcileResult: BackgroundReconcile<TestItem> = BackgroundReconcile { ReconcileResult.Unchanged }

        suspend fun emitSnapshot(items: List<TestItem>) {
            snapshotFlow.emit(items)
        }

        override suspend fun load(params: LoadParams): LoadResult<TestItem> {
            loadCalls += params
            nextLoadError.fold({}) {
                nextLoadError = Presence.Absent
                throw it
            }
            return when (params) {
                is LoadParams.Initial -> loadInitial(params.position, params.limit)
                is LoadParams.Before -> loadBefore(params.anchorId, params.limit)
                is LoadParams.After -> loadAfter(params.anchorId, params.limit)
                is LoadParams.Around -> loadAround(params.anchorId, params.limit)
            }
        }

        override suspend fun refreshNewest(limit: Int): LoadResult<TestItem> {
            refreshNewestCalls++
            return load(LoadParams.Initial(position = InitialPosition.Newest, limit = limit))
        }

        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<TestItem>> = if (useLiveSnapshots) snapshotFlow else emptyFlow()

        override fun getSortKey(item: TestItem): Long = item.index.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) {
            trimCacheCalls += keepCount to protectedIds
        }

        private fun loadInitial(
            anchorId: InitialPosition,
            limit: Int,
        ): LoadResult<TestItem> {
            if (anchorId == InitialPosition.Newest) {
                staleInitialWindow.fold({}) { stale ->
                    return LoadResult(
                        items = stale,
                        hasMore = stale.size == limit,
                        hasMoreTop = BoundaryHint.of(stale.size == limit),
                        hasMoreBottom = BoundaryHint.of(false),
                        backgroundReconcile = backgroundReconcileResult,
                    )
                }
                val items = newest(limit)
                return LoadResult(
                    items = items,
                    hasMore = items.size == limit,
                    hasMoreTop = BoundaryHint.of(items.size == limit),
                    hasMoreBottom = BoundaryHint.of(false),
                )
            }
            val anchor = runCatching { (anchorId as InitialPosition.Around).id.toInt() }.getOrDefault(0)
            if (anchor < 1 || anchor > totalItems) {
                // Anchor не найден - fallback на newest (как реальный API)
                val items = newest(limit)
                return LoadResult(
                    items = items,
                    hasMore = items.size == limit,
                    hasMoreTop = BoundaryHint.of(items.size == limit),
                    hasMoreBottom = BoundaryHint.of(false),
                )
            }
            val items = around(anchor, limit)
            val newerCount = items.count { it.index > anchor }
            val olderCount = items.count { it.index < anchor }
            val halfPage = limit / 2
            val containsLiveEdge = items.any { it.index == totalItems }
            val hasMoreBottom = !containsLiveEdge && newerCount >= halfPage
            val hasMoreTop = olderCount >= halfPage
            return LoadResult(
                items = items,
                hasMore = hasMoreTop || hasMoreBottom,
                hasMoreTop = BoundaryHint.of(hasMoreTop),
                hasMoreBottom = BoundaryHint.of(hasMoreBottom),
            )
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
            val anchor = anchorId.toInt()
            val items = around(anchor, limit)
            val newerCount = items.count { it.index > anchor }
            val olderCount = items.count { it.index < anchor }
            val hasMore = anchor + newerCount < totalItems || anchor - olderCount > 1
            return LoadResult(items = items, hasMore = hasMore)
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

    private companion object {
        const val PAGE_SIZE = 9
        const val PREFETCH_DISTANCE = 2
    }
}
