package io.github.roman_n1.pagination.model

/** Explicit presence for implementation state; public domain states use named alternatives. */
internal sealed interface Presence<out T : Any> {
    data object Absent : Presence<Nothing>
    data class Present<T : Any>(val value: T) : Presence<T>
}

internal inline fun <T : Any, R : Any> Presence<T>.fold(absent: () -> R, present: (T) -> R): R =
    when (this) {
        Presence.Absent -> absent()
        is Presence.Present -> present(value)
    }

internal inline fun <T : Any> Presence<T>.getOrElse(default: () -> T): T = fold(default) { it }
