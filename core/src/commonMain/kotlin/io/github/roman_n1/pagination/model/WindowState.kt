package io.github.roman_n1.pagination.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Иммутабельный снимок состояния окна пагинации.
 *
 * Окно — это видимый срез данных, загруженных из полного набора.
 * Items хранятся в порядке компаратора (обычно DESC: newest = index 0).
 *
 * Окно содержит один сегмент вокруг текущего фокуса, непрерывный ОТНОСИТЕЛЬНО ЗАГРУЖЕННЫХ
 * СТРАНИЦ : соседями в окне становятся только элементы, между
 * которыми pager либо ничего не загружал, либо всё загруженное сам удалил (снапшот из БД
 * перестал подтверждать элемент → элемент выпадает из окна). Непрерывность относительно
 * ПОЛНОГО набора данных после удалений НЕ гарантируется: удаления между загрузками легальны,
 * и «дыры» в сквозной нумерации модели внутри окна — не дефект. JumpTo с overlap merge'ит
 * окна, а без overlap заменяет окно новым сегментом.
 *
 * @param T тип элемента (MessageEntity, ContactEntity, ...)
 *
 * @property items текущий список элементов в окне (отсортирован компаратором)
 * @property loadStateTop состояние загрузки старых элементов
 * @property loadStateBottom состояние загрузки новых элементов
 * @property hasMoreTop есть ещё старые элементы за пределами окна
 * @property hasMoreBottom есть ещё новые элементы за пределами окна
 * @property pendingFocus явный запрос скролла или [Focus.None]
 */
data class WindowState<T : Any>(
    val items: ImmutableList<T> = persistentListOf(),
    val loadStateTop: LoadState = LoadState.Idle,
    val loadStateBottom: LoadState = LoadState.Idle,
    val hasMoreTop: Boolean = true,
    val hasMoreBottom: Boolean = true,
    val pendingFocus: Focus = Focus.None
)
