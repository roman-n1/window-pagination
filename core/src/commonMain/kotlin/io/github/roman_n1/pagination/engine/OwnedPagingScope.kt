package io.github.roman_n1.pagination.engine

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Owned creates a lifecycle; Borrowed never cancels the caller's scope. */
sealed interface PagingScope {
    data object Owned : PagingScope
    data class Borrowed(val scope: CoroutineScope) : PagingScope
}

/** A child supervisor without nullable context lookup. */
internal fun CoroutineContext.childSupervisor(): Job = fold(SupervisorJob()) { child, element ->
    if (element is Job) {
        child.cancel()
        SupervisorJob(element)
    } else child
}

internal class OwnedPagingScope(provided: PagingScope) {
    private val ownsScope = provided == PagingScope.Owned
    val scope: CoroutineScope = when (provided) {
        PagingScope.Owned -> CoroutineScope(SupervisorJob() + Dispatchers.Default)
        is PagingScope.Borrowed -> provided.scope
    }

    fun cancelIfOwned() {
        if (ownsScope) scope.cancel()
    }

    fun isOwnedScopeActive(): Boolean = ownsScope && scope.coroutineContext.fold(false) { active, element ->
        if (element is Job) element.isActive else active
    }
}
