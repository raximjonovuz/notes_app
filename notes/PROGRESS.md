# Notes — Progress (V5.3)

## V5.3 — released

### Resolved (A-series — backend)

- **A1** IDOR hardening on `/notes/<id>` DELETE/POST.
- **A2** Single `DB.batch` for `notes` + `change_log` writes so a
  partial failure can't leave a `change_log` row pointing at a stale
  notes row.
- **A3** `base_change_id` + `undelete: true` opt-in for un-delete.
- **A4** `created_at` no longer overwritten with `client_updated_at`.
- **A5** `since`-cursor pull returns one row per note (per-note
  aggregation; no duplicates).
- **A6** Cron-driven retention: hard-deletes `change_log` and
  soft-deleted `notes` older than 90 days. 410 Gone when the cursor
  is stale.
- **A7** `token_version` per user; emails normalised lowercase.
- **A8** Orphan-image cleanup + explicit `/files/<key>` DELETE.
- **A9** Per-user cache versioning.
- **A10** Strict input validation on `POST /notes` (length / colour /
  clock clamp).

### Resolved (B-series — sync core)

- **B1** Tombstone push: 200 or 404 → local row purged in the same
  pass.
- **B3** `serverId` stamped locally at insert time; UPSERT is a
  no-op on the server side.
- **B6** Per-user sync state (`lastCursor`, `pendingFlushVersion`)
  namespaced by `userId`; one `syncMutex` for push + pull.

### Resolved (C-series — token + release)

- **C1** `EncryptedSharedPreferences` (Android Keystore) for token
  storage. Legacy plain-text `auth_prefs` migrated on first launch.
- **C5** Release signing wired up; `keystore.properties` failure is
  fail-fast (`gradle.taskGraph.whenReady` hook).

### Bug status

The original audit listed 8 surfaced issues. All are resolved:

| Bug | Status | Notes |
|---|---|---|
| Trash / pin / colour flips on already-synced notes never uploaded | **fixed** | B1 — push drain keys on `dirtyRevision > lastSyncedRevision`. |
| `DELETE` only soft-deletes, ghost notes resurrect | **fixed** | B1 — push issues `DELETE /notes/<id>`; 200 or 404 purges the local row. |
| 3-way merge overwrites user's local edits | **fixed** | `tryThreeWayMerge` + `is_conflict` flag. |
| Sync cursor is a wall-clock; clock skew drops rows | **fixed** | `change_id` (Long) cursor in `change_log`. |
| Server `MAX(updated_at, Date.now())` overwrites fresh local edits | **fixed** | Server stores `client_updated_at` verbatim. |
| `/files/<key>` leaks R2 URLs across users | **fixed** | Auth-gated, ownership check against `images` table. |
| `uploadImage` OOMs on multi-MB phone photos | **fixed** | Decode + downscale + re-encode to JPEG q=85, 6 MB ceiling. |
| Cold-start flush misses a push queued by the previous process | **fixed** | `pendingFlushVersion` re-runs the push on cold start. |

No open bugs at the time of this writing. If you find one, file it
under the bug-ID scheme (B-number).

---

## V5.3 → V5.3.x maintenance

### Library bumps (V5.3.3)

- `androidx.core:core-ktx` 1.10.1 → **1.15.0**
- `androidx.activity:activity-compose` 1.8.0 → **1.10.0**
- `androidx.lifecycle:lifecycle-runtime-ktx` 2.6.2 → **2.9.0**
- `androidx.lifecycle:lifecycle-viewmodel-compose` 2.6.2 → **2.9.0**
- `androidx.navigation:navigation-compose` 2.7.7 → **2.8.0**

Other dependencies (AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2026.02.01,
Room 2.7.0, OkHttp 4.12.0, WorkManager 2.11.0) are already at the
version pinned for V5.3.

### Cleanup (V5.3.3)

- **ApiClient** — `jsonObjectBody` / `jsonString` are now thin
  wrappers around `kotlinx.serialization.json.buildJsonObject`. The
  hand-rolled JSON encoder is gone; surrogate pairs and control
  characters are emitted per JSON.
- **MarkdownExporter** — emits a single tag pair per styled run
  (previously one tag per character); inline Markdown special
  characters are backslash-escaped; `<span style="color:N">` now
  carries a real CSS hex (`#rrggbb`) resolved via `StyleRanges.colorFor`.
- Removed unused / dead code:
  - `NoteDao.bumpDirty` (B12 cleanup; no caller)
  - `NoteDao.getDirtyNotesBounded` (cold-start flush doesn't need a
    bounded drain; the unbounded `getDirtyNotes` is what `pushLocalChangesInternal`
    reads)
  - `AuthStore.save / load / clear` (legacy V3.1 single-session
    shims — replaced by `saveOrUpdate / loadAll / getActive / clearAll`)
  - `AuthRepository.currentSession / logout` (deprecated V3.1 shims)
  - `ExampleUnitTest` + `ExampleInstrumentedTest` (Android Studio
    placeholder tests, no behaviour under test)
- **BackupExporter** — added `decode(ByteArray)` and `restoreFrom`
  (SAF import + duplicate-by-serverId skip). Writes still go
  straight to disk via `ContentResolver.openOutputStream` so a 1 GB
  export doesn't OOM on a phone with 256 MB of heap.

---

## Open work (post-V5.3.3)

- Markdown *import* (currently export-only).
- Conflict-resolution UI: `is_conflict=1` rows are flagged but the UI
  doesn't surface them yet.
- Background retention sweep on the client side (the backend already
  hard-deletes old tombstones; the client could mirror that to keep
  `change_id` cursors stable across the 90-day boundary).