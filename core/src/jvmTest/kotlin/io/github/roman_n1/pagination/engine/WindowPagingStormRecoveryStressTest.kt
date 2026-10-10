package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/** Тот же публичный фейк-тип из commonTest, что и у соседних стрессов. */
private typealias StormItem = WindowPagerAngryTest.TestItem

/**
 * CS-2, волна 10 — стрессы, которых НЕТ в [WindowPagerConcurrencyStressTest] и
 * [WindowPagingJumpAndMapStressTest] (там: J1 — структурные инварианты под штормом БЕЗ
 * ClearJumpState и БЕЗ ассертов liveness; J2/J3/M3 — детерминированные репро на
 * StandardTestDispatcher; M1/M2 — burst через `map` без отказов transform).
 *
 * Здесь три дыры, оставшиеся после фиксов RC-7:
 *
 * 1. **Liveness после шторма с ClearJumpState на реальных потоках.** Фикс J3
 *    (`idleIfLoading` + `windowEpoch`) проверен только детерминированным interleaving'ом.
 *    Шторм, включающий ClearJumpState, гоняет ту же пару «снятие Loading ↔ инвалидация
 *    in-flight» под настоящей конкуренцией, и после затишья пагинация обязана быть ЖИВОЙ:
 *    loadState не залип, а финальный JumpTo доезжает до цели. Регресс любого из двух
 *    механизмов (уберут `windowEpoch++` из clearPendingFocus, вернут голый сброс фокуса)
 *    ловится именно здесь, а не структурными ассертами J1.
 *
 * 2. **Отказ transform под burst через `mapList`.** RC-7-catch в MappedWindowController
 *    проверен одним детерминированным отказом через `map` (M3). Прод-путь —
 *    `MessageRepositoryImpl.mapList { ...messageDao... }`, и SQLITE_BUSY прилетает именно
 *    под burst'ом вебсокета. Инвариант: отравленный снапшот молча пропускается, исключение
 *    не уходит в обработчик платформы, поток состояний живёт и сходится к финальному входу.
 *
 * 3. **close() посреди burst (warm-start, вариант B).** Session-реестр окон закрывает
 *    контроллеры при пересоздании окна; «зомби-публикация» после close или живой
 *    собственный scope (долг WP1) — это утечка и перезатирание чужого снапшота.
 *
 * ⚠ RC-8: контракт непрерывности окна относительно модели после удалений НЕ решён —
 * здесь намеренно нет ни одного ассерта на непрерывность/стыки, только сортировка,
 * уникальность и liveness.
 *
 * jvmTest: нужны runBlocking и многопоточный Dispatchers.Default (js/wasm однопоточны).
 */
class WindowPagingStormRecoveryStressTest {
    // ─── 1. Шторм с ClearJumpState + liveness-проба ──────────────────────────────────

