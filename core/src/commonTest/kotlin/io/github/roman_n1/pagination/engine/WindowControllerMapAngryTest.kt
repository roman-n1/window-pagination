package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WindowControllerMapAngryTest {

    // ─── map: трансформация элементов ──────────────────────────────

    @Test
    fun `map — трансформирует каждый элемент`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(items = persistentListOf("a", "b", "c")),
        )
        val mapped = source.map(
            itemId = { it.uppercase() },
            scope = PagingScope.Borrowed(scope),
            transform = { it.uppercase() },
        )

        assertEquals(listOf("A", "B", "C"), mapped.state.value.items)
    }

    @Test
    fun `map — пустой список — пустой результат`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(items = persistentListOf()),
        )
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it.uppercase() },
        )

        assertTrue(mapped.state.value.items.isEmpty())
    }

    @Test
    fun `map — сохраняет loadState`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(
                items = persistentListOf("x"),
                loadStateTop = LoadState.Loading,
                loadStateBottom = LoadState.Idle,
                hasMoreTop = true,
                hasMoreBottom = false,
            ),
        )
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it },
        )

        val state = mapped.state.value
        assertEquals(LoadState.Loading, state.loadStateTop)
        assertEquals(LoadState.Idle, state.loadStateBottom)
        assertTrue(state.hasMoreTop)
        assertEquals(false, state.hasMoreBottom)
    }

    @Test
    fun `map — сохраняет pendingFocus`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(
                items = persistentListOf("a"),
                pendingFocus = Focus.Item("focus-id"),
            ),
        )
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it },
        )

        assertEquals(Focus.Item("focus-id"), mapped.state.value.pendingFocus)
    }

    // ─── dispatch делегируется ─────────────────────────────────────

    @Test
    fun `map — dispatch делегируется оригинальному контроллеру`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(initialState = WindowState(items = persistentListOf("a")))
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it },
        )

        mapped.dispatch(WindowIntent.LoadTop)
        mapped.dispatch(WindowIntent.LoadBottom)
        mapped.dispatch(WindowIntent.JumpTo("id1"))

        assertEquals(
            listOf(
                WindowIntent.LoadTop,
                WindowIntent.LoadBottom,
                WindowIntent.JumpTo("id1"),
            ),
            source.dispatched,
        )
    }

    // ─── config делегируется ──────────────────────────────────────

    @Test
    fun `map — config берётся из delegate`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cfg = WindowPagingConfig(pageSize = 50, prefetchDistance = 10)
        val source = FakeWindowController(
            initialState = WindowState(),
            config = cfg,
        )
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it },
        )

        assertEquals(50, mapped.config.pageSize)
        assertEquals(10, mapped.config.prefetchDistance)
    }

    // ─── itemId новая функция ─────────────────────────────────────

    @Test
    fun `map — itemId использует новую функцию`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(items = persistentListOf("hello")),
        )
        val mapped = source.map(
            itemId = { "mapped-$it" },
            scope = PagingScope.Borrowed(scope),
            transform = { it.uppercase() },
        )

        assertEquals("mapped-HELLO", mapped.itemId("HELLO"))
    }

    // ─── реактивное обновление state ──────────────────────────────

    @Test
    fun `map — реагирует на обновления source state`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val stateFlow = MutableStateFlow(WindowState(items = persistentListOf("a")))
        val source = FakeWindowController(stateFlow = stateFlow)
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it.uppercase() },
        )

        assertEquals(listOf("A"), mapped.state.value.items)

        stateFlow.value = WindowState(items = persistentListOf("b", "c"))
        assertEquals(listOf("B", "C"), mapped.state.value.items)
    }

    // ─── mapList ──────────────────────────────────────────────────

    @Test
    fun `mapList — трансформирует весь список разом`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(items = persistentListOf("x", "y", "z")),
        )
        val mapped = source.mapList(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { list -> list.map { it.repeat(2) } },
        )

        assertEquals(listOf("xx", "yy", "zz"), mapped.state.value.items)
    }

    @Test
    fun `mapList — может менять размер списка фильтрация`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(items = persistentListOf("a", "bb", "ccc")),
        )
        val mapped = source.mapList(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { list -> list.filter { it.length > 1 } },
        )

        assertEquals(listOf("bb", "ccc"), mapped.state.value.items)
    }

    @Test
    fun `mapList — сохраняет loadState и hasMore`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(
            initialState = WindowState(
                items = persistentListOf("a"),
                loadStateTop = LoadState.Loading,
                hasMoreBottom = false,
            ),
        )
        val mapped = source.mapList(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it },
        )

        val state = mapped.state.value
        assertEquals(LoadState.Loading, state.loadStateTop)
        assertEquals(false, state.hasMoreBottom)
    }

    // ─── C21: mapLatest отменяет устаревшую трансформацию ──────────

    @Test
    fun `map — устаревшая трансформация отменяется под burst — mapLatest`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val stateFlow = MutableStateFlow(WindowState(items = persistentListOf("v0")))
        val source = FakeWindowController(stateFlow = stateFlow)
        var completions = 0
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = {
                delay(100)
                completions++
                it.uppercase()
            },
        )

        // Burst: три быстрых обновления, пока трансформация v0 висит на delay.
        stateFlow.value = WindowState(items = persistentListOf("v1"))
        stateFlow.value = WindowState(items = persistentListOf("v2"))
        stateFlow.value = WindowState(items = persistentListOf("v3"))
        advanceUntilIdle()

        // mapLatest завершает ТОЛЬКО последнюю трансформацию; устаревшие v0/v1/v2 отменены.
        // С прежним flow.map здесь завершились бы две (v0 + latest) → completions == 2.
        assertEquals(listOf("V3"), mapped.state.value.items)
        assertEquals(1, completions, "устаревшие трансформации не должны доводиться до конца")
    }

    // ─── close ────────────────────────────────────────────────────

    @Test
    fun `close — вызывает close на delegate`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val source = FakeWindowController(initialState = WindowState())
        val mapped = source.map(
            itemId = { it },
            scope = PagingScope.Borrowed(scope),
            transform = { it },
        )

        mapped.close()
        assertTrue(source.closed)
    }
}

// ─── Fake ───────────────────────────────────────────────────────────

private class FakeWindowController(
    initialState: WindowState<String> = WindowState(),
    private val stateFlow: MutableStateFlow<WindowState<String>> = MutableStateFlow(initialState),
    override val config: WindowPagingConfig = WindowPagingConfig(),
) : WindowController<String> {

    override val state: StateFlow<WindowState<String>> = stateFlow
    override val itemId: (String) -> String = { it }

    val dispatched = mutableListOf<WindowIntent>()
    var closed = false

    override fun dispatch(intent: WindowIntent) {
        dispatched.add(intent)
    }

    override fun close() {
        closed = true
    }
}
