# One engine for windows and editable pages

This is a design roadmap, not a description of shipped APIs. The initial extraction preserves the window engine and replaces nullable contracts with explicit states. Migrating the host application and removing its other pagination dependency are separate stages.

## Proposed model

Keep source ordering and displayed ordering separate:

- `SourcePage<Key, Item>` owns source items, before/after cursors and range identity.
- `PageEdits<Item>` records local insert, update, remove and reorder operations.
- `PageSnapshot<Item>` is the immutable displayed result.
- `PageToken` contains generation, page identity and revision. A stale token produces an explicit conflict result, never silently overwrites a newer page.

A generic key is independent of UI item identity. Cursor states use sealed alternatives such as `Cursor.End` and `Cursor.At(key)`, never nullable keys. An empty page can carry a continuation cursor.

Proposed operations:

```text
editPage(token) {
    update(itemId, replacement)
    insertAfter(existingId, newItem)
    moveBefore(itemId, existingId)
    remove(itemId)
    reorder(orderedIds)
    replaceItems(items)
}
replacePage(token, sourcePage)
```

`editPage` is atomic and leaves server pagination boundaries intact. `replacePage` replaces source content and cursors together. Stable IDs must remain unique; invalid edits return a typed rejection. Source updates and edits need a documented conflict policy (initially reject stale revisions), and deleting a scroll anchor needs an explicit fallback policy. A display reorder must never change the next network cursor.

## Implementation order and acceptance criteria

1. **Extraction and no-null baseline.** Preserve existing load, retry, live-update, mapping and lifecycle tests; independent build and CI.
2. **Page ownership and generic keys.** Add page identity, generation/revision tokens, source ranges, cursors and empty-page continuation. Keep the existing window facade during migration. Test distant jumps, disjoint ranges, overlap, stale completion and cache eviction.
3. **Page transactions.** Add atomic edits and source replacement. Test reordered display versus unchanged network cursor, insertion with stable IDs, duplicate rejection, concurrent refresh/edit conflict, anchor deletion and retry.
4. **Simple network lists.** Migrate contact creation/selection and address-book pickers first. These prove offset/cursor support without chat event complexity. Move domain contracts away from `PagingData` as part of each vertical slice.
5. **Database lists and mentions.** Verify invalidation, refresh and cache behavior; add a persistence coordinator only if concrete sources need one.
6. **Chat facade.** Migrate around/jump, live tail, optimistic local items, message edits/deletes and anchor preservation. Keep stable UI IDs distinct from backend IDs. Verify long-distance jumps do not load intervening history.
7. **Attachments and remaining clients.** Migrate event-driven attachment lists and separately built mail/client integrations. Each requires its own compilation and behavioral checks.
8. **Remove the old dependency.** Search all source sets, public contracts, DI, UI bindings and composites. Remove the other paging dependency only after every consumer has migrated.

For each screen, require a focused test and a UI smoke before switching its production binding. A compiling adapter alone does not prove scroll stability or recovery.

## Features worth adopting

From established paging libraries: directional load states and retry, configurable prefetch, bounded retention, observable loading, transformation cancellation and test tooling. From tiled designs: explicit page keys and independently addressable cached pages, if required by actual consumers. Avoid adding a second parallel engine: window and page APIs should project one underlying state machine.
