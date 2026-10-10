package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

sealed interface ItemPosition {
    data object Start : ItemPosition
    data object End : ItemPosition
    data class Before(val id: String) : ItemPosition
    data class After(val id: String) : ItemPosition
}

sealed interface PageEdit<out T : Any> {
    data class Update<T : Any>(val id: String, val item: T) : PageEdit<T>
    data class Insert<T : Any>(val position: ItemPosition, val item: T) : PageEdit<T>
    data class Remove(val id: String) : PageEdit<Nothing>
    data class Move(val id: String, val position: ItemPosition) : PageEdit<Nothing>
    data class Reorder(val ids: ImmutableList<String>) : PageEdit<Nothing>
    data class ReplaceItems<T : Any>(val items: ImmutableList<T>) : PageEdit<T>
}

data class PageEdits<out T : Any>(val operations: ImmutableList<PageEdit<T>> = persistentListOf())

/** A transaction builder; the store freezes its operations before acquiring ownership. */
class PageEditor<T : Any> internal constructor() {
    private val operations = mutableListOf<PageEdit<T>>()
    fun update(id: String, item: T) { operations.add(PageEdit.Update(id, item)) }
    fun prepend(item: T) { operations.add(PageEdit.Insert(ItemPosition.Start, item)) }
    fun append(item: T) { operations.add(PageEdit.Insert(ItemPosition.End, item)) }
    fun insertBefore(id: String, item: T) { operations.add(PageEdit.Insert(ItemPosition.Before(id), item)) }
    fun insertAfter(id: String, item: T) { operations.add(PageEdit.Insert(ItemPosition.After(id), item)) }
    fun remove(id: String) { operations.add(PageEdit.Remove(id)) }
    fun moveBefore(id: String, anchor: String) { operations.add(PageEdit.Move(id, ItemPosition.Before(anchor))) }
    fun moveAfter(id: String, anchor: String) { operations.add(PageEdit.Move(id, ItemPosition.After(anchor))) }
    fun moveToStart(id: String) { operations.add(PageEdit.Move(id, ItemPosition.Start)) }
    fun moveToEnd(id: String) { operations.add(PageEdit.Move(id, ItemPosition.End)) }
    fun reorder(ids: ImmutableList<String>) { operations.add(PageEdit.Reorder(ids)) }
    fun replaceItems(items: ImmutableList<T>) { operations.add(PageEdit.ReplaceItems(items)) }
    internal fun freeze(): PageEdits<T> = PageEdits(operations.toImmutableList())
}
