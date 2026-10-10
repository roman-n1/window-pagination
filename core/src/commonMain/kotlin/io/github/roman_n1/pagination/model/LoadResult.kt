package io.github.roman_n1.pagination.model

/** A loaded page and optional directional knowledge, expressed as named states. */
data class LoadResult<T : Any>(
    val items: List<T>,
    val hasMore: Boolean,
    val hasMoreTop: BoundaryHint = BoundaryHint.Unspecified,
    val hasMoreBottom: BoundaryHint = BoundaryHint.Unspecified,
    /** Unchanged preserves the cached window. Empty Updated pages also preserve it in 0.1. */
    val backgroundReconcile: BackgroundReconcile<T> = BackgroundReconcile { ReconcileResult.Unchanged },
)
