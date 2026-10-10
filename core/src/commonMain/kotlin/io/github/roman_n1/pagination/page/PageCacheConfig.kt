package io.github.roman_n1.pagination.page

/** Limits source and displayed items separately, plus the retained edit journal. */
data class PageCacheConfig(
    val maxPages: Int = 64,
    val maxItems: Int = 600,
    val maxEditUnitsPerPage: Int = 1200,
)
