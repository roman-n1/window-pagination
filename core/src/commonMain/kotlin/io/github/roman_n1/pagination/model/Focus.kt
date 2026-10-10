package io.github.roman_n1.pagination.model

sealed interface Focus {
    data object None : Focus
    data class Item(val id: String) : Focus
}
