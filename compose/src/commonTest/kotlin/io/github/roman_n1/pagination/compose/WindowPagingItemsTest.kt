package io.github.roman_n1.pagination.compose

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.roman_n1.pagination.engine.WindowController
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

class WindowPagingItemsTest {
    @Test
    fun `get у верхнего края — диспатчит LoadTop`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
        val state =
            WindowState(
                items = items,
                hasMoreTop = true,
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

        // index 3 >= size(5) - prefetchDistance(2) = 3 → triggers LoadTop
        pagingItems[3]

        assertEquals(listOf<WindowIntent>(WindowIntent.LoadTop), controller.dispatched)
    }

    @Test
    fun `edgePrefetchDistance пробрасывает config`() {
        val items = persistentListOf(TestItem("1"))
        val state = WindowState(items = items)
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = items,
                state = state,
                controller = controller,
                prefetchDistance = 7,
            )
        assertEquals(7, pagingItems.edgePrefetchDistance)
    }

    @Test
    fun `get у нижнего края — диспатчит LoadBottom`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
        val state =
            WindowState(
                items = items,
                hasMoreTop = false,
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

        // index 1 < prefetchDistance(2) → triggers LoadBottom
        pagingItems[1]

        assertEquals(listOf<WindowIntent>(WindowIntent.LoadBottom), controller.dispatched)
    }

    @Test
    fun `get в середине — ничего не диспатчит`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
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

        pagingItems[2]

        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun `prefetch диспатчит каждый intent максимум один раз за экземпляр`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
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

        // Trigger both edges multiple times
        pagingItems[0] // LoadBottom
        pagingItems[4] // LoadTop
        pagingItems[0] // should NOT re-dispatch
        pagingItems[4] // should NOT re-dispatch

        assertEquals(
            listOf<WindowIntent>(WindowIntent.LoadBottom, WindowIntent.LoadTop),
            controller.dispatched,
        )
    }

    @Test
    fun `устаревший снимок не запускает prefetch в новом окне`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
        val state = WindowState(items = items, hasMoreTop = true)
        val controller = RecordingController(state)
        val pagingItems =
            WindowPagingItems(
                items = items,
                state = state,
                controller = controller,
                prefetchDistance = 2,
            )

        controller.publish(state.copy(items = persistentListOf(TestItem("target"))))
        pagingItems[4]

        assertTrue(controller.dispatched.isEmpty())
    }

    @Test
    fun `активный переход к сообщению не запускает edge prefetch`() {
        val items = (5 downTo 1).map { TestItem(it.toString()) }.toImmutableList()
        val state =
            WindowState(
                items = items,
                hasMoreTop = true,
                hasMoreBottom = true,
                pendingFocus = Focus.Item("3"),
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
        pagingItems[4]

        assertTrue(controller.dispatched.isEmpty())
    }

    private data class TestItem(
        val id: String,
    )

    private class RecordingController(
        initialState: WindowState<TestItem>,
    ) : WindowController<TestItem> {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<WindowState<TestItem>> = mutableState

        override val itemId: (TestItem) -> String = { it.id }

        override val config: WindowPagingConfig =
            WindowPagingConfig(
                pageSize = 10,
                prefetchDistance = 2,
            )

        val dispatched = mutableListOf<WindowIntent>()

        override fun dispatch(intent: WindowIntent) {
            dispatched += intent
        }

        fun publish(state: WindowState<TestItem>) {
            mutableState.value = state
        }
    }
}
