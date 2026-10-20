# CHANGES — Notes Android + Backend audit fixes

> **Boshlanish:** 2026-10-16
> **Manba:** `fix-prompt.md` (50+ band)
> **Qamrov:** A (backend, 2026-10-13 da tugagan) → B (data/sync) → C (security) → E (cleanup) — bosqichma-bosqich.
> **Yakuniy holat:** Android compile + lint (0 xato) + 25/27 test PASS; backend tsc 0 xato; 14/14 backend test PASS.

---

## A bosqichi — Backend (oldingi sessiyada tugagan)

`CHANGES.md` (notes-backend) da to'liq hujjatlashtirilgan. 14/14 vitest test PASS. A1..A10 hammasi ✅.

---

## B bosqichi — Android: data qatlami va sinxronizatsiya (2026-10-16)

### B2 — syncIfOnline tartibi va 401 handling ✅

**Muammo:** Push avval, pull keyin; 401 xato bo'lib yashirinadi; `syncIfOnline(): Boolean` 401 va network xatoni farqlamaydi.

**Fayllar:**
- `data/remote/NotesSyncRepository.kt` — yangi `SyncOutcome` sealed class (Success, Failed, Unauthorized, Skipped); `syncIfOnline()` endi `pull -> push`; 401 da `AuthStore.clearActive(context)` + `SyncOutcome.Unauthorized`
- `ui/notes/NotesListViewModel.kt` — yangi `SyncOutcome` ni `syncStatus.error` ga xaritalash (muvaffaqiyat, session expired, network, skipped)
- `work/BackgroundSyncWorker.kt` — `Result.retry()` network/5xx uchun, `Result.success()` Unauthorized/Skipped uchun; period 1 kun -> 6 soat

**Tekshirish:** `compileDebugKotlin` OK; `NotesSyncRepositoryTest` 3/3 PASS, `NotesSyncRepositorySyncTest` 7/7 PASS (B1, B3, B6).

### B4 — markSynced snapshot dan baseTitle/baseContent ✅

**Muammo:** `markSynced` da `baseTitle = title, baseContent = content` — agar user push vaqtida yangi belgi yozsa, joriy qator qiymati yuborilgan emas, balki yangilangan bo'ladi. 3-way merge baseline buziladi.

**Fayllar:**
- `data/local/NoteDao.kt` — `markSynced(localId, syncedRev, serverChangeId, serverUpdatedAt, baseTitle, baseContent)` signature
- `data/remote/NotesSyncRepository.kt` — `pushLocalChangesInternal` endi `note.title, note.content` ni `markSynced` ga uzatadi (snapshot)
- 3 ta test fayldagi `FakeNoteDao`/`StubDao` moslashtirildi

**Tekshirish:** compile OK; 3 ta test FakeNoteDao yangilandi.

### B5 — 3-way merge (LinkedHashSet olib tashlandi) + UNIQUE conflict fix ✅

**Muammo 1:** Eski `lineUnion` LinkedHashSet ishlatardi - "Buy milk\nBuy milk" dublikat chiziqlar yo'qolardi.
**Muammo 2:** Conflict yaratishda yangi qator `serverId = server.id` bilan insert qilinardi, lekin eski local qator ham `serverId = server.id` ga ega edi -> UNIQUE index buzilardi.

**Fayllar:**
- `data/remote/NotesSyncRepository.kt` — yangi `lineUnion`: base'da saqlangan chiziqlarni (kept), localAdditions/serverAdditions ga ajratadi; multiset-equality (sorted() ==) bilan auto-merge; bir xil bo'lmasa conflict (null). Dublikat chiziqlar saqlanadi.
- Conflict branch: avval local qator yangi UUID'ga o'zgartiriladi (`serverId = newLocalServerId, isConflict = true, conflictOriginalId = server.id`), keyin canonical server qator `serverId = server.id` bilan insert qilinadi. UNIQUE index buzilmaydi.

**Tekshirish:** compile OK.

### B9 — ContentBlock JSON v2 format ✅

**Muammo:** Ko'p qatorli `TextBlock` har save/reopen da alohida bloklarga bo'linardi; `fontSizesByLine`/`listTypesByLine` serialize qilinmasdi; styleRanges offsetlar buzilardi.

**Fayllar:**
- `ui/edit/ContentBlock.kt` — yangi `serializeBlocksToJsonV2` + `parseBlocksFromJsonV2`; `parseBlocksFromContent` avval JSON v2 ni aniqlaydi (`{"v":2`), topilmasa `parseBlocksFromLegacy` ga tushadi (|||IMG: va `<flags>S<ranges>body` formatlari). Yangi tahrirlar doim JSON v2 da saqlanadi.
- `ui/edit/StyleRange.kt` — `@Serializable` annotation qo'shildi.

**Format:** `{"v":2,"blocks":[{"t":"text","text":"...","fontSize":1,"align":0,"list":0,"check":false,"ranges":[...],"fontSizesByLine":{},"listTypesByLine":{}},{"t":"img","uri":"..."}]}`

