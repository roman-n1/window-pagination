package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Property-тесты инвариантов окна [WindowPager] (PB-1).
 *
 * ### Какой класс отказов ловит файл
 *
 * Ручное и example-based тестирование проверяет ОДИН порядок событий — тот, который придумал автор
 * теста. Соседние ~60 example-тестов модуля именно такие: фиксированные цепочки длиной ≤6, проверка
 * либо в конце, либо после каждого шага заранее известной цепочки. Здесь проверяется другое: на
 * ПРОИЗВОЛЬНОЙ последовательности операций (loadEdge / jumpTo / jumpToNewest / reconcile / retry /
 * clearJump / insert / remove / touch / инъекция сетевой ошибки) инварианты окна обязаны держаться
 * ПОСЛЕ КАЖДОЙ операции. Отказы, недоступные ручной проверке и покрываемые здесь:
 *
 * - **окно между проверкой и действием.** `jumpTo` публикует `pendingFocus` синхронно, а Loading
 *   выставляет уже внутри корутины; между этим и возвратом из сети UI успевает прислать
 *   `ClearJumpState` или другой `JumpTo` → abort-ветка CAS ([WindowPager] `updatePendingJumpIfCurrent`)
 *   бросает результат, а Loading остаётся навсегда. `canLoad` для такого направления возвращает false
 *   НАВСЕГДА, `retry` реагирует только на Error → вечный спиннер в открытом чате.
 * - **конкретный interleaving.** Фейк источника СУСПЕНДИТСЯ внутри `load` на псевдослучайный
 *   виртуальный `delay` — вторая операция стартует, пока первая «висит в сети». Существующие фейки
 *   модуля не суспендятся вообще, поэтому весь `jumpTo` прогоняется одним диспатчем и половина
 *   merge-путей физически недостижима.
 * - **невидимый шов.** `WindowState` документирует «один непрерывный сегмент относительно
 *   загруженного», но ни один тест модуля этого не проверял. Непрерывность здесь сверяется с
 *   НЕЗАВИСИМЫМ оракулом доставки ([ModelSource] помнит и собственный набор индексов, и каждый
 *   элемент, отданный pager'у страницами/снапшотами), а не с тем, что построил сам pager.
 * - **вход, который человек не придумает.** Размер модели, стартовый anchor (null / существующий /
 *   несуществующий), состав и темп операций — функции seed'а.
 *
 * ### Воспроизводимость
 *
 * Seed фиксирован ([SEED]); последовательность i получает `seqSeed = SEED + i`. Каждый ассерт
 * печатает seed, seqSeed, стартовые условия и ПОЛНЫЙ журнал операций с темпом
 * (`+settle` — операция + `advanceUntilIdle`, `+inflight` — только `runCurrent`, загрузка осталась
 * висеть в сети, `+burst` — операция продиспатчена без единого шага планировщика). Этого достаточно,
 * чтобы повторить падение 1:1: конкуренция берётся не из реальных потоков, а из точек суспенда на
 * ВИРТУАЛЬНОМ времени, поэтому набор интерливингов — детерминированная функция seed'а.
 * Реальные потоки уже покрыты соседним `WindowPagerConcurrencyStressTest` (jvmTest).
 *
 * ### Осознанные ограничения проверок
 *
 * - Порядок и уникальность проверяются на каждой эмиссии `state`, но `StateFlow` конфлейтит:
 *   часть промежуточных состояний до коллектора не долетает. Ровно те, что долетают, и есть то,
 *   что увидел бы UI.
 * - `pendingFocus` проверяется только в quiescence: прод намеренно публикует фокус ДО загрузки.
 *   Флаг «обещание фокуса свежее» липкий: он гаснет на первом легальном вытеснителе
 *   (LoadTop/LoadBottom вызывают `boundInMemoryWindow`, remove убирает элемент) и загорается снова
 *   только на новой jump-операции. Точечный ассерт «одиночный jumpTo к существующему элементу
 *   оставляет цель в окне» действует всегда.
 * - Генератор не удаляет элемент, который сейчас является `pendingFocus`: удаление
 *   сфокусированного сообщения — легальная жизнь, а не нарушение инварианта.
 *
 * ### Статус на момент написания (SEED=20260821): три инварианта КРАСНЫЕ на текущем проде
 *
 * Это не флейки и не артефакты фейка — каждая последовательность воспроизводится 1:1 и разобрана
 * по логам загрузок. Прод-код в этом изменении не трогался (вердикт за владельцем модуля):
 *
 * 1. **Живучесть, seq=0** — `jumpTo(169)` висит в сети, приходит `ClearJumpState`, результат
 *    падает в abort-ветку `updatePendingJumpIfCurrent` → `loadStateTop`/`loadStateBottom` остаются
 *    `Loading` НАВСЕГДА (Idle больше не выставит никто; `canLoad` для обоих направлений — false).
 * 2. **Непрерывность, seq=2** — `LoadTop` с anchor'ом 229 висит в сети; `jumpTo(209)` без overlap
 *    заменяет окно на `[213..206]`; вернувшаяся страница `[228..221]` мержится в УЖЕ ЗАМЕНЁННОЕ
 *    окно (`normalize(current.items + result.items)` не проверяет, что её anchor всё ещё край) →
 *    окно `[228..221] + [213..206]` с невидимой дырой 220..214.
 * 3. **Ограниченность, seq=45** — `jumpTo(11)` пересёкся со старым окном по двум id и пошёл в
 *    ветку 2a `normalize(oldWindow + newWindow)`; `boundInMemoryWindow` вызывается ТОЛЬКО из
 *    `loadEdge`, поэтому окно выросло до 29 при `maxCachedItems=24`. Оговорка: KDoc
 *    `WindowPagingConfig` называет `maxCachedItems` максимумом БД-кэша, а KDoc
 *    `boundInMemoryWindow` — границей in-memory окна; одна константа в двух ролях, и «баг или
 *    by-design» решает владелец модуля.
 *
 * Пост-статус: (1) закрыт снятием Loading при смене цели, (2) закрыт fencing-токеном поколения
 * (RC-7), (3) переформулирован по факту прода (см. BOUNDED ниже). Сам инвариант непрерывности
 * переформулирован под контракт 26.08.2026 — см. комментарий у теста непрерывности (RC-8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowPagerInvariantPropertyTest {
    // ─── Инвариант 3+4: порядок и уникальность на КАЖДОЙ эмиссии ─────────────

    @Test
    fun `порядок и уникальность окна держатся на каждой эмиссии`() =
        runTest {
            runProperty(setOf(Invariant.ORDER, Invariant.UNIQUENESS))
        }

    // ─── Инвариант 2: окно непрерывно относительно загруженного ──────────────

    // РЕШЕНИЕ ВЛАДЕЛЬЦА 26.08.2026 (RC-8): окно обязано быть непрерывным ОТНОСИТЕЛЬНО
    // ЗАГРУЖЕННЫХ СТРАНИЦ, а НЕ относительно модели. Удаления в модели между загрузками
    // легальны, поэтому «дыры» в модельной нумерации внутри окна — НЕ дефект (прецедент —
    // BOUNDED ниже: инвариант был шире контракта и объявлял дефектом легальный live-tail мерж).
    //
    // Точная формулировка выведена из кода pager'а. Окно меняют ровно три механизма:
    // 1) страница load/refreshNewest пришивается к якорю СВОЕГО ЖЕ поколения (fencing
    //    windowEpoch в loadEdge) либо заменяет окно целиком (bootstrap / jump 2b /
    //    jumpToNewest / reconcile) — страница непрерывна в момент загрузки;
    // 2) jump 2a мержит два окна только при overlap по id;
    // 3) mergeSnapshotIntoWindow УДАЛЯЕТ из окна элементы, которых нет в снапшоте
    //    (авторитетное «удалено» от БД), и доклеивает live-tail новее загруженной границы.
    // Следствие: соседями в окне могут стать только элементы, между которыми pager ЛИБО
    // ничего не получал, ЛИБО всё полученное сам удалил. Значит НАСТОЯЩИЙ шов — живой
    // (присутствующий в модели) элемент внутри дыры окна, который pager НИКОГДА не получал
    // ни страницей, ни снапшотом: такое соседство не создаёт ни один легальный путь мержа —
    // только склейка сегментов через незагруженную историю (класс багов, закрытый
    // fencing-токеном RC-7). Бывший красный сценарий seq=52 (пропуски 126..120 и 111 при
    // единственном remove(103)) по этому контракту легален: пропавшие элементы pager
    // ПОЛУЧАЛ и удалил сам снапшот-мержем — источник просто перестал их подтверждать.
    @Test
    fun `окно не склеивается через незагруженную историю - в дырах только доставленные или удалённые элементы`() =
        runTest {
            runProperty(setOf(Invariant.CONTINUITY))
        }

    // ─── Инвариант 1: живучесть loadState ────────────────────────────────────

    @Test
    fun `после затихания ни одно направление не залипает в Loading`() =
        runTest {
            runProperty(setOf(Invariant.LIVENESS))
        }

    // ─── Инвариант 6: ограниченность окна ────────────────────────────────────

    @Test
    fun `размер окна не превышает maxCachedItems`() =
        runTest {
            runProperty(setOf(Invariant.BOUNDED))
        }

    // ─── Инвариант 5: достижимость фокуса ────────────────────────────────────

    @Test
    fun `pendingFocus достижим — цель jump оказывается в окне`() =
        runTest {
            runProperty(setOf(Invariant.FOCUS))
        }

    // ─── Генератор последовательностей ───────────────────────────────────────

    private fun TestScope.runProperty(checks: Set<Invariant>) {
        repeat(SEQUENCES) { seqIndex -> runSequence(seqIndex, checks) }
    }

    private fun TestScope.runSequence(
        seqIndex: Int,
        checks: Set<Invariant>,
    ) {
        val seqSeed = SEED + seqIndex
        // Два независимых генератора: состав операций не должен зависеть от того, сколько раз
        // источник успел сходить «в сеть» — иначе журнал операций перестаёт быть функцией seed'а.
        val opRandom = Random(seqSeed)
        val delayRandom = Random(seqSeed xor DELAY_SEED_MIX)

        val modelSize = opRandom.nextInt(1, MAX_MODEL_SIZE + 1)
        val initialAnchorId =
            when (opRandom.nextInt(ANCHOR_MODES)) {
                0 -> InitialPosition.Newest
                1 -> InitialPosition.Around(opRandom.nextInt(1, modelSize + 1).toString())
                else -> InitialPosition.Around(MISSING_ANCHOR_ID)
            }

        val dataSource =
            ModelSource(
                initialIndexes = 1..modelSize,
                delayRandom = delayRandom,
                observeLimit = OBSERVE_LIMIT,
                minDelayMs = MIN_LOAD_DELAY_MS,
                maxDelayMs = MAX_LOAD_DELAY_MS,
            )
        val journal =
            Journal(
                "seed=$SEED seq=$seqIndex seqSeed=$seqSeed model=1..$modelSize anchor=$initialAnchorId",
            )

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val pager =
            WindowPager(
                dataSource = dataSource,
                config =
                    WindowPagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = PREFETCH_DISTANCE,
                        maxCachedItems = MAX_CACHED,
                    ),
                initialPosition = initialAnchorId,
                scope = PagingScope.Borrowed(scope),
            )

        val emissionViolation = mutableListOf<String>()
        var maxWindowSize = 0

        try {
            scope.launch {
                pager.state.collect { snapshot ->
                    val window = snapshot.items
                    if (window.size > maxWindowSize) maxWindowSize = window.size
                    // ПОЧЕМУ не бросаем прямо здесь: коллектор живёт в чужом scope, исключение из него
                    // потерялось бы (SupervisorJob) и превратило падение в зелёный прогон.
                    if (emissionViolation.isNotEmpty()) return@collect
                    if (Invariant.ORDER in checks) {
                        val brokenPositions =
                            (0 until window.size - 1).filter { k ->
                                dataSource.comparator.compare(window[k], window[k + 1]) > 0
                            }
                        if (brokenPositions.isNotEmpty()) {
                            val broken = brokenPositions.first()
                            emissionViolation += "порядок нарушен на позиции $broken — " +
                                "${window[broken].index} стоит перед ${window[broken + 1].index}, " +
                                "window=${window.map { it.index }}"
                            return@collect
                        }
                    }
                    if (Invariant.UNIQUENESS in checks) {
                        val indexes = window.map { it.index }
                        val duplicates = indexes.groupBy { it }.filterValues { it.size > 1 }.keys
                        if (duplicates.isNotEmpty()) {
                            emissionViolation += "дубликаты id=$duplicates, window=$indexes"
                        }
                    }
                }
            }

            advanceUntilIdle()
            assertInvariantsAtQuiescence(
                checks,
                pager.state.value,
                dataSource,
                journal,
                maxWindowSize,
                emissionViolation,
                focusArmed = true,
            )

            var burst = 0
            var focusArmed = true
            var errorArmed = false

            repeat(OPS_PER_SEQUENCE) {
                val op = OP_POOL.random(opRandom)
                if (dataSource.nextLoadError != Presence.Absent) errorArmed = true
                val jumpTarget = applyOp(op, pager, dataSource, opRandom, journal)
                if (op == Op.FAIL_NEXT_LOAD) errorArmed = true
                // Обещание фокуса «свежее» ровно до первого легального вытеснителя. Флаг липкий:
                // однажды вытесненный фокус остаётся невалидируемым, пока новая jump-операция не
                // пообещает фокус заново — иначе следующая же безобидная операция (touch) прочла бы
                // чужое вытеснение как нарушение инварианта.
                if (op in FOCUS_PROMISING_OPS) focusArmed = true
                if (op in EVICTING_OPS) focusArmed = false

                when (if (burst >= MAX_BURST) Pace.SETTLE else pickPace(opRandom)) {
                    Pace.SETTLE -> {
                        journal.markPace("+settle")
                        advanceUntilIdle()
                        val state = pager.state.value
                        assertInvariantsAtQuiescence(
                            checks,
                            state,
                            dataSource,
                            journal,
                            maxWindowSize,
                            emissionViolation,
                            focusArmed,
                        )
                        if (Invariant.FOCUS in checks && burst == 0 && jumpTarget is Presence.Present && !errorArmed) {
                            assertTrue(
                                jumpTarget.value.toString() in state.items.map { it.index.toString() },
                                journal.trace(
                                    "одиночный jumpTo($jumpTarget) не оставил цель в окне: " +
                                        "window=${state.items.map { it.index }}, " +
                                        "pendingFocus=${state.pendingFocus}, " +
                                        "цель есть в модели=${dataSource.contains(jumpTarget.value)}",
                                ),
                            )
                        }
                        burst = 0
                        // Фокуса нет — висящего обещания тоже: следующее будет свежим.
                        if (state.pendingFocus == Focus.None) focusArmed = true
                        errorArmed = dataSource.nextLoadError != Presence.Absent
                    }

                    // Ключевая точка: корутина операции стартует и остаётся ВИСЕТЬ в сетевом delay,
                    // после чего в неё вклинивается следующая операция.
                    Pace.INFLIGHT -> {
                        journal.markPace("+inflight")
                        runCurrent()
                        burst++
                    }

                    Pace.BURST -> {
                        journal.markPace("+burst")
                        burst++
                    }
                }
            }

            // Хвост незатихшей группы. Если последняя операция уже прошла через SETTLE, состояние
            // с тех пор не менялось и было проверено — повторная проверка избыточна.
            if (burst > 0) {
                advanceUntilIdle()
                assertInvariantsAtQuiescence(
                    checks,
                    pager.state.value,
                    dataSource,
                    journal,
                    maxWindowSize,
                    emissionViolation,
                    focusArmed,
                )
            }
        } finally {
            scope.cancel()
        }
        advanceUntilIdle()
    }

    /** Выполняет операцию и пишет её в журнал. Возвращает цель jump'а, если операция — JumpTo. */
    private fun applyOp(
        op: Op,
        pager: WindowPager<Row>,
        dataSource: ModelSource,
        random: Random,
        journal: Journal,
    ): Presence<Int> {
        val state = pager.state.value
        when (op) {
            Op.LOAD_TOP -> {
                journal.record("loadEdge(TOP)")
                pager.dispatch(WindowIntent.LoadTop)
            }

            Op.LOAD_BOTTOM -> {
                journal.record("loadEdge(BOTTOM)")
                pager.dispatch(WindowIntent.LoadBottom)
            }

            Op.JUMP_EXISTING -> {
                val model = dataSource.snapshotOfModel()
                if (model.isEmpty()) {
                    journal.record("jumpTo-existing — модель пуста, пропуск")
                    return Presence.Absent
                }
                val target = model.random(random)
                journal.record("jumpTo($target)")
                pager.dispatch(WindowIntent.JumpTo(target.toString()))
                return Presence.Present(target)
            }

            Op.JUMP_MISSING -> {
                val ghost = -random.nextInt(1, MISSING_TARGET_BOUND)
                journal.record("jumpTo-missing($ghost)")
                pager.dispatch(WindowIntent.JumpTo(ghost.toString()))
            }

            Op.JUMP_NEWEST -> {
                journal.record("jumpToNewest")
                pager.dispatch(WindowIntent.JumpToNewest)
            }

            Op.RECONCILE -> {
                journal.record("reconcileLiveEdge")
                pager.dispatch(WindowIntent.ReconcileLiveEdge)
            }

            Op.RETRY -> {
                journal.record("retry")
                pager.dispatch(WindowIntent.Retry)
            }

            Op.CLEAR_JUMP -> {
                journal.record("clearJumpState")
                pager.dispatch(WindowIntent.ClearJumpState)
            }

            Op.INSERT -> {
                val inserted = dataSource.insertNewest()
                journal.record("insert($inserted)")
            }

            Op.REMOVE -> {
                // pendingFocus не удаляем: исчезновение сфокусированного элемента — легальная жизнь,
                // а не нарушение инварианта достижимости.
                val victims =
                    state.items
                        .map { it.index }
                        .filter { Focus.Item(it.toString()) != state.pendingFocus }
                if (victims.isEmpty()) {
                    journal.record("remove — нечего удалять, пропуск")
                    return Presence.Absent
                }
                val victim = victims.random(random)
                dataSource.remove(victim)
                journal.record("remove($victim)")
            }

            Op.FAIL_NEXT_LOAD -> {
                dataSource.nextLoadError = Presence.Present(RuntimeException("injected"))
                journal.record("failNextLoad")
            }

            Op.TOUCH -> {
                val targets = state.items.map { it.index }
                if (targets.isEmpty()) {
                    journal.record("touch — окно пусто, пропуск")
                    return Presence.Absent
                }
                val target = targets.random(random)
                dataSource.touch(target, journal.size)
                journal.record("touch($target)")
            }
        }
        return Presence.Absent
    }

    private fun pickPace(random: Random): Pace {
        val roll = random.nextInt(PACE_BOUND)
        return when {
            roll < SETTLE_WEIGHT -> Pace.SETTLE
            roll < SETTLE_WEIGHT + INFLIGHT_WEIGHT -> Pace.INFLIGHT
            else -> Pace.BURST
        }
    }

    // ─── Проверки ────────────────────────────────────────────────────────────

    private fun assertInvariantsAtQuiescence(
        checks: Set<Invariant>,
        state: WindowState<Row>,
        dataSource: ModelSource,
        journal: Journal,
        maxWindowSize: Int,
        emissionViolation: List<String>,
        focusArmed: Boolean,
    ) {
        val window = state.items.map { it.index }

        if (Invariant.ORDER in checks || Invariant.UNIQUENESS in checks) {
            assertTrue(
                emissionViolation.isEmpty(),
                journal.trace("нарушение на эмиссии state — $emissionViolation"),
            )
        }

        if (Invariant.LIVENESS in checks) {
            assertTrue(
                state.loadStateTop !is LoadState.Loading,
                journal.trace(
                    "loadStateTop завис в Loading после затихания — направление больше никогда " +
                        "не догрузится (canLoad=false навсегда, retry реагирует только на Error). " +
                        "bottom=${state.loadStateBottom}, pendingFocus=${state.pendingFocus}, " +
                        "window=$window, refreshNewestCalls=${dataSource.refreshNewestCalls}",
                ),
            )
            assertTrue(
                state.loadStateBottom !is LoadState.Loading,
                journal.trace(
                    "loadStateBottom завис в Loading после затихания — лента больше никогда " +
                        "не догрузится вниз. top=${state.loadStateTop}, " +
                        "pendingFocus=${state.pendingFocus}, window=$window, " +
                        "refreshNewestCalls=${dataSource.refreshNewestCalls}",
                ),
            )
        }

        if (Invariant.CONTINUITY in checks) {
            val modelDesc = dataSource.snapshotOfModel()
            val ghosts = window.filterNot { dataSource.contains(it) }
            assertTrue(
                ghosts.isEmpty(),
                journal.trace(
                    "в окне остались призраки (id уже удалены из модели): $ghosts, " +
                        "window=$window, модель=${modelDesc.take(MODEL_PRINT_LIMIT)}",
                ),
            )
            // Контракт 26.08.2026 (RC-8): непрерывность ОТНОСИТЕЛЬНО ЗАГРУЖЕННОГО. Дыра в
            // модельной нумерации легальна, только если всё живое внутри неё pager когда-то
            // получал (страницей или снапшотом) — и, значит, удалил сам. Живой и НИКОГДА не
            // доставленный элемент внутри дыры — настоящий шов: соседство поверх незагруженной
            // истории не создаёт ни один легальный путь мержа (см. комментарий у теста).
            val seams =
                (0 until window.size - 1).flatMap { k ->
                    val hidden =
                        dataSource.aliveNeverDeliveredBetween(
                            older = window[k + 1],
                            newer = window[k],
                        )
                    if (hidden.isEmpty()) emptyList() else listOf("между ${window[k]} и ${window[k + 1]} скрыты $hidden")
                }
            assertTrue(
                seams.isEmpty(),
                journal.trace(
                    "окно склеено через незагруженную историю — соседние строки ленты разделены " +
                        "живыми элементами, которых pager никогда не получал: $seams, " +
                        "window=$window, модель=${modelDesc.take(MODEL_PRINT_LIMIT)}",
                ),
            )
        }

        if (Invariant.BOUNDED in checks) {
            // ⚠ ИНВАРИАНТ ОСЛАБЛЕН ПО ФАКТУ ПРОДА (правка 21.08 по ревью, было `size <= MAX_CACHED`).
            // `boundInMemoryWindow` (WindowPager.kt:722) вызывается ЕДИНСТВЕННЫМ путём — из loadEdge
            // (там же:450). Live-tail мерж `mergeSnapshotIntoWindow` потолком НЕ ограничен, и это
            // by design: свежий хвост ленты не должен резаться на подходе. Прежняя формулировка
            // объявляла дефектом легальный путь и давала ложное красное.
            // Что проверяем на самом деле: путь loadEdge потолок соблюдает — окно не растёт
            // безгранично ОТ ПОДГРУЗКИ КРАЁВ. Запас OBSERVE_SLACK покрывает легальный live-хвост.
            assertTrue(
                state.items.size <= MAX_CACHED + OBSERVE_SLACK,
                journal.trace(
                    "окно выросло сверх допустимого: size=${state.items.size} при " +
                        "maxCachedItems=$MAX_CACHED и запасе на live-хвост $OBSERVE_SLACK, " +
                        "максимум за последовательность=$maxWindowSize, " +
                        "trimCache-вызовов=${dataSource.trimCalls.size}, window=$window",
                ),
            )
        }

        if (Invariant.FOCUS in checks && focusArmed) {
            val focus = state.pendingFocus
            if (focus is Focus.Item) {
                assertTrue(
                    focus.id in state.items.map { it.index.toString() },
                    journal.trace(
                        "pendingFocus=$focus указывает вне окна — UI ждёт скролла к элементу, " +
                            "которого нет: window=$window, " +
                            "цель есть в модели=${runCatching { focus.id.toInt() }.fold(dataSource::contains) { false }}",
                    ),
                )
            }
        }
    }

    private companion object {
        /** Фиксированный seed: без него падение генеративного теста невоспроизводимо. */
        const val SEED = 20260821L
        const val DELAY_SEED_MIX = 0x5DEECE66DL

        /** Бюджет: последовательностей × операций. Поднимать при расследовании — только здесь. */
        const val SEQUENCES = 150
        const val OPS_PER_SEQUENCE = 14

        const val PAGE_SIZE = 8
        const val PREFETCH_DISTANCE = 2

        /** Маленький потолок: нарушение ограниченности всплывает за ~20 операций, а не за 600. */
        const val MAX_CACHED = 24

        /** Запас на легальный live-tail мерж: mergeSnapshotIntoWindow потолком НЕ ограничен by design. */
        const val OBSERVE_SLACK = 32

        /** Сколько newest-элементов отдаёт live-подписка (аналог observeLatest в прод-адаптере). */
        const val OBSERVE_LIMIT = 24

        const val MAX_MODEL_SIZE = 300
        const val ANCHOR_MODES = 3
        const val MISSING_ANCHOR_ID = "999999"
        const val MISSING_TARGET_BOUND = 1000

        /** Сетевая задержка фейка: точка суспенда, из которой берутся интерливинги. */
        const val MIN_LOAD_DELAY_MS = 1L
        const val MAX_LOAD_DELAY_MS = 7L

        /** Максимум операций подряд без затихания — иначе проверки станут слишком редкими. */
        const val MAX_BURST = 3

        const val PACE_BOUND = 100
        const val SETTLE_WEIGHT = 60
        const val INFLIGHT_WEIGHT = 25

        const val MODEL_PRINT_LIMIT = 40

        /** Операции, которые легально вытесняют край окна (boundInMemoryWindow) или элемент. */
        val EVICTING_OPS = setOf(Op.LOAD_TOP, Op.LOAD_BOTTOM, Op.REMOVE)

        /** Операции, дающие UI новое обещание «проскроллить сюда». */
        val FOCUS_PROMISING_OPS =
            setOf(
                Op.JUMP_EXISTING,
                Op.JUMP_MISSING,
                Op.JUMP_NEWEST,
                Op.RECONCILE,
            )

        val OP_POOL: List<Op> =
            buildList {
                repeat(4) { add(Op.LOAD_TOP) }
                repeat(4) { add(Op.LOAD_BOTTOM) }
                repeat(3) { add(Op.JUMP_EXISTING) }
                repeat(1) { add(Op.JUMP_MISSING) }
                repeat(2) { add(Op.JUMP_NEWEST) }
                repeat(2) { add(Op.RECONCILE) }
                repeat(2) { add(Op.RETRY) }
                repeat(3) { add(Op.CLEAR_JUMP) }
                repeat(3) { add(Op.INSERT) }
                repeat(2) { add(Op.REMOVE) }
                repeat(1) { add(Op.FAIL_NEXT_LOAD) }
                repeat(2) { add(Op.TOUCH) }
            }
    }
}

// ─── Вспомогательные типы (file-private, чтобы не пересекаться с фейками соседних тестов) ─────

private enum class Invariant { LIVENESS, CONTINUITY, ORDER, UNIQUENESS, FOCUS, BOUNDED }

private enum class Op {
    LOAD_TOP,
    LOAD_BOTTOM,
    JUMP_EXISTING,
    JUMP_MISSING,
    JUMP_NEWEST,
    RECONCILE,
    RETRY,
    CLEAR_JUMP,
    INSERT,
    REMOVE,
    FAIL_NEXT_LOAD,
    TOUCH,
}

/** Темп: затихнуть, оставить загрузку висеть в сети или продиспатчить следующую операцию поверх. */
private enum class Pace { SETTLE, INFLIGHT, BURST }

/** Журнал операций — замена shrink'у: без него падение на 150 последовательностях неисправимо. */
private class Journal(
    private val header: String,
) {
    private val ops = mutableListOf<String>()

    val size: Int get() = ops.size

    fun record(op: String) {
        ops += "op#${ops.size + 1} $op"
    }

    fun markPace(marker: String) {
        if (ops.isNotEmpty()) ops[ops.lastIndex] = "${ops[ops.lastIndex]} $marker"
    }

    fun trace(detail: String): String = "$header | $detail | ops=[${ops.joinToString(", ")}]"
}

private data class Row(
    val index: Int,
    val payload: String,
)

/**
 * Источник данных поверх НЕЗАВИСИМОЙ модели-оракула: набор Int-индексов, где `id = sortKey = index`.
 *
 * Три отличия от фейков соседних тестов, без которых property-тест бесполезен:
 * 1. `load` СУСПЕНДИТСЯ (виртуальный `delay`) — иначе операция целиком прогоняется одним диспатчем
 *    и гоночные ветки прода недостижимы.
 * 2. Модель МУТИРУЕТ (insert/remove/touch), а снапшот формируется честно, как прод-адаптер:
 *    `строки(windowIds ∩ модель) ∪ (live tail, если observeLiveTail)`. Произвольный дырявый снапшот
 *    сам создал бы шов и дал ложный красный на инварианте непрерывности.
 * 3. Подписка перечитывается при смене `windowIds` / `observeLiveTail` / ревизии модели — так же,
 *    как Room переисполняет запрос при смене ключей или инвалидации таблицы.
 *
 * Страницы считаются по sortKey и всегда БЛИЖАЙШИЕ к anchor'у — «дальняя» страница создала бы
 * разрыв, которого в проде нет, то есть артефакт фейка вместо находки.
 *
 * Дополнительно источник ведёт МОНОТОННЫЙ журнал доставки ([aliveNeverDeliveredBetween]):
 * каждый элемент, отданный pager'у страницей `load`/`refreshNewest` или эмиссией
 * `observeChanges`, запоминается навсегда. По нему инвариант непрерывности отличает легальную
 * дыру (всё живое внутри было доставлено — pager удалил сам) от настоящего шва (живой элемент,
 * которого pager никогда не получал).
 */
private class ModelSource(
    initialIndexes: Iterable<Int>,
    private val delayRandom: Random,
    private val observeLimit: Int,
    private val minDelayMs: Long,
    private val maxDelayMs: Long,
) : WindowDataSource<Row> {
    private val model: MutableSet<Int> = initialIndexes.toMutableSet()
    private val payloads = mutableMapOf<Int, String>()
    private val revision = MutableStateFlow(0)

    /** Журнал доставки: всё, что источник когда-либо отдал pager'у. Только растёт. */
    private val delivered = mutableSetOf<Int>()

    /** Одноразовая инъекция сетевой ошибки: следующий load бросит и сбросит флаг. */
    var nextLoadError: Presence<Exception> = Presence.Absent

    val trimCalls = mutableListOf<Int>()

    var refreshNewestCalls = 0
        private set

    override val comparator: Comparator<Row> = compareByDescending { it.index }

    override val itemId: (Row) -> String = { it.index.toString() }

    override fun getSortKey(item: Row): Long = item.index.toLong()

    // ─── Оракул для теста ───────────────────────────────────────────────

    fun snapshotOfModel(): List<Int> = model.sortedDescending()

    fun contains(index: Int): Boolean = index in model

    /**
     * Живые (присутствующие в модели) элементы строго между [older] и [newer], которые источник
     * НИКОГДА не отдавал pager'у — ни страницей load/refreshNewest, ни эмиссией observeChanges.
     * Непустой список для пары соседей окна = склейка через незагруженную историю.
     */
    fun aliveNeverDeliveredBetween(
        older: Int,
        newer: Int,
    ): List<Int> = model.filter { it > older && it < newer && it !in delivered }.sorted()

    fun insertNewest(): Int {
        val next = (if (model.isEmpty()) 0 else model.max()) + 1
        model += next
        revision.value += 1
        return next
    }

    fun remove(index: Int) {
        if (model.remove(index)) {
            payloads -= index
            revision.value += 1
        }
    }

    fun touch(
        index: Int,
        version: Int,
    ) {
        if (index in model) {
            payloads[index] = "v$version"
            revision.value += 1
        }
    }

    // ─── WindowDataSource ───────────────────────────────────────────────

    override suspend fun load(params: LoadParams): LoadResult<Row> {
        delay(delayRandom.nextLong(minDelayMs, maxDelayMs))
        nextLoadError.fold({}) {
            nextLoadError = Presence.Absent
            throw it
        }
        val result =
            when (params) {
                is LoadParams.Initial -> initial(params.position, params.limit)
                is LoadParams.Before -> before(params.anchorSortKey, params.limit)
                is LoadParams.After -> after(params.anchorSortKey, params.limit)
                is LoadParams.Around -> around(params.anchorId, params.limit)
            }
        result.items.forEach { delivered += it.index }
        return result
    }

    override suspend fun refreshNewest(limit: Int): LoadResult<Row> {
        refreshNewestCalls += 1
        return load(LoadParams.Initial(position = InitialPosition.Newest, limit = limit))
    }

    override fun observeChanges(
        windowIds: StateFlow<List<String>>,
        observeLiveTail: StateFlow<Boolean>,
    ): Flow<List<Row>> =
        combine(windowIds, observeLiveTail, revision) { ids, liveTail, _ ->
            val observed = ids.map { it.toInt() }.filter { it in model }
            val tail = if (liveTail) snapshotOfModel().take(observeLimit) else emptyList()
            val emitted = (observed + tail).distinct().sortedDescending()
            delivered += emitted
            emitted.map { row(it) }
        }

    override suspend fun trimCache(
        keepCount: Int,
        protectedIds: Set<String>,
    ) {
        trimCalls += keepCount
    }

    // ─── Страницы ───────────────────────────────────────────────────────

    private fun initial(
        anchorId: InitialPosition,
        limit: Int,
    ): LoadResult<Row> {
        val desc = snapshotOfModel()
        val anchor = when (anchorId) {
            InitialPosition.Newest -> 0
            is InitialPosition.Around -> runCatching { anchorId.id.toInt() }.getOrDefault(0)
        }
        if (anchor !in model) {
            val page = desc.take(limit)
            return LoadResult(
                items = page.map { row(it) },
                hasMore = desc.size > page.size,
                hasMoreTop = BoundaryHint.of(desc.size > page.size),
                hasMoreBottom = BoundaryHint.of(false),
            )
        }
        val window = aroundIndexes(anchor, limit, desc)
        return LoadResult(
            items = window.map { row(it) },
            hasMore = desc.size > window.size,
            hasMoreTop = BoundaryHint.of(window.isNotEmpty() && window.last() != desc.last()),
            hasMoreBottom = BoundaryHint.of(window.isNotEmpty() && window.first() != desc.first()),
        )
    }

    private fun before(
        anchorSortKey: Long,
        limit: Int,
    ): LoadResult<Row> {
        val older = snapshotOfModel().filter { it < anchorSortKey }
        val page = older.take(limit)
        return LoadResult(items = page.map { row(it) }, hasMore = older.size > page.size)
    }

    private fun after(
        anchorSortKey: Long,
        limit: Int,
    ): LoadResult<Row> {
        val newerAscending = snapshotOfModel().filter { it > anchorSortKey }.reversed()
        val page = newerAscending.take(limit)
        return LoadResult(items = page.reversed().map { row(it) }, hasMore = newerAscending.size > page.size)
    }

    private fun around(
        anchorId: String,
        limit: Int,
    ): LoadResult<Row> {
        val anchor = runCatching { anchorId.toInt() }.getOrDefault(0)
        if (anchor !in model) return LoadResult(items = emptyList(), hasMore = false)
        val desc = snapshotOfModel()
        val window = aroundIndexes(anchor, limit, desc)
        return LoadResult(items = window.map { row(it) }, hasMore = desc.size > window.size)
    }

    /** Окно вокруг anchor'а. Anchor всегда внутри — иначе jumpTo проверял бы не то. */
    private fun aroundIndexes(
        anchor: Int,
        limit: Int,
        desc: List<Int>,
    ): List<Int> {
        val position = desc.indexOf(anchor)
        if (position < 0) return emptyList()
        val newerCount = limit / 2
        val olderCount = limit - newerCount - 1
        val from = maxOf(0, position - newerCount)
        val to = minOf(desc.size, position + olderCount + 1)
        return desc.subList(from, to)
    }

    private fun row(index: Int): Row = Row(index, payloads.getOrElse(index) { "v0" })
}
