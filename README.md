# Notes

A simple, fast, offline-first notes app for Android, paired with a
Cloudflare Workers backend for cross-device sync.

> **Current status:** Android client v5.2.0 · Backend v3.0 (deployed
> to Cloudflare Workers) · Google OAuth sign-in · Auto-save · Sync over
> Cloudflare D1 · R2 image storage

---

## Repository layout

```
.
├── notes/                    # Android app (Kotlin + Jetpack Compose)
│   ├── app/                  # Application module
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── notes-backend/            # Cloudflare Workers backend
│   ├── src/
│   │   ├── routes/
│   │   └── index.ts          # Main router
│   ├── migrations/           # D1 schema migrations
│   └── wrangler.toml
├── dist/                     # Built APKs (gitignored — see .gitignore)
├── PROGRESS.md               # Detailed per-version progress log
└── README.md                 # this file
```

## Highlights

- **Native Android UI** built with Jetpack Compose + Material 3,
  Room for local storage, single-Activity navigation.
- **WYSIWYG editor toolbar** with per-character B/I/U/S, per-line font
  size and list type, text color, background color, and inline image
  blocks. Auto-save on every edit, undo/redo stack.
- **Cloudflare backend** — D1 (SQLite-at-edge) for note storage,
  R2 for image storage, JWT (HS256) auth, PBKDF2 password hashing,
  Google OAuth via Google Identity Services.
- **Sync** — pull-based background sync via WorkManager;
  `lastSyncAgo` shown in the list, manual sync + pull-to-refresh in
  the toolbar.
- **Backup** — Settings → Export all notes saves every local note
  (including formatting, images, account info) to a JSON file via
  Storage Access Framework.

## Architecture

### Android (`notes/`)

```
ui/
  splash/    splash screen
  auth/      login screen (Google + email/password)
  notes/     list view (linear / grid, pull-to-refresh sync)
  edit/      editor (Title + block-based editor with toolbar)
  settings/  theme, account, backup, version
data/
  local/     Room database (notes + auth prefs)
  remote/    OkHttp + kotlinx.serialization REST client
  repository/ NoteRepository (single source of truth)
work/         WorkManager periodic background sync
```

MVVM throughout: each screen has a `ViewModel` exposing
`StateFlow<…State>` and intent-style callbacks. The editor stores
notes as a list of typed `ContentBlock`s (`TextBlock`, `ImageBlock`)
where `TextBlock` carries both the visible text and the persisted rich-
text `styleRanges` (bold/italic/underline/strikethrough/color per
character range, with a "pending anchor" that arms the next typed
char).

### Backend (`notes-backend/`)

```
src/
├── index.ts          # Worker fetch router
├── routes/
│   ├── auth.ts        # /auth/register, /auth/login, /auth/me, /auth/google
│   ├── notes.ts       # /notes (GET/POST), /notes/:id (GET/DELETE)
│   └── upload.ts      # /upload + /files/<key> (R2 serve)
├── auth.ts            # PBKDF2 password hashing + JWT (HMAC HS256)
├── utils.ts           # CORS, JSON helpers
└── types.ts           # Shared TS types
```

Data model:

```
users(id, email, password_hash, created_at)
notes(id TEXT PK UUID, user_id, title, content,
      created_at, updated_at, is_favorite, is_trashed, background_color)
images(id TEXT PK UUID, user_id, note_id, r2_key, content_type,
       size_bytes, created_at)
```

## Local development

### Android

```bash
cd notes
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The app targets **minSdk 24** (Android 7.0) and **targetSdk 37**.
JDK 21 is required to build.

### Backend

```bash
cd notes-backend
npm install
npx wrangler login
npx wrangler d1 create notes-db
# paste the printed database_id into wrangler.toml
npx wrangler r2 bucket create notes-images
# Set JWT_SECRET (≥ 32 random bytes):
node -e 'console.log(require("crypto").randomBytes(32).toString("hex"))'
# Then either put it in wrangler.toml [vars] OR upload it as a secret:
npx wrangler secret put JWT_SECRET
# (optional) Google OAuth
npx wrangler secret put GOOGLE_CLIENT_ID
npm run db:migrate      # local D1
npm run db:migrate:remote # remote D1
npm run deploy
```

## Versioning

The Android app version is in `notes/app/build.gradle.kts`:

- `versionCode` — integer, must increase for each Play upload
- `versionName` — human-readable (`5.2.0`)

The backend version is set in `notes-backend/src/index.ts` (the
`Notes Backend API — v3.0` banner in the `/` response). D1 schema is
versioned via files in `migrations/`.

## Notable files worth reading

| File | What it shows |
|------|---------------|
| `notes/app/src/main/java/com/example/notes/ui/edit/NoteEditViewModel.kt` | Save / sync mutex, auto-save debounce, sticky-anchor rich-text toggle |
| `notes/app/src/main/java/com/example/notes/ui/edit/StyleRange.kt` | Per-character style ranges, anchor & shift-on-text-change |
| `notes-backend/src/routes/notes.ts` | Pull-sync & upsert with `MAX(updated_at)` tie-break |
| `notes-backend/src/auth.ts` | PBKDF2 / SHA-256 password hashing + JWT HS256 |

## License

Personal/educational project. See `PROGRESS.md` for the full history.