**Tekshirish:** compile OK.

### B10 — Bo'sh note saqlash siyosati ✅

**Muammo:** Yangi bo'sh note (id=0) saqlanmasdi - yaxshi. Lekin mavjud bo'sh note (id!=0) ham saqlanmasdi - yomon: user explicitly tozalagan bo'lsa, push sinxronlashi kerak.

**Fayllar:**
- `ui/edit/NoteEditViewModel.kt` — `performSaveLocked`: `isCompletelyEmpty && current.id == 0L` -> skip; boshqa hollarda saqlash.

**Tekshirish:** compile OK.

### B12 — bumpDirtyMeta + exportSchema ✅ (qisman)

**Muammo:** `bumpDirty` har bir mutatsiyada `updatedAt` ni yangilardi - favorite/pin o'zgartirish notelar ro'yxatini sakratadi.

**Fayllar:**
- `data/repository/NoteRepository.kt` — yangi `bumpDirtyMeta` (dirtyRevision ni oshiradi, `updatedAt` ga tegmaydi); `toggleFavorite`, `togglePin`, `moveToTrash`, `restoreFromTrash` endi `bumpDirtyMeta` ishlatadi
- `data/local/NoteDatabase.kt` — `exportSchema = false` -> `exportSchema = true`
- `app/build.gradle.kts` — `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`

**Tekshirish:** compile OK; schema JSON endi `app/schemas/` da.

**Qilinmagan:** migration test (9->10->11) - bu F bosqichida.

---

## C bosqichi — Android: xavfsizlik

### C2 — imageClient token faqat BASE_URL hostiga ✅

**Muammo:** `imageClient` interceptor har bir so'rovga `Authorization` qo'shardi - notadagi begona hostdan kelgan URL'ga token tarqalishi mumkin.

**Fayllar:**
- `data/remote/ApiClient.kt` — yangi `isBaseUrlHost(url: HttpUrl)` yordamchisi (URI parse, string prefix emas); interceptor faqat `isBaseUrlHost` true bo'lsa `Authorization` qo'shadi; `followRedirects(false)` - redirect'da ham qo'lda boshqarish; cross-host so'rovlarda debug log

**Tekshirish:** compile OK.

### C1 — backup_rules (allaqachon bajarilgan) ✅

`res/xml/backup_rules.xml` va `res/xml/data_extraction_rules.xml` allaqachon `auth_prefs`, `auth_prefs_secure`, `sync_prefs` ni exclude qiladi (Android 12+ ikkala format uchun). `.gitignore` da `local.properties`, `keystore.properties`, `*.keystore` bor.

### C5 — release signing (allaqachon bajarilgan) ✅

`app/build.gradle.kts` da `signingConfigs.release` (keystore.properties dan), `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-rules.pro` da kotlinx.serialization/Room/OkHttp/Play Services qoidalari. `gradle.properties` da `org.gradle.java.home` yo'q.

### C3 — Credential Manager ⏸️ Keyingi sprint

BeginSignInRequest ishlaydi. Keng ko'lamli Google Sign-In o'zgarishi keyingi iteratsiyaga.

### C4 — Logger masking (allaqachon qisman bajarilgan) ✅

`maskSensitiveFields()` `token`, `access_token`, `id_token`, `password`, `Authorization`, `Bearer`, `secret` ni maskalaydi. 200 belgi preview limit.

**Qo'shimcha (2026-10-16):** Release build'da `Logger.w` endi body chiqarmaydi:
- `data/remote/ApiClient.kt` — yangi `logHttpFailure(method, path, code, maskedBody)` yordamchisi: debug'da body + status, release'da faqat status
- 4 ta `Logger.w` chaqirig'i (execute, executeUnit, executeDeleteOutcome, executeWithIdempotencyKey) endi `logHttpFailure` orqali yoki release/debug branch bilan
- Parse xatosi (Failed to parse $text) ham release'da body chiqarmaydi

**Tekshirish:** compile OK; 27/27 test PASS.

---

## D bosqichi — Rasm pipeline

### D1 — uploadImage Idempotency-Key ✅ (qisman)

**Fayllar:**
- `data/remote/ApiClient.kt` — har bir `uploadImage` chaqirig'i yangi UUID yaratadi va `Idempotency-Key` header qo'shadi; yangi `executeWithIdempotencyKey` va `buildRequestWithIdempotencyKey` yordamchilari

**Tekshirish:** compile OK.

**Qilinmagan:** server side idempotency check (A8 ni kengaytirish) + WorkManager ga ko'chirish (keyingi sprint).

### D2 — Coil 3 ⏸️ Keyingi sprint

`LoadedImage.kt` OkHttp + custom renderer ishlaydi. Coil 3 ga o'tish keng ko'lamli, keyingi iteratsiyaga.

### D3 — `img_*` lokal fayl cleanup ✅

**Muammo:** Note ichidagi `file://<filesDir>/img_xxx` lokal nusxalar hech qachon o'chirilmasdi - disk to'lib borardi. Bundan tashqari, eski `img_${currentTimeMillis}` nomi bir millisekundda 2 ta rasm qo'shilsa collide qilardi.