    /**
     * Ожидается ЗЕЛЁНЫМ. После шторма {JumpTo, LoadTop, LoadBottom, ClearJumpState,
     * live-snapshot} на реальных потоках: оба направления выходят из Loading (страж фикса J3
     * под настоящей конкуренцией — детерминированная версия живёт в
     * [WindowPagingJumpAndMapStressTest]), окно остаётся отсортированным без дублей, и —
     * главное — pager остаётся РАБОТОСПОСОБНЫМ: контрольный JumpTo к далёкой цели доезжает
     * (цель в окне, фокус выставлен, направления не Loading).
     *
     * Отказ при регрессе: «прыгнул к цитате, смахнул список, прыгнул ещё раз — лента мертва
     * до пересоздания экрана». Структурные ассерты J1 такой регресс НЕ ловят: окно у мёртвого
     * pager'а идеально отсортировано.
     */
    @Test
    fun `шторм с ClearJumpState - loadState не залипает и финальный JumpTo доезжает`(): Unit =
        runBlocking {
            repeat(ITERATIONS) { iteration ->
                val rnd = Random(SEED + iteration)
                val ops = List(OPS_PER_ITERATION) { randomOp(rnd) }
                // Латентности — из заранее посчитанного seeded-пула: Random не потокобезопасен.
                val latencyPool = IntArray(LATENCY_POOL_SIZE) { rnd.nextInt(0, MAX_LATENCY_MS + 1) }
                val latencyCursor = AtomicInteger(0)
                val ds = DenseDataSource(UNIVERSE)
                ds.latencyMs = { latencyPool[latencyCursor.getAndIncrement() % latencyPool.size].toLong() }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val pager =
                    WindowPager(
                        dataSource = ds,
                        config =
                            WindowPagingConfig(
                                pageSize = PAGE_SIZE,
                                prefetchDistance = PREFETCH_DISTANCE,
                                maxCachedItems = MAX_CACHED_ITEMS,
                            ),
                        initialPosition = InitialPosition.Around(START_ANCHOR),
                        scope = PagingScope.Borrowed(scope),
                    )
                val plan = ops.joinToString(",") { it.label }
                val diag = "seed=$SEED it=$iteration plan=[$plan]"
                try {
                    assertTrue(
                        awaitCondition {
                            pager.state.value.items
                                .isNotEmpty()
                        },
                        "bootstrap не собрал стартовое окно - $diag",
                    )
                    pager.settleIdle()

                    coroutineScope {
                        ops.chunked(OPS_PER_WORKER).forEach { slice ->
                            launch(Dispatchers.Default) {
                                slice.forEach { op -> applyOp(pager, ds, op, iteration) }
                            }
                        }
                    }

                    // Страж J3-фикса: после затишья ни одно направление не залипло в Loading.
                    assertTrue(
                        awaitCondition {
                            val s = pager.state.value
                            s.loadStateTop !is LoadState.Loading && s.loadStateBottom !is LoadState.Loading
                        },
                        "loadState залип в Loading после шторма - пагинация мертва - " +
                            "top=${pager.state.value.loadStateTop} bottom=${pager.state.value.loadStateBottom} - $diag",
                    )
                    pager.settleIdle()

                    val settled = pager.state.value
                    val ids = settled.items.map(ds.itemId)
                    assertTrue(settled.items.isNotEmpty(), "окно опустело после шторма - $diag")
                    assertEquals(ids.distinct(), ids, "дубликаты id в окне - $diag")
                    assertEquals(
                        settled.items.sortedWith(ds.comparator),
                        settled.items,
                        "порядок DESC нарушен - окно=${settled.items.map { it.index }} - $diag",
                    )

                    // Liveness-проба: pager обязан обслужить следующий прыжок после любого шторма.
                    pager.dispatch(WindowIntent.JumpTo(PROBE_TARGET))
                    assertTrue(
                        awaitCondition {
                            val s = pager.state.value
                            s.pendingFocus == Focus.Item(PROBE_TARGET) &&
                                s.items.any { ds.itemId(it) == PROBE_TARGET } &&
                                s.loadStateTop !is LoadState.Loading &&
                                s.loadStateBottom !is LoadState.Loading
                        },
                        "pager мёртв после шторма - контрольный JumpTo к $PROBE_TARGET не доехал - " +
                            "focus=${pager.state.value.pendingFocus} " +
                            "окно=${pager.state.value.items.map { it.index }} - $diag",
                    )
                } finally {
                    pager.close()
                    scope.cancel()
                }
            }
        }

    // ─── 2. Burst с отказом transform через mapList ──────────────────────────────────

    /**
     * Ожидается ЗЕЛЁНЫМ. Страж RC-7-catch на реальных потоках и на прод-пути `mapList`
     * (M3 проверяет тот же catch детерминированно и через `map`).
     *
     * Сценарий: burst генераций со случайно отравленными снапшотами (transform бросает
     * SQLITE_BUSY), затем ГАРАНТИРОВАННО отравленная генерация (последняя на тот момент —
     * mapLatest обязан довести её transform до броска, конфляция её не съест), затем чистый
     * финальный вход. Инварианты:
     * - исключение transform не уходит в CoroutineExceptionHandler scope'а (на Android это
     *   краш процесса, на десктопе — молча мёртвая лента);
     * - отравленная генерация никогда не публикуется;
     * - опубликованные генерации не убывают (конфляция может пропускать, но не откатывать);
     * - финальный снапшот соответствует финальному входу — поток жив после любого числа отказов.
     */
    @Test
    fun `burst с отказом transform - mapList переживает SQLITE_BUSY и сходится к финальному входу`(): Unit =
        runBlocking {
            val rnd = Random(SEED)
            val poisoned: MutableSet<Int> =
                (1..GENERATIONS - 2)
                    .filter { rnd.nextInt(100) < POISON_PERCENT }
                    .toMutableSet()
            poisoned += POISON_PROBE
            val transformDelays = LongArray(GENERATIONS + 1) { rnd.nextLong(0, MAX_TRANSFORM_DELAY_MS + 1) }
            val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
            val poisonThrown = AtomicInteger(0)
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Default +
                        CoroutineExceptionHandler { _, error -> uncaught += error },
                )
            val upstream = MutableStateFlow(generationState(0))
            val delegate = FakeWindowController(upstream)
            val published: MutableList<Int> = Collections.synchronizedList(mutableListOf())

