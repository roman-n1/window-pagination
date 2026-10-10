package io.github.roman_n1.pagination.engine

import io.github.roman_n1.pagination.model.*

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import io.github.roman_n1.pagination.model.LoadResult
import io.github.roman_n1.pagination.model.LoadState
import io.github.roman_n1.pagination.model.WindowPagingConfig
import io.github.roman_n1.pagination.model.WindowState

internal enum class WindowLoadDirection { TOP, BOTTOM }

// ПОЧЕМУ отдельно от WindowPager: объединение страниц — чистое преобразование снапшота.
// Владение запросами, проверка поколения и атомарная публикация остаются у pager под mutex.
internal class WindowPageMerger<T : Any>(
    private val itemId: (T) -> String,
    private val comparator: Comparator<T>,
    private val config: WindowPagingConfig,
) {
    fun appendEdge(
        current: WindowState<T>,
        result: LoadResult<T>,
        direction: WindowLoadDirection,
    ): WindowState<T> {
        val merged = normalize(current.items + result.items)
        val bounded = boundInMemoryWindow(merged, direction)
        val droppedOppositeEdge = bounded.size < merged.size
        val hasMore = result.hasMore || result.items.size >= config.pageSize
        return when (direction) {
            WindowLoadDirection.TOP -> {
                current.copy(
                    items = bounded,
                    loadStateTop = LoadState.Idle,
                    hasMoreTop = hasMore,
                    hasMoreBottom = current.hasMoreBottom || droppedOppositeEdge,
                )
            }

            WindowLoadDirection.BOTTOM -> {
                current.copy(
                    items = bounded,
                    loadStateBottom = LoadState.Idle,
                    hasMoreBottom = hasMore,
                    hasMoreTop = current.hasMoreTop || droppedOppositeEdge,
                )
            }
        }
    }

    /** Дедупликация + сортировка по компаратору. Конверсия в ImmutableList — здесь, у источника окна. */
    fun normalize(items: List<T>): ImmutableList<T> = items.distinctBy { itemId(it) }.sortedWith(comparator).toImmutableList()

    /**
     * Ограничивает только in-memory окно. Room-кэш здесь не trim-ится: при движении к старому краю
     * сохраняем хвост, при движении к новому — начало DESC-списка. Отброшенный противоположный край
     * остаётся доступен через обратный edge-load.
     */
    private fun boundInMemoryWindow(
        items: ImmutableList<T>,
        direction: WindowLoadDirection,
    ): ImmutableList<T> {
        if (items.size <= config.maxCachedItems) return items
        return when (direction) {
            WindowLoadDirection.TOP -> items.takeLast(config.maxCachedItems)
            WindowLoadDirection.BOTTOM -> items.take(config.maxCachedItems)
        }.toImmutableList()
    }

    /**
     * Мержит DB snapshot в текущее окно.
     *
     * Для каждого элемента окна ищет обновлённую версию в snapshot (по ID).
     * Если элемент удалён из БД — исчезает из окна.
     * Если [includeLiveTail] = true — новые элементы из snapshot, которые новее
     * НИЗА окна, мержатся (live-обновления с websocket и свои send'ы).
     */
    fun mergeSnapshotIntoWindow(
        window: ImmutableList<T>,
        snapshot: List<T>,
        includeLiveTail: Boolean,
    ): ImmutableList<T> {
        if (window.isEmpty()) return window

        val snapshotById = snapshot.associateBy { itemId(it) }

        // Обновляем существующие элементы (edit, reaction, delete)
        val updatedWindow = window.filter { itemId(it) in snapshotById }.map { snapshotById.getValue(itemId(it)) }
        if (!includeLiveTail || updatedWindow.isEmpty()) return normalize(updatedWindow)

        // Live tail: элементы из snapshot, которых нет в окне и которые СТРОГО НОВЕЕ загруженной
        // нижней границы. Граница — низ ИСХОДНОГО окна (не верх — C-437: свой send несёт createdAt
        // клиентских часов и сортуется «старше» серверного верха; эхо-семантика upsert'а сохраняет
        // этот createdAt) и именно ДО-мержевого окна: удаление нижнего ряда в этом же снапшоте не
        // сужает ПОКРЫТЫЙ диапазон. Элементы старше границы — незагруженная история, их доклейка
        // мимо Before-страниц создала бы невидимый шов; send с ключом ниже границы (skew больше
        // глубины окна) остаётся за Before-страницами/перезагрузкой.
        val loadedFrontier = window.last()
        val loadedIds = updatedWindow.asSequence().map { itemId(it) }.toSet()
        val tail =
            snapshot.filter {
                itemId(it) !in loadedIds &&
                    comparator.compare(it, loadedFrontier) < 0
            }
        return normalize(updatedWindow + tail)
    }
}
