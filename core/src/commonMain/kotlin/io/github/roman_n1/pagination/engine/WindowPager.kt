package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Движок window-based пагинации.
 * Аналог Pager из Paging 3, но с поддержкой JumpTo и live-обновлений.
 *
 * Управляет окном элементов типа [T]:
 * - Загружает данные через [WindowDataSource] (сеть + БД)
 * - Хранит активное окно вокруг текущего фокуса
 * - При JumpTo: merge'ит overlap-окна, а при отсутствии overlap заменяет окно новым сегментом
 * - Синхронизирует окно с live-обновлениями из БД (websocket → DB → observeChanges)
 * - Реализует [WindowController] для использования из domain/presentation слоёв
 *
 * Элементы хранятся в порядке компаратора из [WindowDataSource.comparator]
 * (обычно DESC: index 0 = самый новый элемент).
 *
 * Инициализируется автоматически при создании (bootstrap + observeChanges).
 *
 * @param T тип элемента
 * @param dataSource источник данных — реализуется для каждого типа списка
 * @param config конфигурация пагинации
 * @param initialPosition если задан — начальное окно строится вокруг этого элемента,
 *                        иначе загружаются самые новые
 * @param recoverySignals сигналы для повтора неудачной загрузки, например восстановление сети
 * @param scope CoroutineScope для фоновых операций (загрузка, observe). `PagingScope.Owned` — pager заводит
 *              собственный scope и гасит его целиком в [close] (см. [OwnedPagingScope]).
 *              commonMain: `Dispatchers.IO` есть только на JVM/Android — свой scope нужен
 *              именно для IO-bound загрузки.
 */
