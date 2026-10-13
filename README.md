# Notes

A simple, fast, **offline-first** notes app for Android with cross-device
sync through a serverless Cloudflare Workers backend.

> **Current status (Oct 2026):**
> - Android client **v5.3.3** (Kotlin + Jetpack Compose)
> - Backend **V5.3** (Cloudflare Workers + D1 + R2)
> - Features: email/password + Google OAuth, JWT auth, WYSIWYG editor,
>   auto-save, multi-device sync, inline image blocks (R2 storage),
>   3-way merge with conflict detection, soft-delete, background sync
>   via WorkManager

---

## Repository layout

```
.
├── notes/                      # Android app (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── build.gradle.kts    # versionName / versionCode live here
│   │   └── src/main/
│   │       ├── java/com/example/notes/
│   │       │   ├── data/
│   │       │   │   ├── local/    # Room DB, AuthStore, SyncStore
│   │       │   │   ├── remote/   # ApiClient, NotesSyncRepository
│   │       │   │   └── repository/
│   │       │   ├── ui/
│   │       │   │   ├── auth/     # LoginScreen + Google Sign-In
│   │       │   │   ├── notes/    # NotesListScreen (linear/grid)
│   │       │   │   ├── edit/     # NoteEditScreen + WYSIWYG toolbar
│   │       │   │   ├── settings/ # Theme, account, backup, version
│   │       │   │   ├── splash/
│   │       │   │   └── util/     # LoadedImage (R2 image rendering)
│   │       │   ├── util/         # ImageSniffer, Logger, exporters
│   │       │   └── work/         # BackgroundSyncWorker
│   │       └── res/
│   ├── gradle/libs.versions.toml # Version catalog
│   └── settings.gradle.kts
│
├── notes-backend/              # Cloudflare Workers (TypeScript)
│   ├── src/
│   │   ├── index.ts            # Main router (entry)
│   │   ├── auth.ts             # PBKDF2 + JWT (HS256)
│   │   ├── utils.ts            # CORS, JSON helpers
│   │   ├── types.ts            # Shared TS types
│   │   └── routes/
│   │       ├── auth.ts         # /auth/register, /login, /me, /google
│   │       ├── notes.ts        # /notes CRUD + sync
│   │       └── upload.ts       # /upload + /files/<key> (R2 serve)
│   ├── migrations/             # D1 schema (0001, 0002, 0003)
│   ├── wrangler.toml           # Cloudflare config (no secrets here!)
│   └── .dev.vars               # Local secrets (gitignored)
│
├── dist/                       # Built APKs (gitignored)
├── PROGRESS.md                 # Per-version development log
├── note-apps-research-uz.md    # Research on similar open-source apps
└── README.md                   # this file
```

---

## Architecture overview

```
┌──────────────────┐    HTTPS     ┌──────────────────────┐    SQL    ┌────────────┐
│  Android device  │ ◄──────────► │  Cloudflare Worker   │ ◄───────► │  D1 (SQL)  │
│  (Compose UI)    │  Bearer JWT  │  (TypeScript, edge)  │           └────────────┘
│                  │              │                      │    R2 API ┌────────────┐
│  + Room DB       │              │  Auth / Notes /      │ ◄───────► │  R2 bucket │
│  (source of      │              │  Upload / Files      │           │ (images)   │
│   truth)         │              │                      │           └────────────┘
└──────────────────┘              └──────────────────────┘
```

**Three layers, each with a clear responsibility:**

1. **Android client** — UI, local DB (Room), image picker, OkHttp +
   Kotlinx-serialization for the REST client. Offline-first: writes
   always land in Room, then a debounced push mirrors to the server.
2. **Cloudflare Worker** — Stateless request handler. Validates JWT,
   looks up the user, and routes to the right module (auth / notes /
   upload).
3. **D1 + R2** — D1 holds users, notes, images-metadata, and a
   `change_log` for monotonic sync cursors. R2 holds the actual image
   bytes.

The server is the **source of truth across devices**, but the local
Room DB is the **source of truth for the current device**. This
offline-first shape means the app works on a plane.

---

## Tech stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3, single-Activity Navigation |
| Local DB | Room v10 (SQLite) with KSP-generated DAOs |
| Network | OkHttp 4 + kotlinx.serialization (JSON), OkHttp interceptor for bearer token |
| Auth | JWT HS256 (server) + Android `AuthStore` (multi-account, SharedPreferences) |
| Image bytes | Cloudflare R2 (S3-like), auth-gated reads via `/files/<key>` |
| Sync | Pull-based background sync via WorkManager; debounced push |
| Google sign-in | Google Identity Services SDK (`com.google.android.gms:play-services-auth:21.4.0`) |
| Backend | Cloudflare Workers (V8 isolate), TypeScript strict mode |
| Database | Cloudflare D1 (SQLite at edge) |
| Password hashing | PBKDF2 / SHA-256 / 100k iterations (Web Crypto API) |

