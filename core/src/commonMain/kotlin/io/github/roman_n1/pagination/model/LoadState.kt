package io.github.roman_n1.pagination.model

/**
 * Состояние загрузки для направления (top/bottom).
 * Аналог LoadState из Paging 3.
 */
sealed interface LoadState {
    /** Загрузка не активна и не требуется. */
    data object Idle : LoadState

    /** Загрузка в процессе. */
    data object Loading : LoadState

    /** Загрузка завершилась ошибкой. Retry доступен через [WindowIntent.Retry]. */
    data class Error(val error: Throwable) : LoadState

    /** Удобный accessor для проверки loading-состояния. */
    val isLoading: Boolean get() = this is Loading
}
