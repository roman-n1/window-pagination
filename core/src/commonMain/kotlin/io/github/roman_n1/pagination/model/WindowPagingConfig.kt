package io.github.roman_n1.pagination.model

/**
 * Конфигурация window-based пагинации.
 * Аналог PagingConfig из Paging 3.
 *
 * @param pageSize количество элементов на одну загрузку
 * @param prefetchDistance сколько элементов до края списка запускают подгрузку
 * @param maxCachedItems максимум элементов в БД-кэше (trim при JumpTo)
 */
data class WindowPagingConfig(
    val pageSize: Int = 30,
    val prefetchDistance: Int = 5,
    val maxCachedItems: Int = pageSize * 20
)
