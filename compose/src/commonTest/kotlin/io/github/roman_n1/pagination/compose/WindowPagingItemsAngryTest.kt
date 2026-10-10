package io.github.roman_n1.pagination.compose

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.roman_n1.pagination.engine.WindowController
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

/**
 * Angry Tests для WindowPagingItems.
 *
 * Проверяем implicit trigger поведение: что get() диспатчит правильные intent'ы
 * при граничных условиях и не делает лишнего.
 */
class WindowPagingItemsAngryTest {
    // ─── Пустой список ──────────────────────────────────────────────

    @Test
    fun `get на пустом списке - IndexOutOfBoundsException`() {
        val state = WindowState<TestItem>()
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = persistentListOf(),
                state = state,
                controller = controller,
                prefetchDistance = 2,
            )
        assertFailsWith<IndexOutOfBoundsException>(block = fun() { pagingItems[0] })
    }

    @Test
    fun `size = 0 для пустого списка`() {
        val state = WindowState<TestItem>()
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = persistentListOf(),
                state = state,
                controller = controller,
                prefetchDistance = 2,
            )
        assertEquals(0, pagingItems.size)
    }

    // ─── Один элемент ───────────────────────────────────────────────

    @Test
    fun `1 элемент hasMore в обоих направлениях - get0 диспатчит и LoadTop и LoadBottom`() {
        val items = persistentListOf(TestItem("1"))
        val state =
            WindowState(
                items = items,
                hasMoreTop = true,
                hasMoreBottom = true,
            )
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = items,
                state = state,
                controller = controller,
                prefetchDistance = 2,
            )

        pagingItems[0]

        assertTrue(
            controller.dispatched.contains(WindowIntent.LoadTop),
            "index 0 >= size(1) - prefetch(2) → LoadTop",
        )
        assertTrue(
            controller.dispatched.contains(WindowIntent.LoadBottom),
            "index 0 < prefetch(2) → LoadBottom",
        )
    }

    // ─── hasMore false — prefetch не срабатывает ─────────────────────

    @Test
    fun `hasMore false в обоих направлениях - никаких prefetch`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
        val state =
            WindowState(
                items = items,
                hasMoreTop = false,
                hasMoreBottom = false,
            )
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = items,
                state = state,
                controller = controller,
                prefetchDistance = 2,
            )

        for (i in 0 until items.size) pagingItems[i]

        assertTrue(controller.dispatched.isEmpty(), "hasMore=false → никаких prefetch dispatch'ей")
    }

    // ─── Loading состояние блокирует prefetch ────────────────────────

    @Test
    fun `LoadState Loading блокирует повторный prefetch`() {
        val items = (10 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
        val state =
            WindowState(
                items = items,
                hasMoreTop = true,
                hasMoreBottom = true,
                loadStateTop = LoadState.Loading,
                loadStateBottom = LoadState.Loading,
            )
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = items,
                state = state,
                controller = controller,
                prefetchDistance = 3,
            )

        pagingItems[0]
        pagingItems[9]

        assertTrue(controller.dispatched.isEmpty(), "Loading → prefetch не должен dispatch'ить")
    }

    // ─── isIdle / loadState proxy ───────────────────────────────────

    @Test
    fun `isIdle корректно отражает оба loadState`() {
        val idleItems = persistentListOf(TestItem("1"))
        val idleState = WindowState(items = idleItems)
        val idle = WindowPagingItems(items = idleItems, state = idleState, controller = RecordingController(idleState))
        assertTrue(idle.isIdle)

        val topLoadingItems = persistentListOf(TestItem("1"))
        val topLoadingState =
            WindowState(
                items = topLoadingItems,
                loadStateTop = LoadState.Loading,
            )
        val topLoading =
            WindowPagingItems(
                items = topLoadingItems,
                state = topLoadingState,
                controller = RecordingController(topLoadingState),
            )
        assertTrue(!topLoading.isIdle)

        val bottomErrorItems = persistentListOf(TestItem("1"))
        val bottomErrorState =
            WindowState(
                items = bottomErrorItems,
                loadStateBottom = LoadState.Error(RuntimeException()),
            )
        val bottomError =
            WindowPagingItems(
                items = bottomErrorItems,
                state = bottomErrorState,
                controller = RecordingController(bottomErrorState),
            )
        assertTrue(!bottomError.isIdle)
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private data class TestItem(
        val id: String,
    )

    private class RecordingController(
        initialState: WindowState<TestItem>,
    ) : WindowController<TestItem> {
        override val state: StateFlow<WindowState<TestItem>> = MutableStateFlow(initialState)
        override val itemId: (TestItem) -> String = { it.id }
        override val config: WindowPagingConfig = WindowPagingConfig(pageSize = 10, prefetchDistance = 2)

        val dispatched = mutableListOf<WindowIntent>()

        override fun dispatch(intent: WindowIntent) {
            dispatched += intent
        }
    }
}
