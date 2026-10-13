# Notes Backend (V5.3)

Cloudflare Workers + D1 (SQLite) + R2 (object storage) backend for
the **Notes** Android app (V5.3+).

| Aspect | Value |
|---|---|
| Runtime | Cloudflare Workers (V8 isolate, ES modules, Web Crypto) |
| Storage | D1 (relational) + R2 (images) |
| Auth | JWT (HS256) over `Authorization: Bearer <token>` |
| Password hashing | PBKDF2 / SHA-256 / 100,000 iterations, 16-byte salt |
| Image auth | `GET /files/<key>` requires a valid JWT and verifies the caller owns the row in the `images` table |
| Sync cursor | Monotonic `change_id` from the `change_log` table (no wall-clock reliance) |
| Soft-delete | `deleted_at` timestamp on notes; rows are hard-deleted only after the server confirms the tombstone |

---

## Project layout

```
notes-backend/
├── package.json
├── wrangler.toml               # Cloudflare bindings — NO secrets here
├── .dev.vars                   # Local secrets (gitignored)
├── tsconfig.json
├── migrations/                 # D1 schema, applied in order
│   ├── 0001_initial.sql
│   ├── 0002_soft_delete_and_change_tracking.sql
│   └── 0003_image_user_index.sql
└── src/
    ├── index.ts                # Main router (entry point)
    ├── types.ts                # TS types + Env bindings
    ├── auth.ts                 # JWT + password hashing (Web Crypto)
    ├── utils.ts                # CORS, JSON, base64 helpers
    └── routes/
        ├── auth.ts             # /auth/register, /login, /me, /google
        ├── notes.ts            # /notes (GET, POST, GET one, DELETE)
        └── upload.ts           # /upload + /files/<key> (R2 serve)
```

---

## First-time setup

### 1. Install + log in

```bash
cd notes-backend
npm install
npx wrangler login
```

### 2. Create the D1 database

```bash
npx wrangler d1 create notes-db
# Output: "database_id = abc123-..."
```

Open `wrangler.toml` and paste the printed `database_id`:

```toml
[[d1_databases]]
binding = "DB"
database_name = "notes-db"
database_id = "abc123-..."    # ← paste here
```

### 3. Create the R2 bucket

```bash
npx wrangler r2 bucket create notes-images
```

### 4. Generate the JWT secret

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
# Output: 64 hex characters
```

**Set it as a Cloudflare secret (NEVER in `wrangler.toml`)**:

```bash
npx wrangler secret put JWT_SECRET
# Paste the hex string when prompted
```

> ⚠️ **Security note:** `wrangler.toml` is committed to git. If the
> secret ends up there, anyone with read access to the repo can
> forge valid tokens. Cloudflare secrets are encrypted at rest and
> only injected at runtime — that's the right place.

For local development, also write to `.dev.vars` (gitignored):

```bash
cat > .dev.vars <<EOF
JWT_SECRET="<same hex string>"
EOF
```

### 5. (Optional) Google OAuth

In Google Cloud Console, create a **Web OAuth Client ID** (not
Android — the V5.3 client uses the Web client because it talks
directly to Google's `oauth2.googleapis.com/tokeninfo` endpoint).
Then:

```bash
npx wrangler secret put GOOGLE_CLIENT_ID
# Paste the Web client ID
```

### 6. Migrations + deploy

```bash
# Local (Miniflare-backed D1 + R2)
npm run db:migrate
npm run dev
# → http://localhost:8787

# Remote
npm run db:migrate:remote
npm run deploy
# → https://notes-backend.<your-subdomain>.workers.dev
```

`wrangler dev` runs hot-reload for most code changes. Edits to
`wrangler.toml` or `migrations/` require a restart.

---

## API reference

All endpoints accept and return JSON unless noted. All responses
include CORS headers. Errors come back as `{"error":"..."}` with
an appropriate HTTP status.

### Public

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Health check, returns `Notes Backend API — v5.3` |
| POST | `/auth/register` | Create account, returns `{token, user}` |
| POST | `/auth/login` | Sign in, returns `{token, user}` |
| POST | `/auth/google` | Google OAuth (`{id_token}`), returns `{token, user}` |

### Authenticated (`Authorization: Bearer <token>`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/auth/me` | Current user info |
| GET | `/notes?since=<change_id>` | Pull notes since cursor. Omit `since` for first sync. |
| POST | `/notes` | Upsert note (client provides `id` UUID) |
| GET | `/notes/<id>` | Single note |
| DELETE | `/notes/<id>` | Hard-delete (legacy; new code uses soft-delete via `deleted_at`) |
| POST | `/upload` | Base64-encoded image, returns `{id, url, r2_key, content_type, size}` |
| GET | `/files/<key>` | Serve an image, but **only if you uploaded it** (BUG-6 fix) |

