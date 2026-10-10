package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*
import io.github.roman_n1.pagination.engine.PagingScope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.WindowPagingConfig

/**
 * Регрессия: подписка на live-обновления БД падала «наружу».
 *
 * Room/web-драйвер легально бросает из flow (SQLITE_BUSY на закрытии сессии, таймаут worker'а),
 * а `pagerScope` собран из SupervisorJob без CoroutineExceptionHandler — исключение уходило
 * в дефолтный обработчик платформы (на Android это краш процесса), хотя все остальные пути
 * загрузки в [WindowPager] конвертируют ошибку в состояние.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowPagerLiveUpdateFailureTest {
    @Test
    fun `падение live-подписки не уходит в дефолтный обработчик - окно остаётся читаемым`() =
        runTest {
            val uncaught = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() +
                        StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error -> uncaught += error },
                )
            val pager =
                WindowPager(
                    dataSource = ThrowingObserveDataSource(),
                    config = WindowPagingConfig(pageSize = 3, prefetchDistance = 1, maxCachedItems = 30),
                    scope = PagingScope.Borrowed(scope),
                )
            try {
                advanceUntilIdle()

                assertEquals(emptyList(), uncaught.map { it.toString() })
                assertEquals(listOf("3", "2", "1"), pager.state.value.items)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `после падения live-подписки низ окна перестаёт считаться live edge`() =
        runTest {
            val scope =
                CoroutineScope(
                    SupervisorJob() +
                        StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, _ -> },
                )
            val pager =
                WindowPager(
                    dataSource = ThrowingObserveDataSource(),
                    config = WindowPagingConfig(pageSize = 3, prefetchDistance = 1, maxCachedItems = 30),
                    scope = PagingScope.Borrowed(scope),
                )
            try {
                advanceUntilIdle()

                // Живого хвоста больше нет: лента должна дорастать честными After-страницами.
                assertTrue(pager.state.value.hasMoreBottom)
            } finally {
                scope.cancel()
            }
        }

    /** Начальное окно грузится нормально, а live-подписка сразу падает, как падает Room при SQLITE_BUSY. */
    private class ThrowingObserveDataSource : WindowDataSource<String> {
        override val comparator: Comparator<String> = Comparator { left: String, right: String -> right.toInt().compareTo(left.toInt()) }

        override val itemId: (String) -> String = { it }

        override suspend fun load(params: LoadParams): LoadResult<String> =
            LoadResult(
                items = listOf("3", "2", "1"),
                hasMore = false,
                hasMoreTop = BoundaryHint.of(false),
                hasMoreBottom = BoundaryHint.of(false),
            )

        override fun observeChanges(
            windowIds: StateFlow<List<String>>,
            observeLiveTail: StateFlow<Boolean>,
        ): Flow<List<String>> = flow { throw IllegalStateException("SQLITE_BUSY") }

        override fun getSortKey(item: String): Long = item.toLong()

        override suspend fun trimCache(
            keepCount: Int,
            protectedIds: Set<String>,
        ) = Unit
    }
}
