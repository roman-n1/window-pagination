# Window Pagination

Kotlin Multiplatform pagination for a moving window of a live feed. Jump directly to a distant item without loading the history between the current and destination windows.

**Status: experimental extraction, API may change.** This repository contains the window engine and its Compose adapter. It is not yet a drop-in replacement for AndroidX Paging. Page editing is planned, not implemented; see the [unification roadmap](docs/roadmap.md).

## What works today

- Load before, after, or around an item; jump to the newest window.
- Merge overlapping windows; replace the active window when a jump does not overlap. No synthetic adjacency across unloaded history.
- Observe changes for loaded IDs and optionally the live tail.
- Separate stable UI identity from backend identity for edge requests.
- Retry failures and recover on an external signal.
- Reject stale completions using window generations and request ownership.
- Map individual items or whole snapshots with cancellation of superseded work.
- Compose snapshot bindings with edge prefetch.
- Non-null item types and explicit states for absence, lifecycle ownership and directional knowledge.

`core` has no Compose, AndroidX Paging, database, networking or application dependencies. `compose` adds the UI binding. Both declare JVM, Android, iOS arm64/simulator arm64, JS and Wasm targets. The `null-safety` module is build tooling only and is not a runtime dependency.

The [source page API](docs/page-api.md) adds generic cursor metadata and independent page ownership as the foundation for editable pages. The window facade will adopt it in a subsequent change.

## Getting started

Artifacts are not published to Maven Central yet. For a local checkout, use a composite build:

```kotlin
// settings.gradle.kts
includeBuild("../window-pagination")
```

```kotlin
// consumer build.gradle.kts
commonMain.dependencies {
    implementation("io.github.roman-n1:core:0.1.0-SNAPSHOT")
    implementation("io.github.roman-n1:compose:0.1.0-SNAPSHOT")
}
```

Implement `WindowDataSource<T : Any>` with `load`, `observeChanges`, `getSortKey`, `trimCache`, `itemId` and a comparator. The engine stores items in comparator order, conventionally newest first. `observeChanges` emits an authoritative snapshot for the observed IDs plus an optional live tail; omitting a previously loaded item means deletion. An empty snapshot is currently ignored. A source without live updates can return `emptyFlow()`.

```kotlin
val pager = WindowPager(
    dataSource = source,
    config = WindowPagingConfig(pageSize = 30, prefetchDistance = 5),
    initialPosition = InitialPosition.Around("message-25000"),
    scope = PagingScope.Borrowed(screenScope),
)

pager.dispatch(WindowIntent.JumpTo("message-90000"))
pager.dispatch(WindowIntent.LoadTop)
pager.dispatch(WindowIntent.JumpToNewest)

when (val focus = pager.state.value.pendingFocus) {
    Focus.None -> Unit
    is Focus.Item -> scrollTo(focus.id)
}
// After the UI consumes the focus request:
pager.dispatch(WindowIntent.ClearJumpState)
// At the owner's lifecycle end:
pager.close()
```

`PagingScope.Owned` creates a scope that `close()` cancels. With `Borrowed`, closing cancels the pager's child work, leaving the caller's scope alive. A mapped controller owns its upstream controller: closing the mapping also closes the upstream pager.

In Compose, call `controller.collectAsWindowPagingItems()`. Render `size` rows, use `getId(index)` for stable keys, and read `items[index]` to trigger prefetch. Focus handling and scrolling remain the host's responsibility.

## Explicit absence

| Meaning | API |
| --- | --- |
| Start at newest or a specific item | `InitialPosition.Newest` / `Around(id)` |
| No pending scroll or a target | `Focus.None` / `Item(id)` |
| Direction unknown, more data, boundary reached | `BoundaryHint.Unspecified` / `More` / `End` |
| No reconcile change or a page | `ReconcileResult.Unchanged` / `Updated(page)` |
| Library or caller owns the scope | `PagingScope.Owned` / `Borrowed(scope)` |

An empty `Updated` page currently preserves the cached window. It is **not** a clear-window command. `BoundaryHint.Unspecified` falls back to legacy `hasMore` behavior.

## Limits

- One active contiguous window, not a tile cache of multiple disconnected windows.
- Every merge normalizes by the source comparator. Arbitrary page reorder/insert/replace transactions are not available yet.
- Cursors currently use String identity and a Long sort key. Generic offset and opaque cursor support is planned.
- `maxCachedItems` bounds edge-load growth and requests source cache trimming. It is not a hard cap on all live-tail emissions.
- Mapping errors preserve the last good snapshot. Cancellation is propagated.
- No placeholders, AndroidX compatibility adapter, RemoteMediator equivalent or built-in persistence.

## Verification

Use JDK 21 and an Android SDK with platform 37. On macOS, Xcode is needed for Native checks.

```sh
python3 -m unittest discover -s scripts/tests
python3 scripts/check_null_safety.py
./gradlew :null-safety:test :core:compileTestKotlinJvm :compose:compileTestKotlinJvm -PcheckNullTypes
./gradlew :core:jvmTest :compose:jvmTest
```

On Linux, run Compose JVM tests with `xvfb-run -a`. See [the null policy](docs/null-safety.md) and CI for platform checks.

Licensed under [Apache-2.0](LICENSE).
