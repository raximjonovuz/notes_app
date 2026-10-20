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

The migrations that will run, in order:

- `migrations/0001_initial.sql` — `users`, `notes`, `images`,
  `change_log` baseline.
- `migrations/0002_soft_delete_and_change_tracking.sql` — adds
  `notes.deleted_at`, `notes.client_updated_at`, `notes.client_created_at`,
  `notes.change_id`, the `change_log` table, and the `note_sync_state`
  helper table.
- `migrations/0003_image_user_index.sql` — adds
  `idx_images_r2_user` on `(r2_key, user_id)` so `/files/<key>` can
  authorize in O(log n).
- `migrations/0004_created_at_corrections.sql` — back-fills
  `notes.created_at` from `client_created_at`; rewrites legacy
  second-based `users.created_at` values into milliseconds.
- `migrations/0005_token_version_and_email_lowercase.sql` — adds
  `users.token_version`, normalises emails to lowercase, and creates
  the `rate_limits` table used by the auth rate limiter.

If you have an existing deployment, the migrations are additive and
safe on live data. Existing rows get `deleted_at = NULL`,
`client_updated_at = 0`, `token_version = 0`; the next pull backfills
`client_updated_at` on the first sync, and rows that never had a
server change_id (`change_id = 0`) are treated as "live, no ack yet"
by the client.

## 2. Secrets

Set these with `wrangler secret put <NAME>` before deploying the V5.3
code. None of them belong in `wrangler.toml`.

```bash
# JWT signing key — required. 32-byte random hex (64 hex chars).
npx wrangler secret put JWT_SECRET

# Google OAuth — required only if you want "Continue with Google".
npx wrangler secret put GOOGLE_CLIENT_ID

# Rate-limit counter storage. Without these the limiter falls back to
# in-memory counters that are wiped on every isolate restart. With
# them (recommended) the limiter is per-user, per-minute, and survives
# isolate eviction.
npx wrangler secret put UPSTASH_REDIS_REST_URL
npx wrangler secret put UPSTASH_REDIS_REST_TOKEN

# Password-based auth gate. Set this to "true" to enable
# /auth/register + /auth/login; omit (or set to anything else) and
# the endpoints return 404 so a probe can't tell whether they exist.
# V5.3 default is "disabled" — only Google sign-in is exposed.
npx wrangler secret put ALLOW_PASSWORD_AUTH=false
```

## 3. Deploy

```bash
cd notes-backend
npm run deploy
# or, equivalently:
npx wrangler deploy
```

The deploy takes a few seconds. The cron trigger (`[triggers] crons`
in `wrangler.toml`) is wired automatically by `wrangler deploy` — it
runs at 03:00 UTC daily and drives `runRetention` + `cleanupOrphanImages`.
Verify with `wrangler tail` after the first run.

## 4. Bump the Android `versionCode` and `versionName`

In `notes/app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 26      // was 25 (V5.3.2)
    versionName = "5.3.3"
}
```

## 5. Ship the Android APK

```bash
cd notes
./gradlew assembleDebug
# adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Room migration from V9 → V10 → V11 runs automatically on first
launch and is one-way only (no destructive fallback). The
`dirtyRevision = 1` default for every existing row will cause the
first sync to re-push every note once. That is intentional and safe —
the server's UPSERT treats it as a no-op when the content matches.

## 6. Optional: run integration tests against a real D1

```bash
cd notes-backend
npx wrangler dev   # local worker + local D1
# in another shell:
curl -X POST http://localhost:8787/auth/google \
  -H 'Content-Type: application/json' \
  -d '{"id_token":"<google-id-token>"}'
# Save the returned token, then:
TOKEN=...
curl -X POST http://localhost:8787/notes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":"11111111-1111-1111-1111-111111111111","title":"hi","content":"world","is_favorite":0,"is_trashed":0,"background_color":0,"updated_at":'"$(date +%s%3N)"'}'
curl -X GET "http://localhost:8787/notes?since=0" -H "Authorization: Bearer $TOKEN"
# Expected: 1 note returned, plus sync_cursor=1.
```

## 7. Rollback

There is no automatic rollback. If the V5.3 release causes problems:

1. Revert the Android side to V5.2.x. The V9 Room schema is unchanged
   by V10/V11 if the new columns simply aren't written to. The push
   side falls back to "nothing dirty" (dirtyRevision = 1 by default;
   the `dirtyRevision > lastSyncedRevision` filter matches
   everything; pushes succeed but no-ops on the server because the
   V5.2 backend doesn't know about `client_updated_at`).
2. Revert the Worker to V3.0 (`git checkout v3.0` on the backend).
3. Migrations 0002 / 0003 are additive; 0004 back-fills
   `created_at` / `users.created_at` from the old (incorrect) values
   to the corrected ones — running 0004 again is a no-op. 0005 adds
   `users.token_version` (default 0) and `rate_limits`; dropping them
   requires a manual D1 schema edit if you want to revert.

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
- **`ALLOW_PASSWORD_AUTH` defaults to off**. The Android client's
  `ApiClient.login / register` will silently 404; the Settings screen
  exposes only the Google Sign-In button until you flip the secret.
- **`UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`** back the
  rate-limit counter store. Without them the limiter uses an
  in-process `Map<string, AtomicInteger>` that resets on isolate
  restart — acceptable for low-traffic deployments, not for anything
  resembling production.
