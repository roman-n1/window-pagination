package io.github.roman_n1.pagination.model

sealed interface InitialPosition {
    data object Newest : InitialPosition
    data class Around(val id: String) : InitialPosition
}
