package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

internal sealed interface EditResult<out T : Any> {
    data class Items<T : Any>(val items: ImmutableList<T>) : EditResult<T>
    data class Rejected(val reason: PageRejection) : EditResult<Nothing>
}

internal fun <T : Any> duplicateIds(items: List<T>, itemId: (T) -> String) =
    items.groupBy(itemId).filterValues { it.size > 1 }.keys.toImmutableSet()

/** All intermediate values are private; rejection cannot publish a partially edited page. */
internal fun <T : Any> applyPageEdits(
    source: ImmutableList<T>,
    edits: PageEdits<T>,
    itemId: (T) -> String,
): EditResult<T> {
    var items = source
    for (edit in edits.operations) {
        val result = applyPageEdit(items, edit, itemId)
        when (result) {
            is EditResult.Items -> items = result.items
            is EditResult.Rejected -> return result
        }
    }
    return EditResult.Items(items)
}

private sealed interface Placement {
    data class At(val index: Int) : Placement
    data class Rejected(val reason: PageRejection) : Placement
}

private fun <T : Any> placement(items: List<T>, position: ItemPosition, itemId: (T) -> String): Placement =
    when (position) {
        ItemPosition.Start -> Placement.At(0)
        ItemPosition.End -> Placement.At(items.size)
        is ItemPosition.Before -> {
            val index = items.indexOfFirst { itemId(it) == position.id }
            if (index < 0) Placement.Rejected(PageRejection.UnknownItem(position.id)) else Placement.At(index)
        }
        is ItemPosition.After -> {
            val index = items.indexOfFirst { itemId(it) == position.id }
            if (index < 0) Placement.Rejected(PageRejection.UnknownItem(position.id)) else Placement.At(index + 1)
        }
    }

private fun <T : Any> applyPageEdit(
    items: ImmutableList<T>,
    edit: PageEdit<T>,
    itemId: (T) -> String,
): EditResult<T> {
    val result = items.toMutableList()
    when (edit) {
        is PageEdit.Update -> {
            val index = result.indexOfFirst { itemId(it) == edit.id }
            if (index < 0) return EditResult.Rejected(PageRejection.UnknownItem(edit.id))
            val replacementId = itemId(edit.item)
            if (replacementId != edit.id) return EditResult.Rejected(PageRejection.ItemIdentityChanged(edit.id, replacementId))
            result[index] = edit.item
        }
        is PageEdit.Insert -> {
            val id = itemId(edit.item)
            if (result.any { itemId(it) == id }) {
                return EditResult.Rejected(PageRejection.DuplicateItems(setOf(id).toImmutableSet()))
            }
            when (val target = placement(result, edit.position, itemId)) {
                is Placement.At -> result.add(target.index, edit.item)
                is Placement.Rejected -> return EditResult.Rejected(target.reason)
            }
        }
        is PageEdit.Remove -> {
            val index = result.indexOfFirst { itemId(it) == edit.id }
            if (index < 0) return EditResult.Rejected(PageRejection.UnknownItem(edit.id))
            result.removeAt(index)
        }
        is PageEdit.Move -> {
            val index = result.indexOfFirst { itemId(it) == edit.id }
            if (index < 0) return EditResult.Rejected(PageRejection.UnknownItem(edit.id))
            val self = when (val position = edit.position) {
                ItemPosition.Start, ItemPosition.End -> false
                is ItemPosition.Before -> position.id == edit.id
                is ItemPosition.After -> position.id == edit.id
            }
            if (self) return EditResult.Items(items)
            val moved = result.removeAt(index)
            when (val target = placement(result, edit.position, itemId)) {
                is Placement.At -> result.add(target.index, moved)
                is Placement.Rejected -> return EditResult.Rejected(target.reason)
            }
        }
        is PageEdit.Reorder -> {
            val ids = items.map(itemId).toImmutableList()
            if (edit.ids.size != ids.size || edit.ids.toSet() != ids.toSet()) {
                return EditResult.Rejected(PageRejection.InvalidOrder(ids, edit.ids))
            }
            val byId = items.associateBy(itemId)
            return EditResult.Items(edit.ids.map { byId.getValue(it) }.toImmutableList())
        }
        is PageEdit.ReplaceItems -> {
            val duplicates = duplicateIds(edit.items, itemId)
            if (duplicates.isNotEmpty()) return EditResult.Rejected(PageRejection.DuplicateItems(duplicates))
            return EditResult.Items(edit.items)
        }
    }
    return EditResult.Items(result.toImmutableList())
}