---

### POST /auth/register

```bash
curl -X POST https://<worker>/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"hunter2hunter2"}'
```

Returns:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "user": { "id": 1, "email": "alice@example.com", "created_at": 1730000000000 }
}
```

Password is PBKDF2 / SHA-256 / 100k iterations, 16-byte random salt.
Stored as `salt:hash` (both hex).

### POST /auth/login

```bash
curl -X POST https://<worker>/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"hunter2hunter2"}'
```

Same response shape as register.

### POST /auth/google

```bash
curl -X POST https://<worker>/auth/google \
  -H "Content-Type: application/json" \
  -d '{"id_token":"<google-id-token>"}'
```

The server hits `https://oauth2.googleapis.com/tokeninfo?id_token=...`
and verifies that `aud` matches `GOOGLE_CLIENT_ID` before issuing
its own JWT.

---

### GET /notes

```bash
# First sync — get everything
curl "https://<worker>/notes" -H "Authorization: Bearer $TOKEN"

# Incremental sync — get only changes since the cursor
curl "https://<worker>/notes?since=42" -H "Authorization: Bearer $TOKEN"
```

**V5.3 sync cursor:** `since` is now a monotonic `change_id` (Long)
from the `change_log` table, NOT a millisecond. The response also
includes a fresh `sync_cursor` to use for the next pull:

```json
{
  "notes": [
    {
      "id": "f6c5a8b1-1234-5678-9abc-def012345678",
      "user_id": 1,
      "title": "Shopping list",
      "content": "Milk\nEggs",
      "created_at": 1700000000000,
      "client_updated_at": 1700000001000,
      "change_id": 42,
      "deleted_at": null,
      "is_favorite": 0,
      "is_trashed": 0,
      "background_color": 0
    }
  ],
  "sync_timestamp": 1700000002000,
  "sync_cursor": 42
}
```

### POST /notes

```bash
curl -X POST https://<worker>/notes \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "id": "f6c5a8b1-1234-5678-9abc-def012345678",
    "title": "Hello",
    "content": "World",
    "is_favorite": 0,
    "is_trashed": 0,
    "background_color": 0,
    "client_updated_at": 1700000001000,
    "created_at": 1700000000000
  }'
```

The server upserts. `client_updated_at` is the client's
authoritative clock (no `MAX(updated_at, Date.now())` hack — the
server stores it verbatim to avoid clock-skew bugs).

### DELETE /notes/<id>

Hard delete. New code uses soft-delete (sets `deleted_at`) instead.

### POST /upload

```bash
curl -X POST https://<worker>/upload \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"filename\":\"cat.jpg\",\"content_type\":\"image/jpeg\",\"data\":\"$(base64 -w0 cat.jpg)\"}"
```

Constraints:
- `content_type` must start with `image/`
- decoded bytes ≤ 8 MB (Cloudflare Worker memory cap safety net)
- 16 MB pre-encoding headroom on the client side (V5.3.0 BUG-7 fix)

Response:
```json
{
  "id": "3c2f5770-204c-4c35-b0c6-bed2219f577c",
  "url": "https://<worker>/files/uploads/1/3c2f5770.../cat.jpg",
  "r2_key": "uploads/1/3c2f5770-204c-4c35-b0c6-bed2219f577c/cat.jpg",
  "content_type": "image/jpeg",
  "size": 124532,
  "user_id": 1
}
```

### GET /files/<key>

Auth-gated. The server looks up `(r2_key, user_id)` in the `images`
table; if the row doesn't exist (or belongs to a different user),
returns 404. This prevents cross-user leaks of R2 URLs.

```bash
curl "https://<worker>/files/uploads/1/3c2f5770.../cat.jpg" \
  -H "Authorization: Bearer $TOKEN" --output cat.jpg
```

---

## Data model

