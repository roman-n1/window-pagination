package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*

import kotlinx.coroutines.flow.StateFlow
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Generic контроллер окна пагинации.
 * Аналог LazyPagingItems source из Paging 3.
 */
interface WindowController<T : Any> {
    val state: StateFlow<WindowState<T>>

    /** Функция извлечения ID из элемента. */
    val itemId: (T) -> String

    /** Конфигурация пагинации. */
    val config: WindowPagingConfig

    fun dispatch(intent: WindowIntent)

    // Освобождаем фоновые подписки окна.
    fun close() = Unit
}
