package io.github.roman_n1.pagination.compose

import io.github.roman_n1.pagination.model.*

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import io.github.roman_n1.pagination.engine.WindowController
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowState

/**
 * Generic wrapper для implicit-trigger пагинации.
 * Аналог LazyPagingItems из Paging 3.
 *
 * При обращении к [get] автоматически диспатчит нужный [WindowIntent] в [controller].
 * UI не знает о пагинации — просто читает элементы по индексу.
 *
 * Каждый intent диспатчится максимум один раз за время жизни этого экземпляра.
 * При изменении state создаётся новый экземпляр (через collectAsWindowPagingItems),
 * сбрасывая флаги.
 *
 * ПОЧЕМУ @Stable, а не инференс: controller-поле (MutableStateFlow внутри) инференс не пропустит.
 * Обещание честно по построению: инстанс — замороженный снапшот, все публично наблюдаемые
 * свойства неизменны после создания (в отличие от LazyPagingItems, мутирующего снапшот внутри
 * одного инстанса); новое окно = НОВЫЙ инстанс из collectAsWindowPagingItems → Compose видит
 * смену по ссылке. Private dispatched-флаги публичные значения не меняют (side effect в
 * controller). Ломаешь инвариант «frozen после создания» — снимай аннотацию.
 */
@Stable
class WindowPagingItems<T : Any>(
    private val items: ImmutableList<T>,
    private val state: WindowState<T>,
    private val controller: WindowController<T>,
    private val prefetchDistance: Int = controller.config.prefetchDistance,
) {
    val size: Int get() = items.size
    val snapshot: List<T> get() = items

    val loadStateTop: LoadState get() = state.loadStateTop
    val loadStateBottom: LoadState get() = state.loadStateBottom
    val pendingFocus: Focus get() = state.pendingFocus

    val hasMoreTop: Boolean get() = state.hasMoreTop
    val hasMoreBottom: Boolean get() = state.hasMoreBottom

    val isIdle: Boolean get() = state.loadStateTop == LoadState.Idle && state.loadStateBottom == LoadState.Idle

    /** Дистанция до края, на которой implicit prefetch вызывает LoadTop/LoadBottom. */
    val edgePrefetchDistance: Int get() = prefetchDistance

    /** Флаги: intent уже отправлен в этом экземпляре, повторно не шлём. */
    private var topDispatched = false
    private var bottomDispatched = false

    operator fun get(index: Int): T {
        triggerPrefetch(index)
        return items[index]
    }

    fun getId(index: Int): String = controller.itemId(items[index])

    /** Повторить последнюю неудачную загрузку. */
    fun retry() {
        controller.dispatch(WindowIntent.Retry)
    }

    private fun triggerPrefetch(index: Int) {
        // Замороженный UI-снимок не управляет новым окном и не вмешивается в активный JumpTo.
        if (controller.state.value !== state || state.pendingFocus != Focus.None) return
        if (!topDispatched && index >= size - prefetchDistance && state.hasMoreTop &&
            state.loadStateTop != LoadState.Loading
        ) {
            topDispatched = true
            controller.dispatch(WindowIntent.LoadTop)
        }
        if (!bottomDispatched && index < prefetchDistance && state.hasMoreBottom &&
            state.loadStateBottom != LoadState.Loading
        ) {
            bottomDispatched = true
            controller.dispatch(WindowIntent.LoadBottom)
        }
    }
}
