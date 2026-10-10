package io.github.roman_n1.pagination.page

import kotlinx.collections.immutable.ImmutableList

/** Independent window ranges share the page store's ownership and publication. */
class PageWindows<K : Any, T : Any> internal constructor(private val read: () -> PageStoreState<K, T>) {
    fun window(id: WindowId): WindowLookup<K, T> = WindowLookup.Missing
    fun active(): WindowLookup<K, T> = WindowLookup.Missing
    suspend fun open(id: WindowId, seed: WindowSeed<K>): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(id))
    suspend fun activate(id: WindowId): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(id))
    suspend fun attach(token: WindowToken, pageIds: ImmutableList<PageId>): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(token.windowId))
    suspend fun recordAnchor(token: WindowToken, anchor: ViewportAnchor): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(token.windowId))
    suspend fun requestRestore(token: WindowToken, anchor: ViewportAnchor): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(token.windowId))
    suspend fun acknowledgeRestore(token: WindowToken): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(token.windowId))
    suspend fun close(token: WindowToken): WindowChange<K> = WindowChange.Rejected(WindowRejection.Missing(token.windowId))
}
