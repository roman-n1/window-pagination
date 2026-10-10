# Source page ownership

`PageStore<Key, Item>` holds independently addressable immutable pages. It is the state kernel for upcoming window loading and edit transactions; the existing `WindowPager` facade is not connected to it yet.

A `SourcePage` contains immutable source items and `Cursor.End` / `Cursor.At(key)` for each direction. Keys can be opaque cursors, offsets, or a value object containing separate network/cache offsets. Empty items do not imply end of pagination: an empty page with `Cursor.At(nextKey)` remains a loaded page with continuation.

Capture `store.state.value.generation` before starting a source load, then call `addPage(generation, sourcePage)` when it finishes. `reset()` clears pages and advances the generation; late responses from a previous generation are rejected without changing state. Re-registering a page ID and duplicate item IDs within a source page are rejected as typed results.

`page(id)` returns `PageLookup.Loaded(snapshot)` or `PageLookup.Missing`. A snapshot includes `PageToken(generation, pageId, revision)`, the original source page and displayed items. Registration starts at revision zero. Multiple independent pages can coexist; their ordering/connectivity and active window selection will be managed by the window engine. Identical item IDs may occur in different cached source pages, such as overlapping ranges; the active window projection will resolve ownership.

Registration and reset are atomic. State is published as a read-only `StateFlow`. No persistence, network access or lifecycle scope is introduced in this kernel.
