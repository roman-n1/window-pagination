package io.github.roman_n1.pagination.compose

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.roman_n1.pagination.engine.WindowController
import io.github.roman_n1.pagination.model.WindowIntent
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты Compose-биндинга пагинации: [collectAsWindowPagingItems] + LazyColumn,
 * то есть ровно тот контур, которым пользуются лента чата и списки
 * (KDoc-пример из CollectAsPagingItems.kt воспроизведён в setPagingList).
 *
 * Ядро (WindowPager) покрыто в :core:window-based-pagination:core; здесь проверяем
 * обвязку: биндинг WindowState → элементы списка, implicit-триггеры LoadTop/LoadBottom
 * при реальной композиции и скролле, пустое окно, сброс prefetch-флагов при новом окне.
 *
 * Порядок элементов — DESC (newest = index 0): скролл ВНИЗ уходит в историю (LoadTop),
 * верх списка — live edge (LoadBottom).
 */
@OptIn(ExperimentalTestApi::class)
class WindowPagingComposeBindingTest {

    @Test
    fun `биндинг WindowState в список - каждый элемент окна отображается`() = runComposeUiTest {
        val controller = FakeWindowController(
            initial = WindowState(items = testItems(0..2), hasMoreTop = false, hasMoreBottom = false),
            prefetchDistance = 2,
        )
        setPagingList(controller, listHeight = 400.dp)

        onNodeWithText("msg-0").assertIsDisplayed()
        onNodeWithText("msg-1").assertIsDisplayed()
        onNodeWithText("msg-2").assertIsDisplayed()
        onAllNodesWithTag(ROW_TAG).assertCountEquals(3)
        assertTrue(controller.dispatched.isEmpty(), "hasMore=false в обе стороны — intent'ов быть не должно")
    }

    @Test
    fun `пустое окно - список без строк и без единого intent`() = runComposeUiTest {
        val controller = FakeWindowController(
            initial = WindowState(items = persistentListOf(), hasMoreTop = true, hasMoreBottom = true),
            prefetchDistance = 2,
        )
        setPagingList(controller, listHeight = 400.dp)

        onNodeWithTag(LIST_TAG).assertIsDisplayed()
        onAllNodesWithTag(ROW_TAG).assertCountEquals(0)
        assertTrue(controller.dispatched.isEmpty(), "пустое окно не должно триггерить prefetch")
    }

    @Test
    fun `скролл к краю истории - имплицитный LoadTop`() = runComposeUiTest {
        val controller = FakeWindowController(
            initial = WindowState(items = testItems(0..29), hasMoreTop = true, hasMoreBottom = false),
            prefetchDistance = 3,
        )
        // Видимы ~5 строк из 30 — порог LoadTop (index >= 27) вне первой композиции.
        setPagingList(controller, listHeight = 200.dp)
        assertTrue(controller.dispatched.isEmpty(), "до скролла prefetch стрелять не должен")

        onNodeWithTag(LIST_TAG).performScrollToIndex(29)
        waitForIdle()

        assertEquals(listOf<WindowIntent>(WindowIntent.LoadTop), controller.dispatched)
    }

    @Test
    fun `нижний край виден при первой композиции - ровно один LoadBottom`() = runComposeUiTest {
        val controller = FakeWindowController(
            initial = WindowState(items = testItems(0..9), hasMoreTop = false, hasMoreBottom = true),
            prefetchDistance = 3,
        )
        // Композируются индексы 0..~4: три из них ниже порога prefetch, но intent один.
        setPagingList(controller, listHeight = 200.dp)

        assertEquals(listOf<WindowIntent>(WindowIntent.LoadBottom), controller.dispatched)
    }

    @Test
    fun `обновление state контроллера - новые элементы появляются в списке`() = runComposeUiTest {
        val controller = FakeWindowController(
            initial = WindowState(items = testItems(0..1), hasMoreTop = false, hasMoreBottom = false),
            prefetchDistance = 2,
        )
        setPagingList(controller, listHeight = 400.dp)
        onNodeWithText("msg-1").assertIsDisplayed()

        controller.mutableState.value =
            WindowState(items = testItems(0..2), hasMoreTop = false, hasMoreBottom = false)
        waitForIdle()

        onNodeWithText("msg-2").assertIsDisplayed()
        onAllNodesWithTag(ROW_TAG).assertCountEquals(3)
        assertTrue(controller.dispatched.isEmpty(), "перерисовка без hasMore не должна диспатчить")
    }

    @Test
    fun `новое окно после дозагрузки - prefetch-флаг сброшен и LoadTop уходит повторно`() = runComposeUiTest {
        val controller = FakeWindowController(
            initial = WindowState(items = testItems(0..5), hasMoreTop = true, hasMoreBottom = false),
            prefetchDistance = 2,
        )
        // Все 6 строк видимы: край истории в композиции сразу → первый LoadTop.
        setPagingList(controller, listHeight = 600.dp)
        assertEquals(listOf<WindowIntent>(WindowIntent.LoadTop), controller.dispatched)

        // «Страница дозагрузилась»: окно выросло вниз, история ещё не исчерпана.
        controller.mutableState.value =
            WindowState(items = testItems(0..8), hasMoreTop = true, hasMoreBottom = false)
        waitForIdle()

        assertEquals(
            listOf<WindowIntent>(WindowIntent.LoadTop, WindowIntent.LoadTop),
            controller.dispatched,
            "новый WindowState = новый WindowPagingItems: одноразовый флаг обязан сброситься",
        )
    }

    // ─── Harness ─────────────────────────────────────────────────────

    /** Каноничная обвязка из KDoc CollectAsPagingItems: LazyColumn + get по индексу. */
    private fun ComposeUiTest.setPagingList(
        controller: FakeWindowController,
        listHeight: Dp,
    ) {
        setContent {
            val pagingItems = controller.collectAsWindowPagingItems()
            LazyColumn(
                modifier = Modifier.width(200.dp).height(listHeight).testTag(LIST_TAG),
            ) {
                items(count = pagingItems.size, key = { pagingItems.getId(it) }) { index ->
                    val item = pagingItems[index]
                    BasicText(
                        text = item.id,
                        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).testTag(ROW_TAG),
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun testItems(range: IntRange): ImmutableList<TestItem> =
        range.map { TestItem("msg-$it") }.toImmutableList()

    private data class TestItem(val id: String)

    private class FakeWindowController(
        initial: WindowState<TestItem>,
        prefetchDistance: Int,
    ) : WindowController<TestItem> {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<WindowState<TestItem>> = mutableState
        override val itemId: (TestItem) -> String = { it.id }
        override val config: WindowPagingConfig =
            WindowPagingConfig(pageSize = 10, prefetchDistance = prefetchDistance)

        val dispatched = mutableListOf<WindowIntent>()
        override fun dispatch(intent: WindowIntent) {
            dispatched += intent
        }
    }

    private companion object {
        const val LIST_TAG = "paging_list"
        const val ROW_TAG = "paging_row"
        val ROW_HEIGHT = 40.dp
    }
}
