package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.WindowPagingConfig

/**
 * WP1: `close()` гасил только дочерний `SupervisorJob` компонента. Когда caller свой
 * session/lifecycle-scope не передавал, компонент заводил `CoroutineScope(Dispatchers.Default)`
 * сам — и корневой Job этого scope оставался `Active` до смерти процесса, потому что отмена
 * ребёнка родителя не трогает.
 *
 * Инвариант, который держит этот тест: свой scope компонент гасит целиком, чужой — не трогает
 * (иначе `close()` одного pager'а убил бы весь session-scope caller'а).
 */
class PagingOwnedScopeTest {
    @Test
    fun `close гасит scope заведённый самим pager-ом`() {
        val pager =
            WindowPager(
                dataSource = EmptyWindowDataSource,
                config = WindowPagingConfig(pageSize = 1, prefetchDistance = 1, maxCachedItems = 2),
            )
        assertTrue(pager.isOwnedScopeActive(), "собственный scope обязан быть живым до close")

        pager.close()

        assertFalse(pager.isOwnedScopeActive(), "close обязан погасить собственный scope pager-а")
    }

    @Test
    fun `close не трогает scope переданный caller-ом`() =
        runTest {
            val callerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val pager =
                WindowPager(
                    dataSource = EmptyWindowDataSource,
                    config = WindowPagingConfig(pageSize = 1, prefetchDistance = 1, maxCachedItems = 2),
                    scope = PagingScope.Borrowed(callerScope),
                )

            pager.close()

            assertFalse(pager.isOwnedScopeActive(), "чужой scope компоненту не принадлежит")
            assertTrue(
                callerScope.coroutineContext.fold(false) { active, element -> if (element is Job) element.isActive else active },
                "close pager-а не имеет права гасить session-scope caller-а",
            )
            callerScope.cancel()
        }

    @Test
    fun `close гасит scope заведённый самим mapped-контроллером`() {
        val pager =
            WindowPager(
                dataSource = EmptyWindowDataSource,
                config = WindowPagingConfig(pageSize = 1, prefetchDistance = 1, maxCachedItems = 2),
            )
        val mapped = pager.map(itemId = { it }) { it }
        val owner = mapped as MappedWindowController<String, String>
        assertTrue(owner.isOwnedScopeActive(), "собственный scope обязан быть живым до close")

        mapped.close()

        assertFalse(owner.isOwnedScopeActive(), "close обязан погасить собственный scope контроллера")
        assertFalse(pager.isOwnedScopeActive(), "close маппера обязан дойти до delegate")
    }

    @Test
    fun `mapList без scope тоже владеет своим scope`() {
        val pager =
            WindowPager(
                dataSource = EmptyWindowDataSource,
                config = WindowPagingConfig(pageSize = 1, prefetchDistance = 1, maxCachedItems = 2),
            )
        val mapped = pager.mapList(itemId = { it }) { it }
        val owner = mapped as MappedWindowController<String, String>

        mapped.close()

        assertFalse(owner.isOwnedScopeActive(), "close обязан погасить собственный scope контроллера")
    }

    @Test
    fun `переданный scope остаётся под управлением caller-а`() =
        runTest {
            val callerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val owned = OwnedPagingScope(PagingScope.Borrowed(callerScope))

            owned.cancelIfOwned()

            assertFalse(owned.isOwnedScopeActive())
            assertTrue(callerScope.coroutineContext.fold(false) { active, element -> if (element is Job) element.isActive else active })
            callerScope.cancel()
        }

    private object EmptyWindowDataSource : WindowDataSource<String> {
        override val comparator: Comparator<String> = naturalOrder()
        override val itemId: (String) -> String = { it }

        override suspend fun load(params: LoadParams): LoadResult<String> = LoadResult(items = emptyList(), hasMore = false)

        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<String>> = emptyFlow()

        override fun getSortKey(item: String): Long = 0L

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) = Unit
    }
}
