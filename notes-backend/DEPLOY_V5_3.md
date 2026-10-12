# V5.3 deploy checklist

These commands apply the V5.3 schema migrations and deploy the rewritten
backend. They are NOT executed by opencode — Cloudflare access is not
available from this environment. Run them yourself from a shell with
`wrangler` installed and authenticated.

## 1. Migrations (apply locally and remotely)

```bash
cd notes-backend
npm run db:migrate          # local D1 (for tests / wrangler dev)
npm run db:migrate:remote   # remote D1
```

If `db:migrate` / `db:migrate:remote` scripts aren't in package.json,
the underlying wrangler commands are:

```bash
# Local
npx wrangler d1 migrations apply notes-db --local

# Remote
npx wrangler d1 migrations apply notes-db --remote
```

The new files that will run are:

- `migrations/0002_soft_delete_and_change_tracking.sql` — adds
  `notes.deleted_at`, `notes.client_updated_at`, `notes.client_created_at`,
  `notes.change_id`, the `change_log` table, and the `note_sync_state`
  helper table.
- `migrations/0003_image_user_index.sql` — adds
  `idx_images_r2_user` on `(r2_key, user_id)` so `/files/<key>` can
  authorize in O(log n).

If you have an existing deployment, the migration is additive and safe
on live data. Existing rows get `deleted_at = NULL` and
`client_updated_at = 0`; the next pull will backfill `client_updated_at`
on the first sync, and rows that never had a server change_id
(`change_id = 0`) are treated as "live, no ack yet" by the client.

## 2. Deploy

```bash
cd notes-backend
npm run deploy
# or, equivalently:
npx wrangler deploy
```

## 3. Bump the Android `versionCode` and `versionName`

In `notes/app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 6      // was 5 (V5.2.x)
    versionName = "5.3.0"
}
```

## 4. Ship the Android APK

```bash
cd notes
./gradlew assembleDebug
# adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Room migration from V9 → V10 runs automatically on first launch
and is one-way only (no destructive fallback). The `dirtyRevision = 1`
default for every existing row will cause the first sync to re-push
every note once. That is intentional and safe — the server's UPSERT
treats it as a no-op when the content matches.

## 5. Optional: run integration tests against a real D1

```bash
cd notes-backend
npx wrangler dev   # local worker + local D1
# in another shell:
curl -X POST http://localhost:8787/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"smoke@example.com","password":"smoketest123"}'
# Save the returned token, then:
TOKEN=...
curl -X POST http://localhost:8787/notes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":"11111111-1111-1111-1111-111111111111","title":"hi","content":"world","is_favorite":0,"is_trashed":0,"background_color":0,"updated_at":'"$(date +%s%3N)"'}'
curl -X GET "http://localhost:8787/notes?since=0" -H "Authorization: Bearer $TOKEN"
# Expected: 1 note returned, plus sync_cursor=1.
```

## 6. Rollback

There is no automatic rollback. If the V5.3 release causes problems:

1. Revert the Android side to V5.2.x. The V9 Room schema is unchanged
   by V10 if the new columns simply aren't written to. The push side
   falls back to "nothing dirty" (dirtyRevision = 1 by default; the
   `dirtyRevision > lastSyncedRevision` filter matches everything;
   pushes succeed but no-ops on the server because the V5.2 backend
   doesn't know about `client_updated_at`).
2. Revert the Worker to V3.0 (`git checkout v3.0` on the backend).
3. The V5.3 migration does not drop any column or table, so going
   back to V5.2 keeps the data intact.

## Notes for the V5.3 design

- **Server `updated_at` is now equal to `client_updated_at`**. The
  client provides the timestamp; the server stores it verbatim and
  bumps it forward by 1 if a previous write has a higher value
  (monotonic per row). Wall-clock `Date.now()` is no longer trusted
  for conflict resolution.
- **`change_log` is monotonic**. Each upsert/delete appends one row
  and returns its autoincrement `id`. The client persists that id in
  `SyncStore.lastCursor` (was: `lastSync`) and uses it as the
  `?since=` value. There is no same-millisecond data loss.
- **`/files/<key>` requires the bearer token** and verifies the
  caller is the same user that uploaded the file. URLs in older
  client builds (V5.2.x) will start returning 401 once the V5.3
  server is deployed — schedule the Android roll-out in the same
  release window.
