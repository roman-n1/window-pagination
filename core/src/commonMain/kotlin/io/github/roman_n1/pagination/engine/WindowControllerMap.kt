package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*

import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Создаёт маппящий [WindowController], который трансформирует элементы из [T] в [R].
 * Аналог PagingData.map {} из Paging 3, но для WindowController.
 *
 * Dispatch делегируется оригинальному контроллеру.
 * State маппится через [transformLatest]: при новом снимке delegate.state незавершённая
 * трансформация ОТМЕНЯЕТСЯ и перезапускается на свежих данных. Под burst live-обновлений
 * тяжёлый transform (например, toMessage × N элементов) не тратится на устаревшие снимки,
 * а выходной [stateIn] конфлейтит результат. Ранее здесь стоял [kotlinx.coroutines.flow.map],
 * который доводил устаревшую трансформацию до конца впустую (KDoc обещал обратное).
 *
 * @param itemId функция извлечения ID из трансформированного элемента
 * @param scope scope для маппинга state; `PagingScope.Owned` — контроллер заводит собственный
 *              (Dispatchers.Default — в commonMain IO недоступен) и гасит его в `close()`.
 *              Для IO-bound transform передайте свой scope с реальным IO-диспетчером.
 * @param transform suspend-функция трансформации каждого элемента
 */
fun <T : Any, R : Any> WindowController<T>.map(
    itemId: (R) -> String,
    scope: PagingScope = PagingScope.Owned,
    transform: suspend (T) -> R
): WindowController<R> = MappedWindowController(
    delegate = this,
    mappedItemId = itemId,
    scope = scope,
    transformState = { entityState ->
        WindowState(
            items = entityState.items.map { transform(it) }.toImmutableList(),
            loadStateTop = entityState.loadStateTop,
            loadStateBottom = entityState.loadStateBottom,
            hasMoreTop = entityState.hasMoreTop,
            hasMoreBottom = entityState.hasMoreBottom,
            pendingFocus = entityState.pendingFocus
        )
    }
)

/**
 * Batch-вариант [map]: трансформирует весь список элементов за раз.
 * Позволяет собрать зависимости (например, userIds) из всех элементов,
 * сделать один batch-запрос, и замапить с готовым кэшем.
 */
fun <T : Any, R : Any> WindowController<T>.mapList(
    itemId: (R) -> String,
    scope: PagingScope = PagingScope.Owned,
    transform: suspend (List<T>) -> List<R>
): WindowController<R> = MappedWindowController(
    delegate = this,
    mappedItemId = itemId,
    scope = scope,
    transformState = { entityState ->
        WindowState(
            items = transform(entityState.items).toImmutableList(),
            loadStateTop = entityState.loadStateTop,
            loadStateBottom = entityState.loadStateBottom,
            hasMoreTop = entityState.hasMoreTop,
            hasMoreBottom = entityState.hasMoreBottom,
            pendingFocus = entityState.pendingFocus
        )
    }
)

internal class MappedWindowController<T : Any, R : Any>(
    private val delegate: WindowController<T>,
    private val mappedItemId: (R) -> String,
    scope: PagingScope,
    transformState: suspend (WindowState<T>) -> WindowState<R>
) : WindowController<R> {

    private val hostScope = OwnedPagingScope(scope)
    private val mappingJob = hostScope.scope.coroutineContext.childSupervisor()
    private val mappingScope = CoroutineScope(hostScope.scope.coroutineContext + mappingJob)

    override val itemId: (R) -> String = mappedItemId

    override val config: WindowPagingConfig = delegate.config

    @OptIn(ExperimentalCoroutinesApi::class)
    /**
     * ПОЧЕМУ try/catch вокруг трансформации : раньше здесь стоял голый
     * `mapLatest { transformState(it) }`, и любое исключение ИЗ transform улетало в поток
     * состояний. Наблюдаемый отказ: transform у потребителей читает БД (toMessage × N), а
     * `SQLITE_BUSY` при конкурентной записи — штатная, ожидаемая ошибка. Улетая наружу, она
     * рвала `stateIn`-scope: на Android это краш процесса, на десктопе — мёртвая лента без
     * единого сообщения в логе. Найдено стресс-тестом WindowPagingJumpAndMapStressTest —
     * руками такое чередование не воспроизвести.
     *
     * ⚠ CancellationException ПЕРЕбрасывается: на нём стоит вся оптимизация transformLatest (оператор
     * отменяет незавершённую трансформацию устаревшего снимка). Проглотить её — значит
     * молча сломать отмену и вернуть трату CPU на снимки, которые уже никому не нужны.
     *
     * Сбойный снимок пропускается, и `stateIn` продолжает отдавать последнее валидное
     * состояние: показать ленту слегка устаревшей честнее, чем уронить экран.
     */
    override val state: StateFlow<WindowState<R>> = delegate.state
        .transformLatest { entityState ->
            try {
                emit(transformState(entityState))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (transformFailure: Throwable) {
                // Keep the last successfully mapped snapshot.
            }
        }
        .stateIn(mappingScope, SharingStarted.Eagerly, WindowState())

    override fun dispatch(intent: WindowIntent) = delegate.dispatch(intent)

    override fun close() {
        mappingJob.cancel()
        // mappingJob — ребёнок host-scope; свой scope гасим целиком, чужой оставляем caller'у.
        hostScope.cancelIfOwned()
        delegate.close()
    }

    /**
     * Активен ли scope, заведённый самим контроллером (наблюдаемость для тестов).
     * Чужой scope сюда не попадает — им владеет caller.
     */
    internal fun isOwnedScopeActive(): Boolean = hostScope.isOwnedScopeActive()
}
