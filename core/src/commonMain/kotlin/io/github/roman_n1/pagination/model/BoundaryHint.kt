package io.github.roman_n1.pagination.model

enum class BoundaryHint {
    Unspecified, More, End;

    companion object {
        fun of(value: Boolean): BoundaryHint = if (value) More else End
    }

    fun resolve(fallback: Boolean): Boolean = when (this) {
        Unspecified -> fallback
        More -> true
        End -> false
    }
}
