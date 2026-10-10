# Source page ownership

`PageStore<Key, Item>` holds independently addressable immutable pages. It is the state kernel for upcoming window loading and edit transactions; the existing `WindowPager` facade is not connected to it yet.

A `SourcePage` contains immutable source items and `Cursor.End` / `Cursor.At(key)` for each direction. Keys can be opaque cursors, offsets, or a value object containing separate network/cache offsets. Empty items do not imply end of pagination: an empty page with `Cursor.At(nextKey)` remains a loaded page with continuation.

Capture `store.state.value.generation` before starting a source load, then call `addPage(generation, sourcePage)` when it finishes. `reset()` clears pages and advances the generation; late responses from a previous generation are rejected without changing state. Re-registering a page ID and duplicate item IDs within a source page are rejected as typed results.

`page(id)` returns `PageLookup.Loaded(snapshot)` or `PageLookup.Missing`. A snapshot includes `PageToken(generation, pageId, revision)`, the original source page and displayed items. Registration starts at revision zero. Multiple independent pages can coexist; their ordering/connectivity and active window selection will be managed by the window engine. Identical item IDs may occur in different cached source pages, such as overlapping ranges; the active window projection will resolve ownership.

Registration and reset are atomic. State is published as a read-only `StateFlow`. No persistence, network access or lifecycle scope is introduced in this kernel.

## Atomic page transactions

Capture the snapshot token, then edit its display in one transaction:

```kotlin
store.editPage(snapshot.token) {
    update("message-1", editedMessage)
    insertAfter("message-1", localMessage)
    moveBefore("message-3", "message-1")
    remove("message-2")
}
```

The builder also supports `prepend`, `append`, `insertBefore`, `moveAfter`, `moveToStart`, `moveToEnd`, `reorder` and `replaceItems`. Operations run sequentially against the transaction's intermediate display order. They publish one immutable snapshot, increment the page revision once, and leave all source items and cursors intact. No comparator sorts the edited display. Other pages remain unchanged.

Item IDs are unique within a page. `update` preserves the item's identity. All referenced IDs must exist; `reorder` is an exact permutation of the current IDs. Moving an item relative to itself is valid. An empty transaction preserves the revision. Every nonempty transaction records its intent, even when the resulting visible order is identical. Items themselves must be immutable; the store cannot freeze user-supplied mutable objects.

A failed operation rejects the whole transaction as a typed `PageRejection`, preserving items, edits and revision. A builder retained by the caller cannot append operations to an already committed snapshot. Exceptions thrown by caller code propagate before publication.

## Source refresh and conflicts

`replacePage(token, newSource)` replaces source items, display items and both cursors together, and clears the local edit journal. It accepts empty pages with continuation. The page ID must stay the same. Use this operation when deliberately discarding local edits.

`reconcilePage(token, newSource)` replays the page's local journal on fresh source items and cursors. If an edited item disappeared, a local insertion now duplicates a server item, or a recorded reorder is no longer an exact permutation, it returns `EditsConflict` and leaves the previous snapshot intact. The caller decides whether to reload, revise its edits or explicitly replace the page. Reconciliation does not silently drop operations or deduplicate optimistic echoes; those require a source identity policy.

All three operations validate generation, page existence and revision while holding the store mutex. A source response must use the token captured **before** the load: a response completing after another edit/refresh receives `RevisionChanged`, and a response after `reset()` receives `GenerationChanged`. Capture a fresh token to retry after resolving the conflict. Different pages can be changed independently.

Scroll anchor fallback and applying these transactions through `WindowPager` are subsequent integration steps; the current page kernel does not own viewport or source subscriptions.

## Bounded page cache

`PageCacheConfig(maxPages = 64, maxItems = 600, maxEditUnitsPerPage = 1200)` bounds retained source items and displayed items separately, including empty pages and local edit payloads. A replacement operation still consumes journal budget even if a later operation hides its items. These are reference/count budgets, not byte-size guarantees for arbitrary user models or cursor objects.

Registration, edits and source updates admit their complete result atomically. If necessary, they evict the least recently used **clean** pages and report IDs in `PageChange.Applied.evictedPages`. A page with local edits is protected from automatic eviction. The incoming/updated page is protected as well. If protected pages or a single oversized page cannot fit, `CacheCapacityExceeded` leaves all previous snapshots intact. Oversized journals return `EditCapacityExceeded`. Explicit source replacement clears the journal and releases that protection.

Successful registration/change marks a page as recently used. `accessPage(id)` explicitly marks access; plain `page(id)` is a read-only lookup and does not affect eviction order. The recency table only retains loaded pages. Empty transactions can mark access without publishing a new snapshot or revision.

A token also includes a monotonically allocated load `instance`. Reloading an evicted `PageId` starts a new instance even within the same generation. Operations from the previous instance return `PageInstanceChanged`; matching page ID and revision cannot resurrect ownership.

These limits currently cover the shared page kernel. Bounding the existing window facade's live tail, protecting active window pages and reloading evicted windows belong to its subsequent integration, not to this cache API alone.