---

## Building the Android app

### Prerequisites

- **JDK 21** (Temurin recommended). The Android Studio bundled JBR is
  often broken — install Temurin from `https://adoptium.net` and set
  `JAVA_HOME` to that.
- **Android SDK** with build-tools 36+ and platform 37.
- An Android device with USB debugging enabled (or an emulator).

### Build

```bash
cd notes
# PowerShell (Windows):
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\gradlew.bat assembleDebug
# bash (macOS / Linux):
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. A copy is
also written to `dist/notes-<version>-debug.apk`.

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app targets `minSdk 24` (Android 7.0) and `targetSdk 37`.

### Tweak the backend URL

If you're not using the public deployment at
`https://notes-backend.raximjonozod1727.workers.dev`, point the app at
your own Worker by editing
`notes/app/src/main/java/com/example/notes/data/remote/ApiClient.kt`:

```kotlin
var BASE_URL: String = "https://your-worker.your-subdomain.workers.dev"
```

Then rebuild.

---

## Building the backend

### Prerequisites

- **Node.js 20+** and **npm**.
- A **Cloudflare account** (free tier is enough).
- `wrangler` CLI: `npm install -g wrangler` (or use the local copy in
  `node_modules/.bin/wrangler`).

### First-time setup

```bash
cd notes-backend
npm install
npx wrangler login
```

### 1. Create the D1 database

```bash
npx wrangler d1 create notes-db
# Output includes: "database_id = abc123-def4-..."
```

Open `wrangler.toml` and paste the printed `database_id`:

```toml
[[d1_databases]]
binding = "DB"
database_name = "notes-db"
database_id = "abc123-def4-..."   # ← paste here
```

### 2. Create the R2 bucket

```bash
npx wrangler r2 bucket create notes-images
```

### 3. Generate and store the JWT secret

```bash
# Generate a 32-byte random hex string
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
# Output: 64 hex characters — copy this
```

**Set it as a Cloudflare secret** (NOT in `wrangler.toml` — secrets
must not be committed to git):

```bash
npx wrangler secret put JWT_SECRET
# Paste the hex string when prompted
```

> ⚠️ **Security note:** Never put `JWT_SECRET` in `wrangler.toml` or
> commit it to git. The V5.3.3 release rotates a previously-exposed
> secret and moves it strictly to Cloudflare secrets.

For **local development**, also write it to `.dev.vars` (gitignored):

```bash
cat > .dev.vars <<EOF
JWT_SECRET="<paste the same hex string here>"
EOF
```

### 4. (Optional) Google OAuth

```bash
npx wrangler secret put GOOGLE_CLIENT_ID
# Paste your Web OAuth client ID from Google Cloud Console
```

### 5. Apply migrations and deploy

```bash
# Local (Miniflare-backed) — for testing on http://localhost:8787
npm run db:migrate
npm run dev

# Remote — your live worker
npm run db:migrate:remote
npm run deploy
```

After deploy, your worker lives at:
`https://notes-backend.<your-cloudflare-subdomain>.workers.dev`

---

## API reference

All endpoints accept and return JSON. All responses include CORS
headers. Errors are `{"error":"<message>"}` with appropriate status
codes.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/` | — | Health check (`Notes Backend API — v5.3`) |
| POST | `/auth/register` | — | Create account, returns `{token, user}` |
| POST | `/auth/login` | — | Sign in, returns `{token, user}` |
| POST | `/auth/google` | — | Google OAuth (`{id_token}`), returns `{token, user}` |
| GET | `/auth/me` | yes | Current user info |
| GET | `/notes?since=<changeId>` | yes | Pull notes since cursor (omit `since` for first sync) |
| POST | `/notes` | yes | Upsert note (client provides `id` UUID) |
| GET | `/notes/<id>` | yes | Single note |
| DELETE | `/notes/<id>` | yes | Hard-delete (kept for V3 compat; new code uses soft-delete via `deleted_at`) |
| POST | `/upload` | yes | Base64-encoded image → R2 |
| GET | `/files/<key>` | yes | Serve an image, but only if you uploaded it (BUG-6 fix) |

> **V5.3 sync cursor:** the `since` parameter is a **monotonic
> `change_id`** (not a millisecond). The server appends one row to
> `change_log` per upsert/delete and returns the auto-incremented
> `id`. This eliminates wall-clock skew bugs.

### Auth

`Authorization: Bearer <token>` on every authenticated request. Tokens
expire after **30 days** (`JWT_TTL_SECONDS` in `src/auth.ts`).

### Full curl examples

```bash
# Register
curl -X POST https://<worker>/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"hunter2hunter2"}'

# Login (response has {token, user})
TOKEN=$(curl -s -X POST https://<worker>/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"hunter2hunter2"}' \
  | jq -r .token)

