package io.github.roman_n1.pagination.model

sealed interface ReconcileResult<out T : Any> {
    data object Unchanged : ReconcileResult<Nothing>
    data class Updated<T : Any>(val page: LoadResult<T>) : ReconcileResult<T>
}

fun interface BackgroundReconcile<T : Any> {
    suspend fun run(): ReconcileResult<T>
}
