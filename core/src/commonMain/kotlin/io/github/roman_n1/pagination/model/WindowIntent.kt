package io.github.roman_n1.pagination.model

/**
 * Команды для управления окном пагинации.
 *
 * LoadTop и LoadBottom диспатчатся автоматически через [WindowPagingItems.operator get],
 * когда пользователь скроллит к краю списка (implicit trigger).
 *
 * JumpTo диспатчится явно из UI при клике на reply/search/pinned.
 */
sealed interface WindowIntent {
    /** Загрузить элементы СТАРШЕ текущего окна (скролл вверх по истории). */
    data object LoadTop : WindowIntent

    /** Загрузить элементы НОВЕЕ текущего окна (скролл вниз к последним). */
    data object LoadBottom : WindowIntent

    /**
     * Телепортация к элементу.
     * Если элемент в окне → pendingFocus.
     * Если нет → loadAround; overlap merge'ится, а при отсутствии overlap окно заменяется.
     */
    data class JumpTo(val itemId: String) : WindowIntent

    /**
     * Переход к live edge списка.
     * В отличие от [JumpTo], загружает newest-окно, а не окно вокруг anchor'а.
     */
    data object JumpToNewest : WindowIntent

    /**
     * Восстановить потерянный live edge из авторитетного newest-окна.
     * Всегда перечитывает источник и заменяет видимое окно, даже если pager считает себя на live edge.
     */
    data object ReconcileLiveEdge : WindowIntent

    /** Сброс состояния jump после того, как UI выполнил скролл. */
    data object ClearJumpState : WindowIntent

    /** Повторить последнюю неудачную загрузку (LoadState.Error → retry). */
    data object Retry : WindowIntent
}