# Push a note
curl -X POST https://<worker>/notes \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "id":"f6c5a8b1-1234-5678-9abc-def012345678",
    "title":"Hello",
    "content":"World",
    "is_favorite":0,
    "is_trashed":0,
    "background_color":0,
    "client_updated_at":1700000000000,
    "created_at":1700000000000
  }'

# Pull everything
curl "https://<worker>/notes" -H "Authorization: Bearer $TOKEN"

# Upload an image
curl -X POST https://<worker>/upload \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"filename\":\"cat.jpg\",\"content_type\":\"image/jpeg\",\"data\":\"$(base64 -w0 cat.jpg)\"}"
```

---

## Data model

```
users
├── id              INTEGER PRIMARY KEY AUTOINCREMENT
├── email           TEXT UNIQUE NOT NULL
├── password_hash   TEXT NOT NULL        (PBKDF2 salt:hash)
└── created_at      INTEGER NOT NULL

notes
├── id                  TEXT PRIMARY KEY   (UUID, client-generated)
├── user_id             INTEGER NOT NULL
├── title               TEXT NOT NULL DEFAULT ''
├── content             TEXT NOT NULL DEFAULT ''   (block-serialized)
├── created_at          INTEGER
├── client_updated_at   INTEGER             (BUG-5 fix: clock-skew safe)
├── updated_at          INTEGER             (alias for client_updated_at)
├── change_id           INTEGER             (monotonic cursor, BUG-4 fix)
├── deleted_at          INTEGER             (soft-delete, BUG-2 fix)
├── is_favorite         INTEGER
├── is_trashed          INTEGER
└── background_color    INTEGER

images
├── id              TEXT PRIMARY KEY   (UUID)
├── user_id         INTEGER NOT NULL
├── note_id         TEXT              (nullable)
├── r2_key          TEXT UNIQUE NOT NULL   ("uploads/<userId>/<uuid>/<filename>")
├── content_type    TEXT NOT NULL
├── size_bytes      INTEGER NOT NULL
└── created_at      INTEGER

change_log
├── id              INTEGER PRIMARY KEY AUTOINCREMENT
├── user_id         INTEGER
├── note_id         TEXT
├── op              TEXT              ("upsert" | "delete")
└── ts              INTEGER

idx_images_r2_user      (r2_key, user_id)   — speeds up /files/<key> auth
idx_notes_change_id     (user_id, change_id) — speeds up incremental pull
idx_change_log_user_id  (user_id, id)        — speeds up pull cursor
```

Migrations live in `notes-backend/migrations/`. They run additively — a
brand-new install applies all three; an existing one only applies the
ones it doesn't already have.

---

## Sync protocol

Three pieces of state on every note row:

- `dirtyRevision`     — bumps on every local edit
- `lastSyncedRevision` — the dirtyRevision the server last acknowledged
- `serverChangeId`    — the server's monotonic cursor for this row

The push-side query is `WHERE dirtyRevision > lastSyncedRevision` —
this catches every local change, including favorite / pin / background
flips on already-synced notes (a bug the V5.0 release fixed).

The pull-side query uses the server's `change_log` (monotonic
`change_id`, no wall-clock reliance) and runs a **3-way merge** on
each row:

```
                  ┌──────────────────────────────────────────────┐
                  │  base (last-known common ancestor)           │
                  └───────────────┬──────────────────────────────┘
                                  │
                  ┌───────────────┴───────────────┐
                  │                               │
            local diverged?               server diverged?
                  │                               │
              take server                keep local (push later)
                  │                               │
                  └───────────────┬───────────────┘
                                  │
                       both diverged differently?
                                  │
              line-based union (cheap auto-merge)
                                  │
                       no clean merge?
                                  │
            mark is_conflict=1, keep both versions
```

The client stores the "last known common ancestor" in
`baseTitle` / `baseContent` on the row.

### Soft-delete

Notes are never hard-deleted on the server. Instead, `deleted_at` is
set. The client keeps the local row until the next pull confirms the
tombstone in the server's `change_log`, then it purges the row. This
prevents the "ghost note" resurrection race.

---

## Image upload pipeline

1. User picks an image in the editor.
2. The picker URI is **copied** into the app's private files dir as
   `img_<timestamp>.<ext>` (a `file://` URI we control). This avoids
   the `content://` grant-expiry problem when the user reopens the
   note later.
3. The file is uploaded to `/upload` (base64 JSON, ≤ 6 MB JPEG after
   client-side compression).
4. Server stores the bytes in R2 at
   `uploads/<userId>/<uuid>/<filename>` and records the metadata in
   the `images` table.
5. The note's `ImageBlock` is rewritten from `file://` to the public
   `https://<worker>/files/<r2_key>` URL.
6. The next sync pushes the rewritten note to the server.