            val mapped =
                delegate.mapList(itemId = { it }, scope = PagingScope.Borrowed(scope)) { values ->
                    val generation = generationOf(values.first())
                    delay(transformDelays[generation])
                    if (generation in poisoned) {
                        poisonThrown.incrementAndGet()
                        throw IllegalStateException("SQLITE_BUSY gen=$generation")
                    }
                    values.map { it.uppercase() }
                }
            val collector =
                scope.launch {
                    mapped.state.collect { snapshot ->
                        if (snapshot.items.isNotEmpty()) published += generationOf(snapshot.items.first())
                    }
                }
            try {
                for (generation in 1..GENERATIONS - 2) {
                    upstream.value = generationState(generation)
                    if (generation % BURST_BREATH == 0) delay(1)
                }

                // Детерминированный отказ: probe — ПОСЛЕДНЯЯ эмиссия, отменить её transform нечем.
                upstream.value = generationState(POISON_PROBE)
                assertTrue(
                    awaitCondition { poisonThrown.get() > 0 },
                    "вакуум - ни один отравленный transform не дошёл до броска - seed=$SEED",
                )

                upstream.value = generationState(GENERATIONS)
                assertTrue(
                    awaitCondition { mapped.state.value.pendingFocus == Focus.Item("g$GENERATIONS") },
                    "поток состояний не сошёлся к финальному входу после отказов - " +
                        "focus=${mapped.state.value.pendingFocus} uncaught=${uncaught.map { it.toString() }} - seed=$SEED",
                )
                awaitCondition { published.isNotEmpty() && published.last() == GENERATIONS }

                val observed = published.toList()
                val diag = "seed=$SEED отравлено=${poisoned.size} хвост=${observed.takeLast(8)}"
                assertEquals(
                    listOf("G$GENERATIONS-A", "G$GENERATIONS-B"),
                    mapped.state.value.items,
                    "финальный снапшот не соответствует финальному входу - $diag",
                )
                assertEquals(
                    emptyList(),
                    uncaught.map { it.toString() },
                    "исключение transform ушло за пределы контроллера - на Android это краш процесса - $diag",
                )
                assertEquals(
                    emptyList(),
                    observed.filter { it in poisoned },
                    "отравленная генерация опубликована - RC-7-catch пропустил сбойный снапшот - $diag",
                )
                assertEquals(
                    observed.sorted(),
                    observed,
                    "опубликованные генерации убывали - устаревшая трансформация легла поверх свежей - $diag",
                )
                assertEquals(GENERATIONS, observed.last(), "последняя опубликованная генерация - $diag")
            } finally {
                collector.cancel()
                mapped.close()
                scope.cancel()
            }
        }

    // ─── 3. close посреди burst — зомби-публикации и владение scope ──────────────────

    /**
     * Ожидается ЗЕЛЁНЫМ. Контроллер с СОБСТВЕННЫМ scope (scope = null — ровно так его
     * держит session-реестр окон при warm-start): close() посреди burst'а, пока трансформация
     * в полёте. Инварианты:
     * - in-flight трансформация отменяется, а не доводится до публикации в фоне;
     * - эмиссии, пришедшие ПОСЛЕ close, не публикуются никогда (снимок замерзает);
     * - собственный scope погашен (isOwnedScopeActive=false) — долг WP1: раньше корневой Job
     *   жил до смерти процесса, и реестр, пересоздающий окна, копил утечки.
     *
     * Отказ при регрессе: «зомби»-снапшот закрытого окна перетирает состояние уже
     * пересозданного (два окна публикуются в одну ленту), плюс утечка scope на каждый
     * warm-start.
     */
    @Test
    fun `close посреди burst - поздние эмиссии не публикуются и собственный scope погашен`(): Unit =
        runBlocking {
            val cancelledByClose = AtomicInteger(0)
            repeat(CLOSE_RACE_ITERATIONS) { iteration ->
                val upstream = MutableStateFlow(generationState(0))
                val delegate = FakeWindowController(upstream)
                val started = AtomicInteger(0)
                val inFlight = AtomicInteger(0)
                // mapLatest и сам отменяет устаревшие трансформации при burst'е — такие отмены
                // вакуум-гард НЕ считает: нас интересует только отмена, вызванная close().
                val closeInvoked = MutableStateFlow(false)
                val mapped =
                    delegate.map(itemId = { it }) { value ->
                        started.incrementAndGet()
                        inFlight.incrementAndGet()
                        try {
                            delay(TRANSFORM_HOLD_MS)
                            value.uppercase()
                        } catch (cancellation: CancellationException) {
                            if (closeInvoked.value) cancelledByClose.incrementAndGet()
                            throw cancellation
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                val diag = "seed=$SEED it=$iteration"
                try {
                    for (generation in 1..PRE_CLOSE_GENERATIONS) {
                        upstream.value = generationState(generation)
                    }
                    assertTrue(
                        awaitCondition { started.get() > 0 },
                        "вакуум - ни одна трансформация не стартовала до close - $diag",
                    )

                    closeInvoked.value = true
                    mapped.close()

                    // Эмиссии после close: контроллер обязан их игнорировать.
                    val postCloseFrom = PRE_CLOSE_GENERATIONS + 1
                    for (generation in postCloseFrom..postCloseFrom + POST_CLOSE_GENERATIONS) {
                        upstream.value = generationState(generation)
                    }

                    assertTrue(
                        awaitCondition { inFlight.get() == 0 },
                        "трансформация пережила close - in-flight=${inFlight.get()} - $diag",
                    )
                    mapped.state.settleStable()

                    val frozen = mapped.state.value
                    val frozenGeneration = if (frozen.items.isEmpty()) 0 else generationOf(frozen.items.first())
                    assertTrue(
                        frozenGeneration <= PRE_CLOSE_GENERATIONS,
                        "опубликована генерация из эмиссий ПОСЛЕ close - " +
                            "gen=$frozenGeneration items=${frozen.items} - $diag",
                    )

                    upstream.value = generationState(postCloseFrom + POST_CLOSE_GENERATIONS + 1)
                    delay(FREEZE_TAIL_MS)
                    assertEquals(
                        frozen,
                        mapped.state.value,
                        "зомби-публикация после close - закрытое окно перетёрло бы состояние пересозданного - $diag",
                    )
                    assertFalse(
                        (mapped as MappedWindowController<*, *>).isOwnedScopeActive(),
                        "собственный scope жив после close - утечка WP1 на каждый warm-start - $diag",
                    )
                } finally {
                    mapped.close()
                }
            }
            // Агрегат по всем итерациям: хотя бы раз close обязан был застать трансформацию в полёте,
            // иначе гонка «close против in-flight» ни разу не разыгралась и тест был бы вакуумным.
            assertTrue(
                cancelledByClose.get() > 0,
                "за $CLOSE_RACE_ITERATIONS итераций close ни разу не застал трансформацию в полёте - seed=$SEED",
            )
        }

    // ─── Обвязка шторма ──────────────────────────────────────────────────────────────

    private sealed interface StormOp {
        val label: String

        data class JumpFar(
            val target: Int,
        ) : StormOp {
            override val label: String get() = "Jump$target"
        }

        data object LoadTop : StormOp {
            override val label: String get() = "LoadTop"
        }

        data object LoadBottom : StormOp {
            override val label: String get() = "LoadBottom"
        }

        data object ClearJump : StormOp {
            override val label: String get() = "ClearJump"
        }

        data object Snapshot : StormOp {
            override val label: String get() = "Snapshot"
        }
    }

    private fun randomOp(rnd: Random): StormOp =
        when (rnd.nextInt(5)) {
            0 -> StormOp.JumpFar(LANDING_ZONES[rnd.nextInt(LANDING_ZONES.size)])
            1 -> StormOp.LoadTop
            2 -> StormOp.LoadBottom
            3 -> StormOp.ClearJump
            else -> StormOp.Snapshot
        }

    private suspend fun applyOp(
        pager: WindowPager<StormItem>,
        ds: DenseDataSource,
        op: StormOp,
        iteration: Int,
    ) {
        when (op) {
            is StormOp.JumpFar -> {
                pager.dispatch(WindowIntent.JumpTo(op.target.toString()))
            }

            StormOp.LoadTop -> {
                pager.dispatch(WindowIntent.LoadTop)
            }

            StormOp.LoadBottom -> {
                pager.dispatch(WindowIntent.LoadBottom)
            }

            StormOp.ClearJump -> {
                pager.dispatch(WindowIntent.ClearJumpState)
            }

            StormOp.Snapshot -> {
                val current = pager.state.value.items
                if (current.isNotEmpty()) {
                    ds.emitSnapshot(current.map { it.copy(payload = "live$iteration") })
                }
            }
        }
    }

    /** Ждёт покоя: оба направления не Loading и снимок стабилен подряд; НЕ падает по бюджету. */
    private suspend fun WindowPager<StormItem>.settleIdle(budgetMs: Long = SETTLE_BUDGET_MS) {
        val start = TimeSource.Monotonic.markNow()
        var stableStreak = 0
        var previous: Presence<WindowState<StormItem>> = Presence.Absent
        while (start.elapsedNow() < budgetMs.milliseconds && stableStreak < STABLE_STREAK) {
            val snapshot = state.value
            val idle =
                snapshot.loadStateTop !is LoadState.Loading &&
                    snapshot.loadStateBottom !is LoadState.Loading
            if (idle && Presence.Present(snapshot) == previous) stableStreak++ else stableStreak = 0
            previous = Presence.Present(snapshot)
            delay(POLL_MS)
        }
    }

    /** Ждёт, пока значение StateFlow не перестанет меняться подряд; НЕ падает по бюджету. */
    private suspend fun <T : Any> StateFlow<T>.settleStable(budgetMs: Long = SETTLE_BUDGET_MS) {
        val start = TimeSource.Monotonic.markNow()
        var stableStreak = 0
        var previous: Presence<T> = Presence.Absent
        while (start.elapsedNow() < budgetMs.milliseconds && stableStreak < STABLE_STREAK) {
            val snapshot = value
            if (Presence.Present(snapshot) == previous) stableStreak++ else stableStreak = 0
            previous = Presence.Present(snapshot)
            delay(POLL_MS)
        }
    }

    /** Поллинг по монотонным часам — единственный надёжный гейт на чужом диспетчере. */
    private suspend fun awaitCondition(
        budgetMs: Long = SETTLE_BUDGET_MS,
        condition: () -> Boolean,
    ): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < budgetMs.milliseconds) {
            if (condition()) return true
            delay(POLL_MS)
        }
        return condition()
    }

    // ─── Обвязка mapped-части ────────────────────────────────────────────────────────

    /** Генерация закодирована и в items, и в pendingFocus — один ассерт ловит обе поломки. */
    private fun generationState(generation: Int): WindowState<String> =
        WindowState(
            items = persistentListOf("g$generation-a", "g$generation-b"),
            pendingFocus = Focus.Item("g$generation"),
        )

    /** `g12-a` и `G12-A` → 12. */
    private fun generationOf(value: String): Int = value.drop(1).substringBefore('-').toInt()

    // ─── Фейки ───────────────────────────────────────────────────────────────────────

    /**
     * Плотный потокобезопасный источник 1..[totalItems] с seeded-латентностью.
     * Арифметика окна — та же, что у `WindowPagerAngryTest.FakeDataSource`
     * (тот для реальных потоков не годится: `loadCalls` — обычный MutableList).
     */
    private class DenseDataSource(
        private val totalItems: Int,
    ) : WindowDataSource<StormItem> {
        override val comparator: Comparator<StormItem> = Comparator { left: StormItem, right: StormItem -> right.index.compareTo(left.index) }

        override val itemId: (StormItem) -> String = { it.id }

        private val snapshots = MutableSharedFlow<List<StormItem>>(replay = 1, extraBufferCapacity = 16)

        @Volatile
        var latencyMs: (LoadParams) -> Long = { 0L }

        suspend fun emitSnapshot(items: List<StormItem>) {
            snapshots.emit(items)
        }

        override suspend fun load(params: LoadParams): LoadResult<StormItem> {
            val latency = latencyMs(params)
            if (latency > 0L) delay(latency)
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
        ): Flow<List<StormItem>> = snapshots

        override fun getSortKey(item: StormItem): Long = item.index.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) = Unit

        private fun loadInitial(
            anchorId: InitialPosition,
            limit: Int,
        ): LoadResult<StormItem> {
            val anchor = when (anchorId) {
            InitialPosition.Newest -> 0
            is InitialPosition.Around -> runCatching { anchorId.id.toInt() }.getOrDefault(0)
        }
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
        ): LoadResult<StormItem> {
            val anchor = anchorId.toInt()
            val items = ((anchor - 1) downTo maxOf(anchor - limit, 1)).map { StormItem(it) }
            return LoadResult(items = items, hasMore = anchor - 1 > items.size)
        }

        private fun loadAfter(
            anchorId: String,
            limit: Int,
        ): LoadResult<StormItem> {
            val anchor = anchorId.toInt()
            val items = (minOf(anchor + limit, totalItems) downTo anchor + 1).map { StormItem(it) }
            return LoadResult(items = items, hasMore = totalItems - anchor > items.size)
        }

        private fun loadAround(
            anchorId: String,
            limit: Int,
        ): LoadResult<StormItem> {
            val anchor =
                runCatching { anchorId.toInt() }.getOrElse { return LoadResult(items = emptyList(), hasMore = false) }
            if (anchor < 1 || anchor > totalItems) return LoadResult(items = emptyList(), hasMore = false)
            val items = around(anchor, limit)
            return LoadResult(items = items, hasMore = items.size == limit)
        }

        private fun newest(limit: Int): List<StormItem> = (totalItems downTo maxOf(totalItems - limit + 1, 1)).map { StormItem(it) }

        private fun around(
            anchor: Int,
            limit: Int,
        ): List<StormItem> {
            val newerCount = limit / 2
            val olderCount = limit - newerCount - 1
            val newer = (minOf(anchor + newerCount, totalItems) downTo anchor + 1).map { StormItem(it) }
            val older = ((anchor - 1) downTo maxOf(anchor - olderCount, 1)).map { StormItem(it) }
            return newer + StormItem(anchor) + older
        }
    }

    /** Тот же минимальный фейк, что в соседних mapped-тестах (там он private). */
    private class FakeWindowController(
        private val stateFlow: MutableStateFlow<WindowState<String>>,
        override val config: WindowPagingConfig = WindowPagingConfig(),
    ) : WindowController<String> {
        override val state: StateFlow<WindowState<String>> = stateFlow

        override val itemId: (String) -> String = { it }

        override fun dispatch(intent: WindowIntent) = Unit
    }

    private companion object {
        /** Поднимать при расследовании флейка, но не менять молча: воспроизводимость важнее. */
        const val SEED = 20260826L

        const val ITERATIONS = 100
        const val WORKERS = 4
        const val OPS_PER_ITERATION = 10
        const val OPS_PER_WORKER = (OPS_PER_ITERATION + WORKERS - 1) / WORKERS

        const val UNIVERSE = 1000
        const val PAGE_SIZE = 20
        const val PREFETCH_DISTANCE = 4
        const val MAX_CACHED_ITEMS = 60
        const val START_ANCHOR = "500"

        /**
         * Цель liveness-пробы. Обычно вне окна после шторма (прыжок-replace), но если шторм
         * докатил окно до неё — легален и short-circuit «уже в окне»: ассерт покрывает оба пути.
         */
        const val PROBE_TARGET = "42"

        /** Разнесены дальше, чем окно проезжает за итерацию → прыжки идут по ветке replace. */
        val LANDING_ZONES = intArrayOf(150, 400, 650, 900)

        const val MAX_LATENCY_MS = 3
        const val LATENCY_POOL_SIZE = 64

        const val GENERATIONS = 160
        const val POISON_PROBE = GENERATIONS - 1
        const val POISON_PERCENT = 20
        const val BURST_BREATH = 7
        const val MAX_TRANSFORM_DELAY_MS = 2L

        const val CLOSE_RACE_ITERATIONS = 40
        const val PRE_CLOSE_GENERATIONS = 8
        const val POST_CLOSE_GENERATIONS = 8
        const val TRANSFORM_HOLD_MS = 20L
        const val FREEZE_TAIL_MS = 40L

        const val POLL_MS = 5L
        const val SETTLE_BUDGET_MS = 10_000L
        const val STABLE_STREAK = 3
    }
}
