package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/** Элемент тестовой вселенной; переиспользуем публичный фейк-тип из commonTest. */
private typealias Item = WindowPagerAngryTest.TestItem

/**
 * CS-2. Класс отказов, который РУКАМИ не воспроизводится: `jumpTo` конкурирует с уже улетевшей
 * edge-загрузкой, а `MappedWindowController` получает burst живее, чем считается его `transform`.
 *
 * Почему ручная проверка бессильна:
 * - на тёплом кэше `Around` возвращается быстрее, чем UI успевает доскроллить, поэтому окно
 *   «кто успел первым» у тестировщика всегда одно и то же;
 * - существующие тесты `jumpTo` живут на `StandardTestDispatcher`: виртуальное время сериализует
 *   корутины, и `10 быстрых JumpTo подряд` зелёные при всех дефектах, зафиксированных ниже;
 * - `transform` в проде ходит в БД (`MessageRepositoryImpl.mapList { ... messageDao... }`), и
 *   его отказ/задержка возникают ровно на одном снапшоте из сотни.
 *
 * Что здесь есть:
 * - J1/M1 — генеративные штормы на РЕАЛЬНОМ `Dispatchers.Default` (seed фиксирован, лог операций
 *   в каждом ассерте — падение обязано быть воспроизводимым);
 * - J2/J3/M3 — детерминированные репро конкретных interleaving'ов через гейты вместо гонки.
 *
 * ⚠ J2, J3 и M3 ПАДАЮТ на текущем прод-коде: это не флейк и не «плохой тест», а зафиксированные
 * дефекты (см. KDoc каждого). Не отключай их и не ослабляй ассерты — либо чинится прод, либо
 * (осознанным решением владельца) тест переворачивается в характеризационный с TODO на фикс.
 *
 * jvmTest, а не commonTest: нужны `runBlocking` и многопоточный `Dispatchers.Default`
 * (js/wasm однопоточны) — та же мотивация, что у [WindowPagerConcurrencyStressTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowPagingJumpAndMapStressTest {
    // ─── J1: шторм jumpTo × edge-load × live-snapshot ────────────────────────────────

    /**
     * Эталонный инвариант (ожидается ЗЕЛЁНЫМ): на ЛЮБОЙ последовательности операций из алфавита
     * {JumpTo(zone), LoadTop, LoadBottom, live-snapshot} окно в покое строго убывает по
     * `dataSource.comparator`, не содержит двух элементов с одинаковым `itemId`, не пустеет и не
     * содержит элементов вне вселенной.
     *
     * Почему это важно именно на реальных потоках: потребитель — `LazyColumn` с
     * `key = pagingItems.getId(index)`; дубль ключа = `IllegalArgumentException` = краш ленты, а
     * сбитый порядок = перемешанные сообщения. `normalize()` применяется в пяти разных местах, и
     * достаточно одному `apply` уехать из-под mutex, чтобы окно собралось из двух несогласованных
     * половин. На `StandardTestDispatcher` такой interleaving недостижим.
     *
     * Инвариант проверяется ПОСЛЕ КАЖДОЙ операции (в том воркере, который её выполнил), а не
     * только в конце: первое нарушение фиксируется атомарно вместе с seed и логом операций.
     *
     * Про loadState здесь намеренно НЕТ ассертов — «не завис в Loading» проверяется
     * детерминированно в J3, потому что в шторме этот инвариант нарушается недетерминированно
     * (см. `ClearJumpState во время jump-загрузки ...`).
     */

    @Test
    fun `шторм jumpTo и edge-load - окно остаётся отсортированным сегментом без дублей`(): Unit =
        runBlocking {
            repeat(ITERATIONS) { iteration ->
                val rnd = Random(SEED + iteration)
                val ops = List(OPS_PER_ITERATION) { randomOp(rnd) }
                // Латентности берём из ЗАРАНЕЕ посчитанного seeded-пула: kotlin.random.Random не
                // потокобезопасен, а дёргать его из воркеров — это и гонка, и потеря воспроизводимости.
                val latencyPool = IntArray(LATENCY_POOL_SIZE) { rnd.nextInt(0, MAX_LATENCY_MS + 1) }
                val latencyCursor = AtomicInteger(0)

                val ds = GatedDataSource(UNIVERSE)
                ds.latencyMs = { latencyPool[latencyCursor.getAndIncrement() % latencyPool.size].toLong() }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val pager = stressPager(ds, scope)

                val plan = ops.joinToString(",") { it.label }
                val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
                val violation = AtomicReference<Presence<String>>(Presence.Absent)

                try {
                    // Ждём именно СОБЫТИЕ «bootstrap собрал окно», а не «снимок перестал меняться»:
                    // пустое окно тоже стабильно, и на загруженном раннере settle вернулся бы до
                    // старта bootstrap'а — получили бы ложное «окно опустело».
                    assertTrue(
                        awaitCondition {
                            pager.state.value.items
                                .isNotEmpty()
                        },
                        "bootstrap не собрал стартовое окно - ${diagnostics(iteration, plan, emptyList(), "bootstrap")}",
                    )
                    pager.settle()
                    recordViolation(violation, pager.state.value, ds, iteration, plan, executed, "bootstrap")

                    coroutineScope {
                        ops.chunked(OPS_PER_WORKER).forEach { slice ->
                            launch(Dispatchers.Default) {
                                slice.forEach { op ->
                                    applyOp(pager, ds, op, iteration)
                                    executed += op.label
                                    recordViolation(violation, pager.state.value, ds, iteration, plan, executed, op.label)
                                }
                            }
                        }
                    }

                    pager.settle()
                    val state = pager.state.value
                    recordViolation(violation, state, ds, iteration, plan, executed, "settle")
                    assertEquals(Presence.Absent, violation.get(), "инвариант окна нарушен")

                    assertWindowInvariants(state, ds, diagnostics(iteration, plan, executed, "final"))
                } finally {
                    pager.close()
                    scope.cancel()
                }
            }
        }

    // ─── J2: jumpTo-replace поверх улетевшей edge-загрузки ───────────────────────────

    /**
     * ⚠ ОЖИДАЕТСЯ КРАСНЫМ — реальный дефект (окно склеивается через дыру).
     *
     * Контракт `WindowState`: «Окно содержит один непрерывный сегмент вокруг текущего фокуса».
     * После удаления gap-механики `jumpTo` без overlap ЗАМЕНЯЕТ окно новым сегментом
     * (`WindowPager.kt`, ветка 2b), но улетевший до этого `loadEdge` применяет свою страницу
     * к УЖЕ ЗАМЕНЁННОМУ окну: `normalize(current.items + result.items)` не проверяет стык.
     *
     * Отказ в проде: пользователь видит сообщение #110 прямо над #490 и не знает, что между ними
     * пропали 380 штук. Хуже: `hasMoreTop` после такого merge относится к ЧУЖОЙ половине, поэтому
     * `LoadTop` от низа окна продолжает грузить старый сегмент, а окрестность цели прыжка не
     * догрузится никогда. Прод сам считает это отказом — в `runBackgroundReconcile` есть отдельная
     * ветка «стыка нет → окно не трогаем»; в паре `jumpTo` ↔ `loadEdge` такой защиты нет.
     *
     * Гейт вместо гонки: `LoadParams.Before` виснет ровно между «anchor взят из старого окна» и
     * «результат применён», то есть в том самом окне между проверкой и действием.
     */
    @Test
    fun `jumpTo-replace во время in-flight LoadTop - окно не склеивается через дыру`() =
        runTest {
            val ds = GatedDataSource(UNIVERSE)
            val beforeGate = CompletableDeferred<Unit>()
            ds.beforeGate = beforeGate
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val pager = stressPager(ds, scope)
            try {
                advanceUntilIdle()
                assertBootstrappedAroundAnchor(pager, ds)

                // 1. LoadTop берёт anchor = низ ТЕКУЩЕГО окна (491) и виснет на гейте.
                pager.dispatch(WindowIntent.LoadTop)
                advanceUntilIdle()
                assertTrue(
                    pager.state.value.loadStateTop is LoadState.Loading,
                    "предусловие - LoadTop должен висеть на гейте Before",
                )

                // 2. JumpTo без overlap заменяет окно новым сегментом вокруг цели.
                pager.dispatch(WindowIntent.JumpTo(FAR_TARGET))
                advanceUntilIdle()
                assertEquals(
                    (110 downTo 91).toList(),
                    pager.state.value.items
                        .map { it.index },
                    "предусловие - jumpTo без overlap заменяет окно новым сегментом",
                )
                assertEquals(
                    pager.state.value.items
                        .map { it.id },
                    ds.observedWindowIds.value,
                    "предусловие - engine пересинхронизировал подписку на новое окно",
                )
                assertEquals(
                    1,
                    ds.trimCalls.get(),
                    "предусловие - jumpTo обязан trim'ить кэш вокруг нового окна",
                )

                // 3. Отпускаем страницу СТАРОГО сегмента: она приезжает в новое окно.
                beforeGate.complete(Unit)
                advanceUntilIdle()

                val indexes =
                    pager.state.value.items
                        .map { it.index }
                val gaps = indexes.zipWithNext().filter { (newer, older) -> newer - older != 1 }
                assertEquals(
                    emptyList(),
                    gaps,
                    "окно перестало быть непрерывным сегментом - разрывы=$gaps - окно=$indexes",
                )
            } finally {
                beforeGate.complete(Unit)
                pager.close()
                scope.cancel()
            }
        }

    // ─── J3: pendingFocus сменился, пока Around был в полёте ─────────────────────────

    /**
     * ⚠ ОЖИДАЕТСЯ КРАСНЫМ — реальный дефект (СМЕРТЬ ПАГИНАЦИИ, а не «залипший спиннер»).
     *
     * `jumpTo` синхронно публикует `pendingFocus`, выставляет Loading в ОБА направления и уходит
     * в `dataSource.load(Around)`. Применение результата идёт через `updatePendingJumpIfCurrent`,
     * который молча возвращает `false`, если `pendingFocus` уже сменился, — и тогда Loading
     * никто не снимает: ни успешная ветка, ни ветка ошибки, ни пустой результат.
     *
     * Дальше `canLoad` возвращает `false` для обоих направлений, `WindowPagingItems.triggerPrefetch`
     * заблокирован по `loadState != Loading`, а `retry()` реагирует только на `Error`. Лента больше
     * не догрузит ни одной страницы до пересоздания экрана.
     *
     * Прод-путь не гипотетический: `ChatFeedComponentImpl.jumpToMessage` шлёт `JumpTo` и
     * одновременно взводит скролл, а `MessageList` шлёт `OnScrollToMessageCompleted` →
     * `ClearJumpState` по путям, не закрытым проверкой pagingIdle (drag пользователя, достижение
     * дна). То есть: тапнул по цитате и смахнул список — на медленной сети лента умерла.
     * Руками не воспроизводится: на тёплом кэше `Around` успевает раньше скролла всегда.
     */
    @Test
    fun `ClearJumpState во время jump-загрузки - loadState не остаётся в Loading`() =
        runTest {
            val ds = GatedDataSource(UNIVERSE)
            val aroundGate = CompletableDeferred<Unit>()
            ds.aroundGate = aroundGate
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val pager = stressPager(ds, scope)
            try {
                advanceUntilIdle()

                pager.dispatch(WindowIntent.JumpTo(FAR_TARGET))
                advanceUntilIdle()
                assertTrue(
                    pager.state.value.loadStateTop is LoadState.Loading &&
                        pager.state.value.loadStateBottom is LoadState.Loading,
                    "предусловие - jumpTo к цели вне окна выставляет Loading в обе стороны",
                )

                // Точная копия прод-флоу onScrollToMessageCompleted: UI сообщает, что скролл выполнен.
                pager.dispatch(WindowIntent.ClearJumpState)
                aroundGate.complete(Unit)
                advanceUntilIdle()

                val state = pager.state.value
                assertFalse(
                    state.loadStateTop is LoadState.Loading,
                    "loadStateTop навсегда завис в Loading - пагинация вверх мертва",
                )
                assertFalse(
                    state.loadStateBottom is LoadState.Loading,
                    "loadStateBottom навсегда завис в Loading - пагинация вниз мертва",
                )
            } finally {
                aroundGate.complete(Unit)
                pager.close()
                scope.cancel()
            }
        }

    // ─── M1: burst под MappedWindowController ────────────────────────────────────────

    /**
     * Ожидается ЗЕЛЁНЫМ. Под burst-эмиссиями финальный снапшот `MappedWindowController`
     * соответствует ФИНАЛЬНОМУ входу `delegate.state`, а последовательность опубликованных
     * генераций никогда не убывает.
     *
     * Отказ при нарушении: «финальный != финальный вход» — лента навсегда отстала на одно
     * обновление (последнее сообщение из вебсокета не появилось, и ничто больше не триггернёт
     * пересборку, потому что `delegate.state` больше не эмитит). Перестановка генераций — лента
     * прыгает назад на устаревший список. Оба воспроизводятся только когда burst приходит быстрее,
     * чем считается `toFeedUiModel × 30`, то есть на живом чате под нагрузкой, никогда на стенде.
     *
     * Сейчас инвариант держится ровно за счёт связки StateFlow(конфлейт) + `mapLatest` + `stateIn`
     * и ломается при любой «оптимизации» вида `sample` / `debounce` / launch-на-эмиссию.
     *
     * Генерация закодирована И в items, И в `pendingFocus` — один ассерт ловит и отставание,
     * и разрыв связки items ↔ pendingFocus. Ожидаемое значение — литеральная константа
     * [GENERATIONS], а не то, что построил сам тест.
     */
    @Test
    fun `burst эмиссий - финальный снапшот mapped соответствует финальному входу`(): Unit =
        runBlocking {
            val rnd = Random(SEED)
            val transformDelays = LongArray(GENERATIONS + 1) { rnd.nextLong(1, MAX_TRANSFORM_DELAY_MS + 1) }
            val upstream = MutableStateFlow(generationState(0))
            val delegate = FakeWindowController(upstream)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Публикует transform на Dispatchers.Default, читает тест-поток → только потокобезопасный список.
            val published: MutableList<Int> = Collections.synchronizedList(mutableListOf())

            val mapped =
                delegate.map(itemId = { it }, scope = PagingScope.Borrowed(scope)) { value ->
                    delay(transformDelays[generationOf(value)])
                    value.uppercase()
                }
            val collector =
                scope.launch {
                    mapped.state.collect { snapshot ->
                        // stateIn(Eagerly) стартует с ПУСТОГО WindowState — это не генерация.
                        if (snapshot.items.isNotEmpty()) published += generationOf(snapshot.items.first())
                    }
                }

            try {
                for (generation in 1..GENERATIONS) {
                    upstream.value = generationState(generation)
                    if (generation % BURST_BREATH == 0) delay(1)
                }

                val converged = awaitCondition { mapped.state.value.pendingFocus == Focus.Item("g$GENERATIONS") }
                // Коллектор — отдельная корутина: снимок state уже финальный, а запись в лог может
                // ещё не доехать. Без этого ожидания ассерт по последней записи был бы флейком.
                awaitCondition { published.isNotEmpty() && published.last() == GENERATIONS }
                val observed = published.toList()
                val diag = "seed=$SEED generations=$GENERATIONS хвост=${observed.takeLast(8)}"
                assertTrue(converged, "финальная генерация так и не опубликована - $diag")

                val state = mapped.state.value
                assertEquals(
                    listOf("G$GENERATIONS-A", "G$GENERATIONS-B"),
                    state.items,
                    "mapped отстал от финального входа - $diag",
                )
                assertEquals(
                    Focus.Item("g$GENERATIONS"),
                    state.pendingFocus,
                    "pendingFocus разошёлся с items - $diag",
                )
                assertEquals(GENERATIONS, observed.last(), "последняя опубликованная генерация - $diag")
                assertEquals(
                    observed.sorted(),
                    observed,
                    "опубликованные генерации убывали - устаревшая трансформация приземлилась поверх свежей - $diag",
                )
            } finally {
                collector.cancel()
                mapped.close()
                scope.cancel()
            }
        }

    // ─── M2: прямой страж фикса C21 на реальных потоках ──────────────────────────────

    /**
     * Ожидается ЗЕЛЁНЫМ. Трансформация, чей вход устарел, ОТМЕНЯЕТСЯ и не доходит до конца;
     * двух трансформаций одновременно не бывает никогда.
     *
     * Существующий страж C21 живёт на `UnconfinedTestDispatcher`: виртуальное время даёт один и
     * тот же порядок, реальной конкуренции там нет. Регресс `mapLatest` → `map` компилируется и
     * оставляет зелёными все тесты, кроме одного, а в проде под burst'ом вебсокета тяжёлый
     * `toMessage` / `toFeedUiModel` крутится на снимках, которые уже никому не нужны — джанк и
     * разряд батареи на активном чате. Второй сценарий (две трансформации параллельно) означал бы
     * замену `mapLatest` на launch-на-эмиссию — тогда вдобавок ломается M1 (перестановка).
     *
     * Старт трансформации ждём ПОЛЛИНГОМ по дедлайну: `yield()` на чужом диспетчере не
     * синхронизирует (оплачено двумя красными прогонами из трёх в прошлых волнах).
     */
    @Test
    fun `burst эмиссий - устаревшая трансформация отменяется на реальных потоках`(): Unit =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val started = AtomicBoolean(false)
            val completed: MutableList<Int> = Collections.synchronizedList(mutableListOf())
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)

            val upstream = MutableStateFlow(generationState(0))
            val delegate = FakeWindowController(upstream)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val mapped =
                delegate.map(itemId = { it }, scope = PagingScope.Borrowed(scope)) { value ->
                    val generation = generationOf(value)
                    val now = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { max(it, now) }
                    try {
                        if (generation == 0) {
                            started.set(true)
                            gate.await()
                        }
                        completed += generation
                        value.uppercase()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }

            try {
                assertTrue(
                    awaitCondition { started.get() },
                    "трансформация generation 0 не стартовала - тест был бы вакуумным - seed=$SEED",
                )

                // Burst: генерации 1..N приходят, пока трансформация 0 висит на гейте.
                for (generation in 1..BURST_GENERATIONS) {
                    upstream.value = generationState(generation)
                }

                assertTrue(
                    awaitCondition { mapped.state.value.pendingFocus == Focus.Item("g$BURST_GENERATIONS") },
                    "финальная генерация не опубликована - опубликовано=${mapped.state.value.pendingFocus} - seed=$SEED",
                )

                // Отпускаем гейт: с mapLatest блок generation 0 уже отменён и завершиться не может,
                // с обычным map он именно здесь доехал бы до конца.
                gate.complete(Unit)
                delay(CANCELLED_TAIL_MS)

                assertFalse(
                    completed.contains(0),
                    "устаревшая трансформация generation 0 доведена до конца - completed=$completed - seed=$SEED",
                )
                assertEquals(
                    1,
                    maxInFlight.get(),
                    "две трансформации выполнялись одновременно - mapLatest подменён на launch-на-эмиссию - seed=$SEED",
                )
            } finally {
                gate.complete(Unit)
                mapped.close()
                scope.cancel()
            }
        }

    // ─── M3: отказ transform ровно на одном снапшоте ─────────────────────────────────

    /**
     * ⚠ ОЖИДАЕТСЯ КРАСНЫМ — реальный дефект (тот же класс отказа, что уже закрыт для
     * [WindowPager] в `WindowPagerLiveUpdateFailureTest`, но не закрыт для mapped-контроллера).
     *
     * `state = delegate.state.mapLatest { transformState(it) }.stateIn(mappingScope, Eagerly, ...)`:
     * бросок из `transform` убивает шарящую корутину `stateIn` — новые снапшоты не публикуются
     * НИКОГДА, `Error`-стейта нет, `retry` не помогает, а само исключение уходит в
     * `CoroutineExceptionHandler` scope'а (его обычно нет → дефолтный обработчик платформы,
     * на Android это краш процесса).
     *
     * Прод: `MessageRepositoryImpl` вызывает `mapList { items.toMessages { messageDao... } }` БЕЗ
     * scope, то есть на собственном `SupervisorJob + Dispatchers.Default` без обработчика. Один
     * SQLITE_BUSY / таймаут web-worker'а в момент burst'а — и лента молча замирает навсегда.
     * Воспроизвести руками нельзя: нужно, чтобы БД бросила ровно на одном снапшоте.
     *
     * Потоки тут не нужны — нужен ровно один порядок событий, поэтому детерминированный runTest.
     */
    @Test
    fun `падение transform не убивает поток состояний mapped-контроллера`() =
        runTest {
            val uncaught = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() +
                        StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error -> uncaught += error },
                )
            val upstream = MutableStateFlow(WindowState(items = persistentListOf("ok1")))
            val delegate = FakeWindowController(upstream)
            val mapped =
                delegate.map(itemId = { it }, scope = PagingScope.Borrowed(scope)) { value ->
                    if (value == POISON_ITEM) throw IllegalStateException("SQLITE_BUSY")
                    value.uppercase()
                }
            try {
                advanceUntilIdle()
                assertEquals(
                    listOf("OK1"),
                    mapped.state.value.items,
                    "предусловие - первый снапшот опубликован",
                )

                upstream.value = WindowState(items = persistentListOf(POISON_ITEM))
                advanceUntilIdle()
                assertEquals(
                    emptyList(),
                    uncaught.map { it.toString() },
                    "исключение transform ушло за пределы контроллера - на Android это краш процесса",
                )

                upstream.value = WindowState(items = persistentListOf("ok2"))
                advanceUntilIdle()
                assertEquals(
                    listOf("OK2"),
                    mapped.state.value.items,
                    "поток состояний умер после одного отказа transform - лента замерла навсегда",
                )
            } finally {
                mapped.close()
                scope.cancel()
            }
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

        data object Snapshot : StormOp {
            override val label: String get() = "Snapshot"
        }
    }

    /**
     * Landing-зоны разнесены дальше, чем окно способно проехать за одну итерацию
     * ([OPS_PER_ITERATION] × [PAGE_SIZE]), поэтому прыжок почти всегда идёт по ветке replace —
     * той самой, которая осталась без стресса после удаления gap-механики.
     */
    private fun randomOp(rnd: Random): StormOp =
        when (rnd.nextInt(4)) {
            0 -> StormOp.JumpFar(LANDING_ZONES[rnd.nextInt(LANDING_ZONES.size)])
            1 -> StormOp.LoadTop
            2 -> StormOp.LoadBottom
            else -> StormOp.Snapshot
        }

    private suspend fun applyOp(
        pager: WindowPager<Item>,
        ds: GatedDataSource,
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

            StormOp.Snapshot -> {
                val current = pager.state.value.items
                if (current.isNotEmpty()) {
                    ds.emitSnapshot(current.map { it.copy(payload = "live$iteration") })
                }
            }
        }
    }

    private fun diagnostics(
        iteration: Int,
        plan: String,
        executed: List<String>,
        stage: String,
    ): String = "seed=$SEED it=$iteration stage=$stage plan=[$plan] executed=[${executed.joinToString(",")}]"

    /** Фиксирует ПЕРВОЕ нарушение: последующие уже вторичны и только зашумляют сообщение. */
    private fun recordViolation(
        sink: AtomicReference<Presence<String>>,
        state: WindowState<Item>,
        ds: GatedDataSource,
        iteration: Int,
        plan: String,
        executed: List<String>,
        stage: String,
    ) {
        val items = state.items
        // toList() под капотом идёт через synchronized toArray(), поэтому лог воркеров читать безопасно.
        val diag = diagnostics(iteration, plan, executed.toList(), stage)
        val problem: String =
            when {
                items.isEmpty() -> {
                    "окно опустело - $diag"
                }

                items.map(ds.itemId).let { it.distinct() != it } -> {
                    "дубликаты id в окне - ключи LazyColumn перестали быть уникальными - " +
                        "окно=${items.map { it.index }} - $diag"
                }

                items.sortedWith(ds.comparator) != items -> {
                    "порядок DESC нарушен - окно=${items.map { it.index }} - $diag"
                }

                items.any { it.index < 1 || it.index > UNIVERSE } -> {
                    "в окне элемент вне вселенной - окно=${items.map { it.index }} - $diag"
                }

                else -> {
                    return
                }
            }
        sink.compareAndSet(Presence.Absent, Presence.Present(problem))
    }

    /**
     * Ждёт, пока снимок перестанет меняться [STABLE_STREAK] опросов подряд, но НЕ падает по
     * дедлайну: иначе дефект «loadState завис в Loading» выродился бы в невнятный таймаут вместо
     * предметного ассерта с seed и логом операций. Бюджет — по времени, а не по числу опросов:
     * счётчик итераций даёт латентный флейк на загруженном CI.
     */
    private suspend fun WindowPager<Item>.settle(budgetMs: Long = SETTLE_BUDGET_MS) {
        val start = TimeSource.Monotonic.markNow()
        var stableStreak = 0
        var previous: Presence<WindowState<Item>> = Presence.Absent
        while (start.elapsedNow() < budgetMs.milliseconds && stableStreak < STABLE_STREAK) {
            val snapshot = state.value
            if (Presence.Present(snapshot) == previous) stableStreak++ else stableStreak = 0
            previous = Presence.Present(snapshot)
            delay(POLL_MS)
        }
    }

    /** Поллинг наблюдаемого состояния по дедлайну — единственный надёжный гейт на чужом диспетчере. */
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

    /** Генерация кодируется И в items, И в pendingFocus — один ассерт ловит обе поломки. */
    private fun generationState(generation: Int): WindowState<String> =
        WindowState(
            items = persistentListOf("g$generation-a", "g$generation-b"),
            pendingFocus = Focus.Item("g$generation"),
        )

    /** `g12-a` и `G12-A` → 12. */
    private fun generationOf(value: String): Int = value.drop(1).substringBefore('-').toInt()

    // ─── Фейки ───────────────────────────────────────────────────────────────────────

    /**
     * Плотный источник 1..[totalItems] с гейтами по типу [LoadParams] (детерминированные репро)
     * и seeded-латентностью (шторм). Арифметика окна — копия `WindowPagerAngryTest.FakeDataSource`:
     * окно вокруг anchor'а = `limit/2` новее + anchor + остаток старее.
     */
    private class GatedDataSource(
        private val totalItems: Int,
    ) : WindowDataSource<Item> {
        override val comparator: Comparator<Item> = Comparator { left: Item, right: Item -> right.index.compareTo(left.index) }

        override val itemId: (Item) -> String = { it.id }

        private val snapshots = MutableSharedFlow<List<Item>>(replay = 1, extraBufferCapacity = 16)

        /** Ссылки, которые engine передал в подписку: бесплатная проверка синхронизации на покое. */
        @Volatile
        var observedWindowIds: StateFlow<List<String>> = MutableStateFlow(emptyList())
            private set

        @Volatile
        var observedLiveTail: StateFlow<Boolean> = MutableStateFlow(false)
            private set

        val trimCalls = AtomicInteger(0)

        var initialGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)
        var beforeGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)
        var afterGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)
        var aroundGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)

        @Volatile
        var latencyMs: (LoadParams) -> Long = { 0L }

        suspend fun emitSnapshot(items: List<Item>) {
            snapshots.emit(items)
        }

        override suspend fun load(params: LoadParams): LoadResult<Item> {
            val latency = latencyMs(params)
            if (latency > 0L) delay(latency)
            when (params) {
                is LoadParams.Initial -> initialGate.await()
                is LoadParams.Before -> beforeGate.await()
                is LoadParams.After -> afterGate.await()
                is LoadParams.Around -> aroundGate.await()
            }
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
        ): Flow<List<Item>> {
            observedWindowIds = windowIds
            observedLiveTail = observeLiveTail
            return snapshots
        }

        override fun getSortKey(item: Item): Long = item.index.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) {
            trimCalls.incrementAndGet()
        }

        private fun loadInitial(
            anchorId: InitialPosition,
            limit: Int,
        ): LoadResult<Item> {
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
        ): LoadResult<Item> {
            val anchor = anchorId.toInt()
            val items = ((anchor - 1) downTo maxOf(anchor - limit, 1)).map { Item(it) }
            return LoadResult(items = items, hasMore = anchor - 1 > items.size)
        }

        private fun loadAfter(
            anchorId: String,
            limit: Int,
        ): LoadResult<Item> {
            val anchor = anchorId.toInt()
            val items = (minOf(anchor + limit, totalItems) downTo anchor + 1).map { Item(it) }
            return LoadResult(items = items, hasMore = totalItems - anchor > items.size)
        }

        private fun loadAround(
            anchorId: String,
            limit: Int,
        ): LoadResult<Item> {
            val anchor =
                runCatching { anchorId.toInt() }.getOrElse { return LoadResult(items = emptyList(), hasMore = false) }
            if (anchor < 1 || anchor > totalItems) return LoadResult(items = emptyList(), hasMore = false)
            val items = around(anchor, limit)
            return LoadResult(items = items, hasMore = items.size == limit)
        }

        private fun newest(limit: Int): List<Item> = (totalItems downTo maxOf(totalItems - limit + 1, 1)).map { Item(it) }

        private fun around(
            anchor: Int,
            limit: Int,
        ): List<Item> {
            val newerCount = limit / 2
            val olderCount = limit - newerCount - 1
            val newer = (minOf(anchor + newerCount, totalItems) downTo anchor + 1).map { Item(it) }
            val older = ((anchor - 1) downTo maxOf(anchor - olderCount, 1)).map { Item(it) }
            return newer + Item(anchor) + older
        }
    }

    /**
     * Копия `FakeWindowController` из `WindowControllerMapAngryTest` (там он `private`),
     * урезанная до нужного здесь: делегирование `dispatch`/`close` уже покрыто тем тестом,
     * а неиспользуемые наблюдатели в фейке — рот.
     */
    private class FakeWindowController(
        private val stateFlow: MutableStateFlow<WindowState<String>>,
        override val config: WindowPagingConfig = WindowPagingConfig(),
    ) : WindowController<String> {
        override val state: StateFlow<WindowState<String>> = stateFlow

        override val itemId: (String) -> String = { it }

        override fun dispatch(intent: WindowIntent) = Unit
    }

    private companion object {
        /** Поднимать при расследовании флейка — но не менять молча: воспроизводимость важнее. */
        const val SEED = 20260821L

        /** Поднять при охоте за редким interleaving'ом. */
        const val ITERATIONS = 100
        const val WORKERS = 4
        const val OPS_PER_ITERATION = 8
        const val OPS_PER_WORKER = (OPS_PER_ITERATION + WORKERS - 1) / WORKERS

        const val UNIVERSE = 1000
        const val PAGE_SIZE = 20
        const val PREFETCH_DISTANCE = 4
        const val MAX_CACHED_ITEMS = 60
        const val START_ANCHOR = "500"
        const val FAR_TARGET = "100"

        /** Разнесены дальше, чем окно проезжает за итерацию → прыжок идёт по ветке replace. */
        val LANDING_ZONES = intArrayOf(120, 370, 620, 870)

        const val MAX_LATENCY_MS = 3
        const val LATENCY_POOL_SIZE = 64

        const val GENERATIONS = 200
        const val BURST_GENERATIONS = 32
        const val BURST_BREATH = 7
        const val MAX_TRANSFORM_DELAY_MS = 3L

        /** Форы отменённой трансформации, чтобы «доехать», если mapLatest подменят на map. */
        const val CANCELLED_TAIL_MS = 50L

        const val POLL_MS = 5L
        const val SETTLE_BUDGET_MS = 10_000L
        const val STABLE_STREAK = 3

        const val POISON_ITEM = "poison"
    }

    /** Предусловие обоих сценариев: bootstrap собрал окно вокруг anchor и не сидит на live-крае. */
    private fun assertBootstrappedAroundAnchor(
        pager: WindowPager<WindowPagerAngryTest.TestItem>,
        ds: GatedDataSource,
    ) {
        assertEquals(
            (510 downTo 491).toList(),
            pager.state.value.items
                .map { it.index },
            "предусловие - bootstrap собрал окно вокруг anchor $START_ANCHOR",
        )
        assertEquals(
            false,
            ds.observedLiveTail.value,
            "предусловие - окно вокруг anchor не на live-краю, подписка на newest-хвост лишняя",
        )
    }

    /**
     * Финальные инварианты окна явными ассертами: если инвариант когда-нибудь сломается,
     * сообщение обязано показать и seed, и обе последовательности операций (поле diag).
     */
    private fun assertWindowInvariants(
        state: WindowState<WindowPagerAngryTest.TestItem>,
        ds: GatedDataSource,
        diag: String,
    ) {
        val ids = state.items.map(ds.itemId)
        assertEquals(ids.distinct(), ids, "дубликаты id в окне - $diag")
        assertEquals(
            state.items.sortedWith(ds.comparator),
            state.items,
            "порядок DESC нарушен - $diag",
        )
    }

    /**
     * Стенд стресс-тестов: конфигурация pager'а у них одна и та же, а повтор её тела делал каждый
     * тест длиннее порога LongMethod. Вынесено по канону testing-strategy (setup > 5 строк).
     */
    private fun stressPager(
        ds: GatedDataSource,
        scope: CoroutineScope,
    ) = WindowPager(
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
}