```sql
-- users
id              INTEGER PRIMARY KEY AUTOINCREMENT
email           TEXT UNIQUE NOT NULL
password_hash   TEXT NOT NULL
created_at      INTEGER

-- notes
id                  TEXT PRIMARY KEY   (UUID, client-generated)
user_id             INTEGER NOT NULL
title               TEXT NOT NULL DEFAULT ''
content             TEXT NOT NULL DEFAULT ''   (block-serialized)
created_at          INTEGER
client_updated_at   INTEGER                 (clock-skew safe)
updated_at          INTEGER                 (= client_updated_at)
change_id           INTEGER                 (monotonic sync cursor)
deleted_at          INTEGER                 (soft-delete)
is_favorite         INTEGER
is_trashed          INTEGER
background_color    INTEGER

-- images
id              TEXT PRIMARY KEY   (UUID)
user_id         INTEGER NOT NULL
note_id         TEXT              (nullable)
r2_key          TEXT UNIQUE NOT NULL
content_type    TEXT NOT NULL
size_bytes      INTEGER NOT NULL
created_at      INTEGER

-- change_log
id              INTEGER PRIMARY KEY AUTOINCREMENT
user_id         INTEGER
note_id         TEXT
op              TEXT              ('upsert' | 'delete')
ts              INTEGER
```

Indexes:
- `idx_notes_change_id (user_id, change_id)` — speeds up the `?since=N` pull
- `idx_notes_deleted_at (user_id, deleted_at)` — speeds up the soft-delete filter
- `idx_change_log_user_id (user_id, id)` — speeds up the pull-cursor walk
- `idx_images_r2_user (r2_key, user_id)` — speeds up `/files/<key>` auth

---

## Auth flow

```
┌────────┐                                  ┌──────────┐
│ Client │ ── POST /auth/login (email/pw) ─►│  Worker  │
│        │ ◄── {token, user} ──────────── │          │
│        │                                  └──────────┘
│        │  Stores token in AuthStore (SharedPreferences).
│        │
│        │  Every subsequent request:
│        │  Authorization: Bearer <token>
│        │ ─────────────────────────────────────►
│        │
│        │  Token contains: { sub: <user_id>, iat, exp }
│        │  Verified with HMAC-SHA256(JWT_SECRET).
│        │  30-day TTL.
```

JWT is HS256 (HMAC-SHA256) — the same secret is used to sign and
verify, so anyone with the secret can forge tokens. Keep
`JWT_SECRET` in Cloudflare secrets, never in `wrangler.toml`.

---

## Sync flow

```
Mobile (Room DB)                        Cloudflare (D1)
───────────────                         ───────────────
1. User edits a note.
2. Room write (note.dirtyRevision++).

3. Debounced push (5s) ─── POST /notes ──►  Upsert. Appends
                                                to change_log.
                            ◄─ {change_id, …} ──
4. markSynced(localId, lastSyncedRevision=dirtyRevision).

5. Periodic / pull-to-refresh.
   GET /notes?since=<lastCursor> ────────────►
                            ◄─ {notes[], sync_cursor} ──
6. 3-way merge each note:
   base = row.baseTitle/baseContent (last common ancestor)
   local = current local state
   server = server's current state
   → take server if base==local
   → keep local if base==server
   → line-union if both diverged similarly
   → mark is_conflict=true otherwise

7. Pull-cursor advanced. Next pull is incremental.
```

### Soft-delete

Notes are never hard-deleted on the server. Setting `deleted_at`
is a tombstone. The local row is hard-deleted only after a pull
sees the server's matching `change_log` entry — preventing
ghost-note resurrection on a flaky network.

---

## Security notes

- `JWT_SECRET` must be a 32-byte random hex string (64 hex chars).
  Rotating it invalidates all existing sessions (clients see 401 and
  bounce back to the login screen).
- `database_id` in `wrangler.toml` must match the id returned by
  `wrangler d1 create`. Re-creating the database produces a new id.
- `notes-images` is the R2 bucket name. The bucket must exist
  before deploying.
- `/files/*` is **auth-gated** (V5.3 BUG-6 fix). Earlier V3.0
  builds served them publicly; the V5.3 release closes that hole.
- All passwords are PBKDF2 / SHA-256 / 100k iterations. PBKDF2 is
  implemented with Web Crypto, not Node's `crypto`, because this
  code runs in the Workers V8 isolate.

---

## Local dev tips

```bash
# Watch live logs from your deployed worker
wrangler tail

# Query the local D1
wrangler d1 execute notes-db --local --command "SELECT * FROM users;"

# Query the remote D1
wrangler d1 execute notes-db --remote --command "SELECT id, email FROM users;"

# Insert a fake R2 object for local testing
wrangler r2 object put notes-images/test/sample.png --file sample.png

# Reset local state
rm -rf .wrangler/state/
```

---

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | `wrangler dev` (local D1 + R2) |
| `npm run deploy` | `wrangler deploy` |
| `npm run db:migrate` | Apply migrations to local D1 |
| `npm run db:migrate:remote` | Apply migrations to remote D1 |
