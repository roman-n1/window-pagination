package io.github.roman_n1.pagination.model

/**
 * Параметры загрузки для [WindowDataSource.load].
 * Аналог PagingSource.LoadParams из Paging 3, но с поддержкой Around (JumpTo).
 */
sealed interface LoadParams {
    val limit: Int

    /** Начальная загрузка. Позиция [InitialPosition] определяет newest или окно вокруг элемента. */
    data class Initial(val position: InitialPosition, override val limit: Int) : LoadParams

    /**
     * Загрузка элементов СТАРШЕ anchor'а (скролл вверх по истории).
     * [anchorCanonicalId] — канонический (persistence/backend) id якоря из
     * `WindowDataSource.canonicalId`; дефолт [anchorId] — для источников без двойной идентичности.
     */
    data class Before(
        val anchorId: String,
        val anchorSortKey: Long,
        override val limit: Int,
        val anchorCanonicalId: String = anchorId,
    ) : LoadParams

    /** Загрузка элементов НОВЕЕ anchor'а (скролл вниз к последним). См. [Before.anchorCanonicalId]. */
    data class After(
        val anchorId: String,
        val anchorSortKey: Long,
        override val limit: Int,
        val anchorCanonicalId: String = anchorId,
    ) : LoadParams

    /** Загрузка окна ВОКРУГ anchor'а (JumpTo: reply, search, pinned). */
    data class Around(val anchorId: String, override val limit: Int) : LoadParams
}