Rendering an R2 image goes through `imageClient` (a dedicated
`OkHttpClient`) with an **interceptor** that injects the current
bearer token on every request — required since V5.3 because `/files/<key>`
verifies the caller against the `images` table.

---

## Notable files worth reading

| File | What it shows |
|---|---|
| `notes/app/src/main/java/com/example/notes/ui/edit/NoteEditViewModel.kt` | Save / sync mutex, auto-save debounce, sticky-anchor rich-text toggle, image upload orchestration |
| `notes/app/src/main/java/com/example/notes/ui/edit/StyleRange.kt` | Per-character style ranges, anchor & shift-on-text-change |
| `notes/app/src/main/java/com/example/notes/ui/edit/ContentBlock.kt` | Block-based editor: `TextBlock` + `ImageBlock`, serialize/parse to a single content String |
| `notes/app/src/main/java/com/example/notes/data/remote/ApiClient.kt` | OkHttp + interceptor for bearer token, imageClient with auth interceptor |
| `notes/app/src/main/java/com/example/notes/data/remote/NotesSyncRepository.kt` | Push / pull / 3-way merge / soft-delete reconciliation |
| `notes/app/src/main/java/com/example/notes/data/local/NoteDao.kt` | Room queries with `dirtyRevision > lastSyncedRevision` push filter |
| `notes/app/src/main/java/com/example/notes/ui/util/LoadedImage.kt` | R2 image rendering with auth |
| `notes-backend/src/index.ts` | Worker entry / router |
| `notes-backend/src/auth.ts` | PBKDF2 password hashing + JWT HS256 |
| `notes-backend/src/routes/upload.ts` | `/upload` (R2 put + D1 record) and `/files/<key>` (auth-gated R2 serve) |
| `notes-backend/src/routes/notes.ts` | Pull-sync, upsert, soft-delete |
| `notes-backend/migrations/0002_soft_delete_and_change_tracking.sql` | `change_log` table + `client_updated_at` clock-skew fix |

---

## Versioning

- **Android** version is in `notes/app/build.gradle.kts`:
  - `versionCode` — integer, must increase for every Play upload
  - `versionName` — human-readable (`5.3.3`)

- **Backend** version is set in `notes-backend/src/index.ts` (the
  `Notes Backend API — v5.3` banner in the `/` response).

- **D1 schema** is versioned via files in `migrations/`. To add a
  migration, create `migrations/NNNN_description.sql`; the next
  `wrangler d1 migrations apply` will pick it up.

---

## Troubleshooting

### `wrangler` is out of date

```
⛅️ wrangler 3.114.17 (update available 4.150.0)
```

It's a warning, not a blocker. Upgrade with:
```bash
npm install --save-dev wrangler@4
```

### Build fails: `could not open 'C:\Program Files\Android\Android Studio\jbr\lib\jvm.cfg'`

Your Android Studio JBR is broken. Install JDK 21 from
`adoptium.net` and set `JAVA_HOME` to that before running
`./gradlew`.

### `gradle` skips my changes (UP-TO-DATE)

Add `--rerun-tasks` to force a full re-run:
```bash
./gradlew assembleDebug --rerun-tasks
```

### `adb` says "no devices/emulators found"

- Check the cable, try a different USB port
- Run `adb kill-server && adb start-server`
- Make sure USB debugging is enabled in Developer Options

### `/files/<key>` returns 401

Your JWT is missing or expired. The V5.3 backend requires auth on
every file fetch (this is by design — see `routes/upload.ts` for the
ownership check).

### App keeps saying "Image upload failed"

- Check `adb logcat` for the actual exception
- HEIC files (iOS-shared photos) need a hardware decoder; on
  older devices the client may fall back to the raw bytes and the
  upload may exceed 6 MB
- `writeTimeout` was bumped to 60 s in V5.3.1 to handle 1-2 MB images
  on slow 4G

### Sync keeps spinning "deferred for image upload"

Your note still has a `file://…` URI in `content` (the R2 upload
hasn't completed). The push is correctly filtered; the V5.3.0 fix
schedules a retry. If the upload genuinely failed, open the note,
delete the image, and re-add it.

---

## Sharing with friends

To let a friend test your build without giving them Cloudflare
access:

1. Send them the APK from `dist/notes-5.3.3-debug.apk` (or the desktop
   copy at `notes-5.3.3.apk`).
2. They install it on their phone.
3. They open the app, register with their own email/password
   (the auth and D1 are shared, so they get their own `user_id`).
4. Sync works against the same backend, but their notes and images
   are scoped to their own account.

For a **fully independent deployment** (separate Cloudflare account,
separate D1, separate R2), they should follow the "Building the
backend" section above. The only Android change is editing
`ApiClient.BASE_URL`.

---

## License

Personal / educational project. See `PROGRESS.md` for the full
per-version history.
