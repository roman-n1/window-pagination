package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import io.github.roman_n1.pagination.model.LoadParams
import io.github.roman_n1.pagination.model.LoadResult

/**
 * Абстракция источника данных для [WindowPager].
 * Аналог PagingSource из Paging 3, но с поддержкой JumpTo (Around) и live-обновлений.
 *
 * Реализуется для каждого типа списка (сообщения, контакты, пины, ...).
 */
interface WindowDataSource<T : Any> {
    val comparator: Comparator<T>
    val itemId: (T) -> String

    /**
     * Канонический (persistence/backend) id элемента — тот, которым оперируют сеть и SQL.
     * У источников с двойной идентичностью (стабильный UI-ключ ≠ PK, например clientMessageId ≠
     * server id у сообщений) отличается от [itemId]: engine кладёт его в
     * [LoadParams.Before]/[LoadParams.After], чтобы источник не восстанавливал PK эвристикой
     * по побочным признакам. Дефолт — [itemId].
     */
    val canonicalId: (T) -> String get() = itemId

    /**
     * Единая точка загрузки данных.
     * Тип загрузки определяется через [LoadParams]:
     * - [LoadParams.Initial] — начальное окно
     * - [LoadParams.Before] — старые элементы (скролл вверх)
     * - [LoadParams.After] — новые элементы (скролл вниз)
     * - [LoadParams.Around] — окно вокруг элемента (JumpTo)
     */
    suspend fun load(params: LoadParams): LoadResult<T>

    /**
     * Принудительно загружает авторитетное newest-окно.
     *
     * В отличие от обычного [LoadParams.Initial], реализация может обойти локальный warm-cache.
     * Это нужно для восстановления live edge, когда независимый источник уже видит новое событие,
     * а подписка текущего окна его потеряла.
     */
    suspend fun refreshNewest(limit: Int): LoadResult<T> = load(LoadParams.Initial(position = InitialPosition.Newest, limit = limit))

    /**
     * Наблюдает за live-обновлениями элементов.
     *
     * [windowIds] — ID элементов, которые сейчас в окне (обновляется engine'ом при каждом изменении).
     * [observeLiveTail] — true, когда окно на live-краю (`!hasMoreBottom`) или ещё пусто
     * (bootstrap seed). Вне bottom engine и так не append'ит хвост (`includeLiveTail=false`),
     * поэтому подписка на newest N — лишняя Room-инвалидация .
     *
     * Реализация должна наблюдать за:
     * 1. Изменениями элементов с этими ID (edit, delete, reactions)
     * 2. Новыми элементами (live tail — websocket → DB), **только если** [observeLiveTail]=true
     *
     * Возвращает Flow со snapshot'ом, содержащим обновлённые элементы окна + (опционально) новые.
     */
    fun observeChanges(
        windowIds: StateFlow<List<String>>,
        observeLiveTail: StateFlow<Boolean>,
    ): Flow<List<T>>

    fun getSortKey(item: T): Long

    /**
     * Очищает кэш, оставляя не менее [keepCount] элементов.
     * [protectedIds] — ID элементов, которые нельзя удалять (текущее окно).
     */
    suspend fun trimCache(
        keepCount: Int,
        protectedIds: Set<String>,
    )
}
