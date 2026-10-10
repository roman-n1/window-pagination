package io.github.roman_n1.pagination.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.roman_n1.pagination.engine.WindowController

/**
 * Аналог `collectAsLazyPagingItems()` из Paging 3.
 *
 * Подписывается на [WindowController.state] и возвращает [WindowPagingItems]
 * с implicit trigger для пагинации.
 *
 * Использование:
 * ```
 * val pagingItems = controller.collectAsWindowPagingItems()
 *
 * LazyColumn {
 *     items(pagingItems.size, key = { pagingItems.getId(it) }) { index ->
 *         val item = pagingItems[index]  // пагинация триггерится автоматически
 *     }
 * }
 * ```
 */
@Composable
fun <T : Any> WindowController<T>.collectAsWindowPagingItems(): WindowPagingItems<T> {
    val windowState = state.collectAsState().value
    return remember(this, windowState) {
        WindowPagingItems(
            items = windowState.items,
            state = windowState,
            controller = this@collectAsWindowPagingItems
        )
    }
}