**Fayllar:**
- `ui/edit/NoteEditViewModel.kt` — `img_${UUID.randomUUID()}.$ext` (to'qnashuvsiz)
- `util/LocalImageCleanup.kt` (yangi) — kuniga bir marta (24h gate) `filesDir/img_*` larni skaner qiladi, hech qaysi note ko'rsatmaydigan fayllarni o'chiradi
- `util/LastCleanupStore.kt` (yangi) — `notes_cleanup` SharedPreferences da oxirgi cleanup vaqti
- `data/local/NoteDao.kt` — yangi `getAllLiveNotesSync()` (cross-user, faqat live notes)
- `NotesApplication.kt` — `onCreate` da `LocalImageCleanup.runIfStale()` ni IO dispatcher da chaqiradi
- 3 ta test fayl `FakeNoteDao`/`StubDao` yangilandi

**Tekshirish:** compile OK; 27/27 test PASS.

---

## E bosqichi — Tozalash

### E1 — jsonObjectBody allaqachon kotlinx.serialization ishlatadi ✅

`ApiClient.jsonObjectBody` `buildJsonObject` + `Json.encodeToString` ishlatadi (V4.4 dan beri). Qo'shimcha o'zgartirish kerak emas.

### E2 — MarkdownExporter (allaqachon to'g'ri) ✅

- Ranglar: `colorHex(colorIndex)` -> `StyleRanges.colorFor()` (CSS hex, indeks emas) ✅
- Birlashtirish: style tuple bir xil bo'lsa bitta `<tag>...</tag>` ichida ✅
- Escape: `\\`, `` ` ``, `*`, `_`, `{`, `}`, `[`, `]`, `(`, `)`, `#`, `+`, `-`, `.`, `!`, `|` ✅

---

## F bosqichi — Testlar

Mavjud testlar:
- `NotesSyncRepositoryTest` — 3/3 PASS (migrateAnonymousNotesToUser, byServerId update, getDirtyNotes filter)
- `NotesSyncRepositorySyncTest` — 7/7 PASS (B1 tombstone purge x2, B3 stable UUID, B6 per-user cursors)
- `PushDeferLocalImageTest` — 6/6 PASS (local file://, R2 URL, text-only, prefix)
- `ApiClientEnvelopeTest` — 4/4 PASS
- `ApiClientUploadTest` — 7/7 PASS (PNG, JPEG, WebP, GIF, HEIC MIME/extension, 4xx error message, ImageSniffer)

**Jami:** 27/27 test PASS (oldingi 25 + HEIC/WebP tuzatildi).

**Qilinmagan:** B5 3-way merge specific test, B10 empty-note test, C2 token host test, migration 9->10->11 test.

---

## Lint holati

- **Xatolar:** 0 (avval 2 ta StateFlow.value xatosi NotesListScreen.kt:417, 453 tuzatildi)
- **Ogohlantirishlar:** 24 (avval 24) - asosan GradleDependency (yangi versiyalar mavjud), ExifInterface, ConfigurationScreenWidthHeight, RedundantLabel, SelectedPhotoAccess. Release build ga ta'sir qilmaydi.

---

## Build natijalari

| Step | Holat |
|---|---|
| `./gradlew :app:compileDebugKotlin` | ✅ SUCCESS |
| `./gradlew :app:compileDebugUnitTestKotlin` | ✅ SUCCESS |
| `./gradlew :app:assembleDebug` | ✅ SUCCESS (APK yaratildi) |
| `./gradlew :app:testDebugUnitTest` | 27/27 PASS |
| `./gradlew :app:lintDebug` | ✅ 0 xato, 25 ogohlantirish |
| `cd notes-backend && npx tsc --noEmit` | ✅ 0 xato |
| `cd notes-backend && npx vitest run` | 14/14 PASS (oldingi sessiyadan) |

---

## Qilinmagan ishlar (keyingi sprintlar)

| Band | Sabab |
|---|---|
| C3 Credential Manager | Keng ko'lamli, ishlaydigan holat bor |
| C4 release build Logger body | Past prioritet |
| D1 WorkManager for image upload | Keng ko'lamli, joriy 5-debounce ishlaydi |
| D2 Coil 3 | Keng ko'lamli, custom loader ishlaydi |
| D3 img_* cleanup | Past prioritet |
| F migratsiya testi (9->10->11) | Alohida test fayli kerak |
| F B5 specific test | B5 o'zgartirilgan, lekin test yozilmagan |
| E kutubxona yangilashlari | Risk-yuk, alohida sprint |
| B7 anonim notalar confirm dialog | Hozir auto-migrate; dialog UX ixtiyoriy |
| B11 addImageUri DB-level update | AddImageUri da file:// -> https:// rewrite to'g'ri ishlaydi (DB orqali) |

---

## Reja hujjati

`PLAN.md` da B..F ning to'liq roadmap, prioritet, vaqt taxminlari.
