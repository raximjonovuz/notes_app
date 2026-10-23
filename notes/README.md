# Notes (Android, V5.3)



Native Android client for the **Notes** sync service. Kotlin + Jetpack
Compose + Room 2.7.0 + OkHttp 4.12.0. Talks to a Cloudflare Worker
backend (see `../notes-backend`).

| Aspect | Value |
|---|---|
| Min SDK | 24 (Android 7.0) |
| Target SDK | 37 |
| UI | Jetpack Compose (Material 3) |
| Storage | Room (SQLite, in-app) |
| Networking | OkHttp + kotlinx.serialization |
| Auth | JWT in `EncryptedSharedPreferences` (Android Keystore) |
| Sync | Monotonic `change_id` cursor (no wall-clock reliance) |

---

## Quick start

```bash
# JDK 21 (Temurin) must be on PATH.
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:Path      = "$env:JAVA_HOME\bin;$env:Path"

# Unit tests + debug APK
.\gradlew :app:testDebugUnitTest
.\gradlew :app:assembleDebug

# adb install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds require `app/keystore.properties` (gitignored). The
build fails fast when the file is missing — see
[Release signing](#release-signing) below.

---

## API contract (V5.3)

The client talks to the `notes-backend` Worker. Endpoints it actually
uses:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/auth/login` | Email + password sign-in (disabled by default; enable with `ALLOW_PASSWORD_AUTH=true`) |
| `POST` | `/auth/register` | Same as login; creates the account |
| `POST` | `/auth/google` | Google Sign-In (`{id_token}`) |
| `GET`  | `/auth/me` | Current user profile |
| `POST` | `/auth/logout` | Bumps the user's `token_version` so all issued JWTs are revoked |
| `GET`  | `/notes` | Full sync — returns every live note for the active user |
| `GET`  | `/notes?since=<change_id>` | Incremental sync — returns notes touched since the cursor (including tombstones) |
| `POST` | `/notes` | Upsert a note. Client supplies the UUID; the server echoes it back |
| `DELETE` | `/notes/<id>` | Hard-delete (legacy); the client now issues soft-deletes via `deleted_at` in the upsert body |
| `POST` | `/upload` | Base64-encoded image, returns `{url, r2_key, content_type, size}` |
| `GET`  | `/files/<key>` | Auth-gated image serve — caller must own the row in `images` |

### Sync semantics

1. `since` is a monotonic `change_id` (Long), never a millisecond. The
   server's `change_log` is the source of truth; the client only
   stores its last-seen `change_id` (per-user, see B6 below).
2. Tombsone rows (`deleted_at != null`) are returned in `since` pulls
   so the client can purge its local copy immediately. A full sync
   (`since` omitted) hides tombstones — it's the "first sync" payload.
3. `sync_cursor` in the response is the cursor for the **next** pull.
   Echoed back as `since`.
4. `has_more` (bool) is set when the response hit the page limit
   (default 200, max 500). Clients keep pulling until `has_more=false`.
5. `client_updated_at` (sent by the client) is the only authoritative
   timestamp. The server stores it verbatim; no `MAX(updated_at,
   Date.now())` is used.

### Content format (v2)

`NoteEntity.content` is a **block-serialized** string. The on-disk
shape is documented in `ui/edit/ContentBlock.kt`:

- `<n|style|font|textSize>body` — paragraph or list item
  (`n` is the block index, `style` is a 0|1 bitmap for bold/italic,
  etc.; the second `|` segment carries inline `StyleRange` markers —
  see `ui/edit/StyleRange.kt`).
- `|||IMG:<uri>|||` — image block.

The Markdown export in `util/MarkdownExporter.kt` reads the same
schema and emits CommonMark + a few HTML fallbacks for the
no-Markdown features (color, underline, strikethrough).

### Tombstone handling (B1)

The client soft-deletes (`deletedAt = now`) instead of issuing a Room
DELETE. The push pass then sends `DELETE /notes/<id>` which is a hard
server-side delete. Either 200 or 404 from the server purges the local
row in the same pass — no two-phase "soft-delete locally, wait for
the confirming pull" dance.

### serverId (B3)

`NoteEntity.serverId` is a UUID stamped at insert time (not at push
time). The push body carries the same UUID the local row already
holds; the server's UPSERT echoes it back unchanged. This makes the
UNIQUE index on `serverId` safe (and lets us detect accidental
duplicate inserts).

### Multi-account (B6)

Every per-user cursor (`SyncStore.lastCursor`) and pending-flush
version (`SyncStore.pendingFlushVersion`) is namespaced by `userId`.
A switch-account mid-sync cannot mix cursors; the `syncMutex`
serialises push and pull for the same reason.

---

## Build / test

```bash
# Compile + unit tests
.\gradlew :app:testDebugUnitTest

# Debug APK
.\gradlew :app:assembleDebug

# Release APK (needs app/keystore.properties)
.\gradlew :app:assembleRelease
```

The unit tests cover:

- `data/remote/ApiClientEnvelopeTest.kt` — `{note:{...}}` envelope
  parsing + 5xx / empty-body error paths.
- `data/remote/ApiClientUploadTest.kt` — image-upload MIME detection
  (PNG, JPEG, WebP, GIF, HEIC) and 4xx error reporting.
- `data/remote/NotesSyncRepositoryTest.kt` — dedup / dirty-row /
  anonymous-to-user flows.
- `data/remote/PushDeferLocalImageTest.kt` — `file://` deferral
  (must not push a note whose image upload is still in flight).

Run with `--no-configuration-cache` if the configuration-cache warns
about stale state.

---

## Release signing

`app/keystore.properties` is gitignored. Required shape:

```properties
keyAlias=...
keyPassword=...
storeFile=release.keystore
storePassword=...
```

If the file is missing, `assembleRelease` fails fast with:

```
keystore.properties not found at <abs path> — required for release builds.
```

The Gradle `taskGraph.whenReady` hook intercepts the task graph before
`validateSigningRelease` runs so the error is clear (rather than the
generic AGP keystore complaint).

`assembleDebug` is unaffected.

---

## Source layout

```
app/src/main/java/com/example/notes/
├── data/
│   ├── local/        NoteDao, NoteEntity, NoteDatabase, AuthStore, SyncStore
│   ├── remote/       ApiClient, AuthRepository, NotesSyncRepository
│   └── repository/   NoteRepository (single source of truth)
├── ui/
│   ├── auth/         LoginScreen, AuthViewModel
│   ├── edit/         NoteEditScreen + ViewModel + ContentBlock + StyleRange
│   ├── notes/        NotesListScreen + ViewModel
│   ├── settings/     SettingsScreen
│   ├── splash/       SplashScreen
│   └── theme/        Material 3 theme
├── util/
│   ├── BackupExporter.kt   SAF export + restore
│   ├── MarkdownExporter.kt block-serialised → CommonMark
│   ├── ImageSniffer.kt     magic-byte MIME sniffer
│   └── Logger.kt           Logcat wrapper that drops PII in release
└── work/             BackgroundSyncWorker (WorkManager)
```

---

## Security notes

- Bearer tokens live in **EncryptedSharedPreferences** (C1). Rotating
  the device's lockscreen does NOT force a re-login; reinstalling the
  app does.
- `Logger` drops `DEBUG` / `INFO` in release builds. Tokens, emails,
  passwords, and note titles are never logged.
- The Android client never logs `Authorization: Bearer …`,
  `id_token`, or `password` values — see `grep -rn "Bearer\|id_token\|password" app/src`.