class WindowPager<T : Any>(
    private val dataSource: WindowDataSource<T>,
    override val config: WindowPagingConfig = WindowPagingConfig(),
    private val initialPosition: InitialPosition = InitialPosition.Newest,
    private val recoverySignals: Flow<Unit> = emptyFlow(),
    scope: PagingScope = PagingScope.Owned,
) : WindowController<T> {
    private class JumpLoadException(
        val itemId: String,
        cause: Throwable,
    ) : Exception(cause)

    private class LiveEdgeReconcileException(
        cause: Throwable,
    ) : Exception(cause)

    private val hostScope = OwnedPagingScope(scope)
    private val pagerJob = hostScope.scope.coroutineContext.childSupervisor()
    private val pagerScope = CoroutineScope(hostScope.scope.coroutineContext + pagerJob)

    private val pageMerger = WindowPageMerger(dataSource.itemId, dataSource.comparator, config)

    private val _state = MutableStateFlow(WindowState<T>())
    override val state: StateFlow<WindowState<T>> = _state

    override val itemId: (T) -> String = dataSource.itemId

    /** Mutex для атомарных read-modify-write на _state. */
    private val mutex = Mutex()

    // ПОЧЕМУ отдельно от windowEpoch: поколение защищает элементы окна, а идентичность
    // запроса — Loading/Error. Старое завершение не вправе снять Loading нового запроса.
    // Все обращения под mutex; максимум два владельца — по одному на направление.
    private val edgeRequests = mutableMapOf<WindowLoadDirection, Any>()

    /**
     * Текущие ID элементов в окне. Передаётся в dataSource.observeChanges()
     * чтобы наблюдать только за нужными элементами, а не за всей таблицей.
     */
    private val windowIds = MutableStateFlow<List<String>>(emptyList())

    /**
     * Включать newest live-tail в observeChanges.
     * true на live-краю или при пустом окне (seed); false при скролле вверх (hasMoreBottom).
     */
    private val observeLiveTail = MutableStateFlow(true)

    /**
     * Флаг: bootstrap завершился успешно.
     * observeChanges() не seed'ит окно из DB snapshot пока bootstrap не загрузил
     * начальное окно — иначе initialAnchorId будет проигнорирован.
     * При ошибке bootstrap остаётся false — seed из observe не произойдёт,
     * пользователь сможет retry через dispatch(Retry).
     *
     * StateFlow одновременно даёт межпоточную видимость для retry() и сигнализирует
     * observeChanges(), когда можно подписаться на актуальный DB snapshot.
     */
    private val bootstrapSucceeded = MutableStateFlow(false)

    init {
        pagerScope.launch { bootstrap() }
        pagerScope.launch { syncWindowIdsFromState() }
        pagerScope.launch { observeChanges() }
        pagerScope.launch { recoverySignals.collect { retry() } }
    }

    override fun close() {
        pagerJob.cancel()
        // pagerJob — РЕБЁНОК host-scope: его отмена родителя не трогает. Если scope наш, гасим
        // и корень, иначе он остаётся Active до смерти процесса.
        hostScope.cancelIfOwned()
    }

    /**
     * Активен ли scope, заведённый самим pager'ом (наблюдаемость для тестов).
     * Чужой scope сюда не попадает — им владеет caller, и [close] его не трогает.
     */
    internal fun isOwnedScopeActive(): Boolean = hostScope.isOwnedScopeActive()

    /**
     * Реактивно синхронизирует windowIds / observeLiveTail с текущим state.
     * Когда items/hasMoreBottom меняются — observeChanges переключает запрос.
     */
    private suspend fun syncWindowIdsFromState() {
        state.collect { currentState ->
            windowIds.value = currentState.items.map { dataSource.itemId(it) }
            // Пустое окно: нужен latest для bootstrap seed. На live-краю (нет bottom gap) —
            // полный tail. Вне bottom — только observeByIds (engine всё равно не append'ит).
            observeLiveTail.value = currentState.items.isEmpty() || !currentState.hasMoreBottom
        }
    }

    /** Точка входа для всех команд. Вызывается из UI (через WindowPagingItems) или из Component. */
    override fun dispatch(intent: WindowIntent) {
        when (intent) {
            is WindowIntent.LoadTop -> loadEdge(WindowLoadDirection.TOP)
            is WindowIntent.LoadBottom -> loadEdge(WindowLoadDirection.BOTTOM)
            is WindowIntent.JumpTo -> jumpTo(intent.itemId)
            is WindowIntent.JumpToNewest -> jumpToNewest()
            is WindowIntent.ReconcileLiveEdge -> reconcileLiveEdge()
            is WindowIntent.ClearJumpState -> clearPendingFocus()
            is WindowIntent.Retry -> retry()
        }
    }

    // ─── Retry ─────────────────────────────────────────────────────────
    // Повторяет загрузку для направлений, которые находятся в состоянии Error.

    private fun retry() {
        val current = _state.value
        val errors = listOf(current.loadStateTop, current.loadStateBottom)
            .filterIsInstance<LoadState.Error>().map { it.error }
        val failedJumps = errors.filterIsInstance<JumpLoadException>()
        if (failedJumps.isNotEmpty()) {
            jumpTo(failedJumps.first().itemId)
            return
        }
        // Если bootstrap не прошёл — повторяем bootstrap, а не loadEdge
        if (!bootstrapSucceeded.value && current.loadStateBottom is LoadState.Error) {
            pagerScope.launch { bootstrap() }
            return
        }
        if (current.loadStateTop is LoadState.Error) loadEdge(WindowLoadDirection.TOP)
        if (current.loadStateBottom is LoadState.Error) {
            if (current.loadStateBottom.error is LiveEdgeReconcileException) {
                reconcileLiveEdge()
            } else {
                loadEdge(WindowLoadDirection.BOTTOM)
            }
        }
    }

    // ─── Bootstrap ───────────────────────────────────────────────────
    // Начальная загрузка окна при создании pager.
    // Если initialAnchorId задан — загружаем окно вокруг anchor'а (lastReadMessage).
    // Если нет — загружаем самые новые элементы.

    private suspend fun bootstrap() {
        mutex.withLock {
            if (_state.value.loadStateBottom == LoadState.Loading) return
            edgeRequests.remove(WindowLoadDirection.BOTTOM)
            _state.update {
                it.copy(loadStateBottom = LoadState.Loading)
            }
        }

        val result =
            try {
                dataSource.load(LoadParams.Initial(initialPosition, config.pageSize))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutex.withLock {
                    _state.update {
                        it.copy(loadStateBottom = LoadState.Error(e))
                    }
                }
                return
            }
        val window = pageMerger.normalize(result.items)

        // hasMoreBottom зависит от того, удалось ли реально построить окно вокруг anchor:
        // - initialPosition == InitialPosition.Newest → загрузили newest → снизу нет ничего
        // - anchor задан и НАЙДЕН в результате → окно вокруг anchor → могут быть элементы снизу
        // - anchor задан, но НЕ НАЙДЕН → был fallback на newest → снизу нет ничего
        //
        // Почему directional flags: один `hasMore` / `window.size >= pageSize` для around
        // часто оставлял hasMoreBottom=true у live edge (старшая половина добрала страницу) →
        // UI prefetch LoadBottom → пустой GET offset=-limit.
        val anchorFound =
            initialPosition is InitialPosition.Around &&
                window.any { dataSource.itemId(it) == initialPosition.id }
        val legacyHasMore = result.hasMore || window.size >= config.pageSize
        val hasMoreBottom = anchorFound && result.hasMoreBottom.resolve(legacyHasMore)
        val hasMoreTop = result.hasMoreTop.resolve(legacyHasMore)

        mutex.withLock {
            // окно ставится целиком — поднимаем поколение, иначе краевая загрузка,
            // стартовавшая до bootstrap'а, подмешает страницу прежнего сегмента (шов в ленте).
            windowEpoch++
            _state.update {
                it.copy(
                    items = window,
                    loadStateBottom = LoadState.Idle,
                    hasMoreTop = hasMoreTop,
                    hasMoreBottom = hasMoreBottom,
                )
            }
            bootstrapSucceeded.value = true
        }

        // Paging 3-паритет (RemoteMediator): кэш уже показан, серверная сверка — фоном.
        // Loading-стейт НЕ выставляется: у пользователя есть окно, сверка для него невидима.
        val baseline: Presence<T> = if (window.isEmpty()) Presence.Absent else Presence.Present(window.last())
        pagerScope.launch { runBackgroundReconcile(result.backgroundReconcile, baseline) }
    }

    /**
     * Фоновая серверная сверка стартового окна (см. [LoadResult.backgroundReconcile]).
     *
     * Исход выбирается по тому, что случилось с окном, пока сверка ходила в сеть.
     * «Юзер уехал в историю» = в окне появились элементы СТАРШЕ низа bootstrap-окна
     * (edge-load'ы расширяют вниз по истории); live-tail append из observeChanges добавляет
     * только НОВЕЕ верха и признаком скролла не является.
     *
     * 1. Юзер не уезжал (включая live-tail append во время сверки) → атомарный replace
     *    серверным окном и его флагами. WS-элементы новее серверного окна не теряются: они
     *    уже в БД и вернутся первым же live-tail снапшотом (окно после replace — live edge).
     *    Это же чинит шов, который live-append успел создать поверх stale-кэша.
     * 2. Юзер уехал, но серверное окно дотягивается до текущего (его низ присутствует в окне,
     *    либо в окне нет элементов старше этого низа) → merge `pageMerger.normalize(old + new)` —
     *    объединение непрерывно (паттерн jumpTo 2a). hasMoreTop юзера сохраняем.
     * 3. Юзер уехал и стыка нет (разрыв больше страницы) → окно не трогаем (не выдёргиваем
     *    из чтения), но hasMoreBottom=true: stale-край перестаёт считаться live edge,
     *    вниз лента дорастёт честными After-страницами.
     *
     * Ошибка/пустой ответ (сетевая ошибка неотличима) → окно остаётся кэшевым, Error-стейт
     * не выставляется: ошибка невидимой сверки — не ошибка загрузки. pendingFocus не трогаем —
     * принудительного скролла нет ни в одном исходе.
     */
    private suspend fun runBackgroundReconcile(
        reconcile: BackgroundReconcile<T>,
        baselineOldest: Presence<T>,
    ) {
        val fresh =
            try {
                reconcile.run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        val page = when (fresh) {
            ReconcileResult.Unchanged -> return
            is ReconcileResult.Updated -> fresh.page
        }
        if (page.items.isEmpty()) return

        val freshWindow = pageMerger.normalize(page.items)
        val freshIds = freshWindow.map(dataSource.itemId).toSet()
        val freshOldest = freshWindow.last()
        val legacyHasMore = page.hasMore || freshWindow.size >= config.pageSize
        // comparator DESC: compare(a, b) > 0 → `a` позиционируется позже → `a` СТАРШЕ.
        mutex.withLock {
            // ниже ветка `!userExtendedHistory` ЗАМЕНЯЕТ окно свежим сегментом (items =
            // freshWindow). Именно её находил property-тест: краевая загрузка, стартовавшая до
            // refresh'а, подмешивала страницу прежнего сегмента и окно склеивалось через дыру.
            // Поколение поднимаем на входе в критсекцию: остальные ветки только доливают
            // элементы к текущему окну, лишний инкремент им безвреден (в худшем случае одна
            // устаревшая страница будет отброшена и перезапрошена).
            windowEpoch++
            _state.update { current ->
                val userExtendedHistory =
                    baselineOldest.fold({ false }) { baseline ->
                        current.items.any { dataSource.comparator.compare(it, baseline) > 0 }
                    }
                val freshReachesCurrent =
                    dataSource.itemId(freshOldest) in current.items.map(dataSource.itemId).toSet() ||
                        current.items.none {
                            dataSource.comparator.compare(it, freshOldest) > 0 &&
                                dataSource.itemId(it) !in freshIds
                        }
                when {
                    !userExtendedHistory -> {
                        current.copy(
                            items = freshWindow,
                            hasMoreTop = page.hasMoreTop.resolve(legacyHasMore),
                            hasMoreBottom = page.hasMoreBottom.resolve(false),
                        )
                    }

                    freshReachesCurrent -> {
                        current.copy(
                            items = pageMerger.normalize(current.items + freshWindow),
                            hasMoreBottom = page.hasMoreBottom.resolve(false),
                        )
                    }

                    else -> {
                        current.copy(hasMoreBottom = true)
                    }
                }
            }
        }
    }

    // ─── Observe DB changes ──────────────────────────────────────────
    // Подписка на live-обновления из БД.
    // Когда websocket доставляет новое сообщение → оно upsert'ится в БД →
    // Room эмитит новый snapshot → engine мержит его в текущее окно.
    //
    // Два сценария:
    // 1. Окно пустое → seed из snapshot (fallback если bootstrap не загрузил)
    // 2. Есть элементы → обновляем существующие + append новых если мы на live-краю

    private suspend fun observeChanges() {
        bootstrapSucceeded.first { it }

        combine(state, windowIds, observeLiveTail) { currentState, ids, liveTail ->
            ids == currentState.items.map { dataSource.itemId(it) } &&
                liveTail == (currentState.items.isEmpty() || !currentState.hasMoreBottom)
        }.first { it }

        // Live-подписка — единственный путь в классе, который раньше не конвертировал ошибку в
        // состояние: pagerScope собран из SupervisorJob без CoroutineExceptionHandler, поэтому
        // бросок Room-флоу (SQLITE_BUSY при закрытии сессии, таймаут web-worker'а) уходил в
        // дефолтный обработчик платформы — на Android это краш процесса.
        try {
            collectChanges()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Окно остаётся читаемым, но перестаёт считаться live edge: без живого хвоста лента
            // должна дорастать вниз честными After-страницами, а не ждать снапшот, которого не будет.
            mutex.withLock {
                _state.update { it.copy(hasMoreBottom = true) }
            }
        }
    }

    private suspend fun collectChanges() {
        dataSource.observeChanges(windowIds, observeLiveTail).collect { snapshot ->
            val snapshotOrdered = pageMerger.normalize(snapshot)

            val current = mutex.withLock { _state.value }

            // Сценарий 1: окно пустое (bootstrap вернул пустой результат) → seed из snapshot.
            // observeLatest ограничен limit'ом, поэтому snapshot не знает точное hasMoreTop.
            // Используем optimistic check по размеру страницы: если набрали полную страницу,
            // разрешаем ещё одну подгрузку вверх.
            if (current.items.isEmpty()) {
                val seeded = snapshotOrdered.take(config.pageSize).toImmutableList()
                if (seeded.isNotEmpty()) {
                    mutex.withLock {
                        if (_state.value.items.isEmpty()) {
                            _state.update {
                                it.copy(
                                    items = seeded,
                                    hasMoreTop = seeded.size >= config.pageSize,
                                    hasMoreBottom = false,
                                    loadStateTop = LoadState.Idle,
                                    loadStateBottom = LoadState.Idle,
                                )
                            }
                        }
                    }
                }
                return@collect
            }

            // Сценарий 2: мержим snapshot в окно.
            // includeLiveTail=true если мы на самом низу (hasMoreBottom=false) —
            // тогда новые сообщения из snapshot append'ятся автоматически.
            //
            // Важно: observeChanges видит только windowIds + newest live tail, а не всю историю.
            // Поэтому по этому snapshot НЕЛЬЗЯ пересчитывать hasMoreTop/hasMoreBottom:
            // отсутствие элементов за пределами окна в snapshot не означает, что их нет в источнике.
            val merged =
                pageMerger.mergeSnapshotIntoWindow(
                    current.items,
                    snapshotOrdered,
                    includeLiveTail = !current.hasMoreBottom,
                )
            val finalWindow =
                if (merged.isEmpty() && snapshotOrdered.isNotEmpty()) {
                    snapshotOrdered.take(config.pageSize).toImmutableList()
                } else {
                    merged
                }

            mutex.withLock {
                _state.update {
                    it.copy(items = finalWindow)
                }
            }
        }
    }

    // ─── Load Edge (Top/Bottom) ──────────────────────────────────────
    // Расширение окна при скролле к краю.
    // Вызывается implicit через WindowPagingItems.operator get() — UI не знает.

    private fun loadEdge(direction: WindowLoadDirection) {
        pagerScope.launch {
            val request = Any()
            // Проверяем можно ли грузить (не loading, hasMore, есть элементы)
            var startedEpoch = 0L
            val snapshot =
                mutex.withLock {
                    val current = _state.value
                    if (!canLoad(current, direction)) return@withLock Presence.Absent
                    edgeRequests[direction] = request
                    _state.update { setLoading(it, direction, LoadState.Loading) }
                    startedEpoch = windowEpoch
                    Presence.Present(current)
                }
            if (snapshot !is Presence.Present) return@launch

            // Anchor — крайний элемент в направлении загрузки
            val anchor = anchorForDirection(snapshot.value.items, direction)
            val params = edgeLoadParams(anchor, direction)

            val result =
                try {
                    dataSource.load(params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutex.withLock {
                        if (!takeEdgeCompletion(direction, request)) return@withLock
                        val loadState = if (windowEpoch == startedEpoch) LoadState.Error(e) else LoadState.Idle
                        _state.update { setLoading(it, direction, loadState) }
                    }
                    return@launch
                }
            // trimCache НЕ вызывается при edge-load — иначе trim удалил бы только что
            // загруженные соседние страницы. Trim только при JumpTo — после смены фокуса
            // защищаем только актуальное окно.

            // Единая критическая секция: merge окна И выставление loadState/hasMore —
            // один атомарный _state.update под одним mutex. Раздельные секции публиковали
            // промежуточное состояние (items уже расширены, hasMore ещё старый), в которое
            // вклинивался observeChanges/mergeSnapshotIntoWindow с устаревшим hasMoreBottom
            // (includeLiveTail=false) и выбрасывал live-tail — порванное окно при вебсокет-трафике.
            mutex.withLock {
                if (!takeEdgeCompletion(direction, request)) return@withLock
                if (windowEpoch != startedEpoch) {
                    // Окно заменено JumpTo, пока страница летела: её элементы относятся к другому
                    // сегменту. Публиковать их — значит склеить окно через дыру. Loading снимаем,
                    // чтобы направление не залипло, hasMore не трогаем — новое окно знает своё.
                    _state.update { setLoading(it, direction, LoadState.Idle) }
                    return@withLock
                }
                _state.update { current ->
                    pageMerger.appendEdge(current, result, direction)
                }
            }
        }
    }

    private fun edgeLoadParams(
        anchor: T,
        direction: WindowLoadDirection,
    ): LoadParams {
        val anchorId = dataSource.itemId(anchor)
        val anchorSortKey = dataSource.getSortKey(anchor)
        // canonicalId — PK якоря на момент снапшота: у источников с двойной идентичностью
        // (clientMessageId ≠ server id) он избавляет от эвристического восстановления PK в БД.
        val anchorCanonicalId = dataSource.canonicalId(anchor)
        return when (direction) {
            WindowLoadDirection.TOP -> LoadParams.Before(anchorId, anchorSortKey, config.pageSize, anchorCanonicalId)
            WindowLoadDirection.BOTTOM -> LoadParams.After(anchorId, anchorSortKey, config.pageSize, anchorCanonicalId)
        }
    }

    // ─── JumpTo ──────────────────────────────────────────────────────
    // Телепортация к произвольному элементу (reply, search, pinned).
    //
    // Алгоритм:
    // 1. Элемент уже в окне → просто ставим pendingFocus (UI скроллит)
    // 2. Элемент не в окне → loadAround (загрузка окна вокруг target'а)
    //    2a. Новое окно пересекается со старым → merge в единый сегмент
    //    2b. Не пересекается → заменяем окно новым сегментом

    private fun jumpTo(itemId: String) {
        // ПОЧЕМУ синхронно: dispatch не suspend, а UI сразу проверяет,
        // не завис ли скролл. update атомарен и меняет только цель; load-переходы остаются под mutex.
        // снимая Loading обесточенного прыжка, мы освобождаем canLoad —
        // и краевая загрузка может стартовать, пока ПРЕДЫДУЩАЯ ещё в полёте. Две страницы от
        // разных якорей склеиваются с дырой между ними. Поэтому снятие Loading всегда идёт в паре
        // с подъёмом поколения: всё, что летит сейчас, объявляется устаревшим и не публикуется.
        windowEpoch++
        _state.update {
            // новый JumpTo ОБЕСТОЧИВАЕТ предыдущий — тот применяет результат только через
            // updatePendingJumpIfCurrent(старая цель), и после смены фокуса он уже не сработает.
            // Значит выставленный им Loading не снимет никто: направление залипает навсегда
            // («прыгнул к одному сообщению, передумал, прыгнул к другому — лента умерла»).
            // Особенно коварно, когда новая цель УЖЕ в окне: тогда новый jump возвращается сразу,
            // ничего не выставляя, и старый Loading остаётся единственным следом.
            // Снимаем его здесь; свой Loading новый jump поставит сам, если пойдёт грузить.
            it.copy(
                pendingFocus = Focus.Item(itemId),
                loadStateTop = it.loadStateTop.idleIfLoading(),
                loadStateBottom = it.loadStateBottom.idleIfLoading(),
            )
        }
        pagerScope.launch {
            val current = mutex.withLock { _state.value }
            if (current.pendingFocus != Focus.Item(itemId)) return@launch

            // Случай 1: элемент уже в окне
            if (current.items.any { dataSource.itemId(it) == itemId }) {
                return@launch
            }
            // Случай 2: загружаем окно вокруг target'а
            val loadStarted =
                mutex.withLock {
                    if (_state.value.pendingFocus == Focus.Item(itemId)) edgeRequests.clear()
                    updatePendingJumpIfCurrent(itemId) {
                        it.copy(
                            loadStateTop = LoadState.Loading,
                            loadStateBottom = LoadState.Loading,
                        )
                    }
                }
            if (!loadStarted) return@launch

            val result =
                try {
                    dataSource.load(LoadParams.Around(itemId, config.pageSize))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = JumpLoadException(itemId, e)
                    mutex.withLock {
                        updatePendingJumpIfCurrent(itemId) {
                            it.copy(
                                loadStateTop = LoadState.Error(failure),
                                loadStateBottom = LoadState.Error(failure),
                                pendingFocus = Focus.None,
                            )
                        }
                    }
                    return@launch
                }
            if (result.items.isEmpty()) {
                mutex.withLock {
                    updatePendingJumpIfCurrent(itemId) {
                        it.copy(
                            loadStateTop = LoadState.Idle,
                            loadStateBottom = LoadState.Idle,
                            pendingFocus = Focus.None,
                        )
                    }
                }
                return@launch
            }

            val newWindow = pageMerger.normalize(result.items)
            val oldWindow = mutex.withLock { _state.value.items }

            // Проверяем overlap: есть ли общие элементы между старым и новым окном
            val newIds = newWindow.map { dataSource.itemId(it) }.toSet()
            val oldIds = oldWindow.map { dataSource.itemId(it) }.toSet()

            if (newIds.intersect(oldIds).isNotEmpty()) {
                // 2a: overlap → merge в единый сегмент
                val merged = pageMerger.normalize(oldWindow + newWindow)
                val resultApplied =
                    mutex.withLock {
                        updatePendingJumpIfCurrent(itemId) {
                            it.copy(
                                items = merged,
                                loadStateTop = LoadState.Idle,
                                loadStateBottom = LoadState.Idle,
                                pendingFocus = Focus.Item(itemId),
                                hasMoreTop = true,
                                hasMoreBottom = true,
                            )
                        }
                    }
                if (!resultApplied) return@launch
            } else {
                // 2b: нет overlap → заменяем окно на новый сегмент.
                // Старый сегмент отбрасывается — при скролле к краю окна
                // LoadTop/LoadBottom подгрузят соседние страницы вплоть до live edge.
                val resultApplied =
                    mutex.withLock {
                        windowEpoch++
                        updatePendingJumpIfCurrent(itemId) {
                            it.copy(
                                items = newWindow,
                                loadStateTop = LoadState.Idle,
                                loadStateBottom = LoadState.Idle,
                                pendingFocus = Focus.Item(itemId),
                                hasMoreTop = true,
                                hasMoreBottom = true,
                            )
                        }
                    }
                if (!resultApplied) return@launch
            }

            // Trim DB кэш только при JumpTo — сохраняем вокруг нового окна только актуальные элементы.
            val protectedIds =
                _state.value.items
                    .map { dataSource.itemId(it) }
                    .toSet()
            dataSource.trimCache(config.maxCachedItems, protectedIds)
        }
    }

    private fun jumpToNewest() {
        pagerScope.launch {
            val alreadyAtLiveEdge =
                mutex.withLock {
                    val current = _state.value
                    if (!current.hasMoreBottom && current.items.isNotEmpty()) {
                        // перебивая pendingFocus, мы обесточиваем НЕЗАВЕРШЁННЫЙ jumpTo — его
                        // updatePendingJumpIfCurrent вернёт false, и выставленный им Loading не снимет
                        // уже никто. Направление залипает навсегда: лента перестаёт догружаться.
                        // Поэтому снимаем Loading здесь же, вместе со сменой цели.
                        windowEpoch++
                        _state.update {
                            it.copy(
                                pendingFocus = focusFor(it.items),
                                loadStateTop = it.loadStateTop.idleIfLoading(),
                                loadStateBottom = it.loadStateBottom.idleIfLoading(),
                            )
                        }
                        true
                    } else {
                        edgeRequests.remove(WindowLoadDirection.BOTTOM)
                        _state.update {
                            it.copy(
                                loadStateBottom = LoadState.Loading,
                                pendingFocus = Focus.None,
                            )
                        }
                        false
                    }
                }
            if (alreadyAtLiveEdge) return@launch

            val result =
                try {
                    dataSource.load(LoadParams.Initial(position = InitialPosition.Newest, limit = config.pageSize))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutex.withLock {
                        _state.update { it.copy(loadStateBottom = LoadState.Error(e)) }
                    }
                    return@launch
                }

            val newestWindow = pageMerger.normalize(result.items)
            val newestItemId = focusFor(newestWindow)

            mutex.withLock {
                // окно ЗАМЕНЯЕТСЯ новым сегментом — поднимаем поколение, иначе краевая
                // загрузка, стартовавшая до прыжка, подмешает страницу СТАРОГО сегмента и склеит
                // окно через дыру (в ленте невидимый шов между двумя половинами).
                windowEpoch++
                _state.update {
                    it.copy(
                        items = newestWindow,
                        loadStateTop = LoadState.Idle,
                        loadStateBottom = LoadState.Idle,
                        pendingFocus = newestItemId,
                        hasMoreTop = result.hasMore || newestWindow.size >= config.pageSize,
                        hasMoreBottom = false,
                    )
                }
            }

            val protectedIds = newestWindow.map { dataSource.itemId(it) }.toSet()
            dataSource.trimCache(config.maxCachedItems, protectedIds)
        }
    }

    /**
     * Recovery для рассинхрона «chat list уже на новом сообщении, открытая лента ещё на старом».
     * Обычный JumpToNewest на live edge намеренно short-circuit'ится, а DB observer может быть как раз
     * потерянным звеном. Поэтому этот путь всегда получает authoritative newest и публикует его прямо
     * в state pager'а, не ожидая следующей Room invalidation.
     */
    private fun reconcileLiveEdge() {
        pagerScope.launch {
            val shouldLoad =
                mutex.withLock {
                    val current = _state.value
                    if (current.loadStateBottom == LoadState.Loading) {
                        false
                    } else {
                        edgeRequests.remove(WindowLoadDirection.BOTTOM)
                        _state.update { it.copy(loadStateBottom = LoadState.Loading) }
                        true
                    }
                }
            if (!shouldLoad) return@launch

            val result =
                try {
                    dataSource.refreshNewest(config.pageSize)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = LiveEdgeReconcileException(e)
                    mutex.withLock {
                        _state.update { it.copy(loadStateBottom = LoadState.Error(failure)) }
                    }
                    return@launch
                }

            val newestWindow = pageMerger.normalize(result.items)
            if (newestWindow.isEmpty()) {
                mutex.withLock {
                    _state.update { it.copy(loadStateBottom = LoadState.Idle) }
                }
                return@launch
            }

            val newestItemId = focusFor(newestWindow)
            mutex.withLock {
                // reconcileLiveEdge ЗАМЕНЯЕТ окно свежим хвостом — поднимаем поколение.
                // Именно этот путь находил property-тест последним: последовательность
                // insert → clearJumpState(in-flight) → reconcileLiveEdge(in-flight) → loadEdge(TOP)
                // склеивала свежий хвост со страницей ПРЕЖНЕГО сегмента, и в ленте появлялся
                // невидимый шов между двумя половинами истории.
                windowEpoch++
                _state.update {
                    it.copy(
                        items = newestWindow,
                        loadStateTop = LoadState.Idle,
                        loadStateBottom = LoadState.Idle,
                        pendingFocus = newestItemId,
                        hasMoreTop =
                            result.hasMoreTop.resolve(result.hasMore || newestWindow.size >= config.pageSize),
                        hasMoreBottom = false,
                    )
                }
            }
        }
    }

    /**
     * Поколение окна. Инкрементируется, когда JumpTo ЗАМЕНЯЕТ окно новым сегментом
     * (ветка 2b, без overlap). Краевая загрузка запоминает поколение на старте и отказывается
     * применять свой результат, если окно с тех пор заменили: иначе страница из СТАРОГО сегмента
     * подмешивается в новый и окно склеивается через дыру — в ленте невидимый шов, а прокрутка
     * между двумя половинами показывает несуществующую последовательность сообщений.
     * Краевые загрузки читают его под [mutex]; JumpTo/ClearJumpState инвалидируют
     * поколение синхронно при dispatch, до запуска корутины загрузки.
     */
    private var windowEpoch: Long = 0L

    /**
     * ПОЧЕМУ снимаем и loadState, а не только фокус.
     *
     * JumpTo выставляет ОБА направления в [LoadState.Loading] (см. jumpTo выше) и применяет свой
     * результат исключительно через [updatePendingJumpIfCurrent] — то есть только пока
     * `pendingFocus` совпадает с целью. Если сбросить фокус, пока jump-загрузка ещё в полёте,
     * её результат уже не применится НИКОГДА, а выставленный ею `Loading` останется навсегда:
     * направление считается «уже грузится», новые loadEdge отбрасываются, и лента мертва до
     * пересоздания экрана. Наблюдаемый отказ: «прыгнул к сообщению, передумал, и лента больше
     * не докручивается вверх».
     *
     * Снимаем Loading только когда jump реально шёл (`pendingFocus != Focus.None`) — иначе можно
     * оборвать индикатор обычной краевой загрузки, которая к jump отношения не имеет.
     * Найдено стресс-тестом WindowPagingJumpAndMapStressTest: чередование ClearJumpState с
     * незавершённой jump-загрузкой руками не воспроизвести.
     */
    private fun clearPendingFocus() {
        _state.update { current ->
            if (current.pendingFocus == Focus.None) return@update current
            // Снятие Loading освобождает canLoad — обязаны инвалидировать in-flight (см. jumpTo).
            windowEpoch++
            current.copy(
                pendingFocus = Focus.None,
                loadStateTop = current.loadStateTop.idleIfLoading(),
                loadStateBottom = current.loadStateBottom.idleIfLoading(),
            )
        }
    }

    /** Loading, выставленный отменённым jump'ом, обязан сняться — иначе направление залипнет. */
    private fun LoadState.idleIfLoading(): LoadState = if (this is LoadState.Loading) LoadState.Idle else this

    /**
     * Атомарно применяет результат только к тому JumpTo, который всё ещё является текущим.
     * Обычного mutex недостаточно: dispatch(JumpTo) синхронно публикует новую цель без suspend.
     */
    private inline fun updatePendingJumpIfCurrent(
        itemId: String,
        transform: (WindowState<T>) -> WindowState<T>,
    ): Boolean {
        while (true) {
            val current = _state.value
            if (current.pendingFocus != Focus.Item(itemId)) return false
            if (_state.compareAndSet(current, transform(current))) {
                return true
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /** Called under mutex; only the current edge request may settle its loading state. */
    private fun takeEdgeCompletion(
        direction: WindowLoadDirection,
        request: Any,
    ): Boolean {
        if (!edgeRequests.containsKey(direction) || edgeRequests.getValue(direction) !== request) return false
        edgeRequests.remove(direction)
        return true
    }

    private fun focusFor(items: List<T>): Focus =
        if (items.isEmpty()) Focus.None else Focus.Item(dataSource.itemId(items.first()))

    /** Самый новый элемент (index 0 в DESC порядке). */
    private fun newest(w: List<T>) = w.first()

    /** Самый старый элемент (последний index в DESC порядке). */
    private fun oldest(w: List<T>) = w.last()

    /** Можно ли грузить в данном направлении (не loading, hasMore, есть элементы). */
    private fun canLoad(
        s: WindowState<T>,
        d: WindowLoadDirection,
    ) = when (d) {
        WindowLoadDirection.TOP -> s.loadStateTop != LoadState.Loading && s.hasMoreTop && s.items.isNotEmpty()
        WindowLoadDirection.BOTTOM -> s.loadStateBottom != LoadState.Loading && s.hasMoreBottom && s.items.isNotEmpty()
    }

    /** Anchor для загрузки: oldest для TOP (грузим старее), newest для BOTTOM (грузим новее). */
    private fun anchorForDirection(
        items: List<T>,
        d: WindowLoadDirection,
    ) = when (d) {
        WindowLoadDirection.TOP -> oldest(items)
        WindowLoadDirection.BOTTOM -> newest(items)
    }

    private fun setLoading(
        s: WindowState<T>,
        d: WindowLoadDirection,
        loadState: LoadState,
    ) = when (d) {
        WindowLoadDirection.TOP -> s.copy(loadStateTop = loadState)
        WindowLoadDirection.BOTTOM -> s.copy(loadStateBottom = loadState)
    }

    private fun setLoadingAndHasMore(
        s: WindowState<T>,
        d: WindowLoadDirection,
        loadState: LoadState,
        hasMore: Boolean,
    ) = when (d) {
        WindowLoadDirection.TOP -> s.copy(loadStateTop = loadState, hasMoreTop = hasMore)
        WindowLoadDirection.BOTTOM -> s.copy(loadStateBottom = loadState, hasMoreBottom = hasMore)
    }
}
