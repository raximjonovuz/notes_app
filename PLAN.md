# PLAN — Notes App Audit Fix Roadmap (B..F)

> **Boshlanish sanasi:** 2026-10-16
> **Manba:** `fix-prompt.md` (50+ band) + `CHANGES.md` (Stage A tugagan)
> **Usul:** Ketma-ket, prioritet bo'yicha. Har band yakunida: code change + compile + test.

## Hozirgi holat (audit natijalari)

| Bosqich | Holat | Izoh |
|---|---|---|
| Stage A (Backend) | ✅ tugagan | 14/14 test PASS, tsc 0 xato. `CHANGES.md` da to'liq. |
| Lint (NotesListScreen) | ✅ tuzatildi | `viewModel.sort.value` → `sort by collectAsStateWithLifecycle()` |
| Stage B (Data/Sync) | 🟡 qisman | B1, B3, B6 bajarilgan; B2, B4, B5, B7, B8, B9, B10, B11, B12 qilinmagan |
| Stage C (Security) | ❌ boshlanmagan | |
| Stage D (Image) | ❌ boshlanmagan | |
| Stage E (Cleanup) | ❌ boshlanmagan | |
| Stage F (Tests) | ❌ boshlanmagan | |

## Strategiya

1. **Eng yuqori xavfli bandlar** birinchi — data yo'qotish/xavfsizlik:
   - B2 (sync tartibi), B5 (3-way merge), B10 (bo'sh note), C2 (token host), C5 (release signing)
2. **Keyin o'rta xavfli:** B4, B9, B8, B11, B12, C1, D2
3. **Eng oxirida:** E (cleanup), F (testlar)
4. Har bir bandda:
   - Avval tegishli fayl(lar)ni o'qish
   - Kichik, fokuslangan o'zgartirish
   - `./gradlew :app:compileDebugKotlin` (tezkor feedback)
5. **Buferli tiklash:** har 3-4 banddan keyin to'liq build + lint

## B bosqichi (Android data + sync) — 9 ta qolgan band

| # | Band | Tavsif | Taxminiy vaqt |
|---|---|---|---|
| 1 | **B2** | `syncIfOnline` da **pull oldin, push keyin**; 401 → `SyncStatus.error` + `AuthStore.clearActive`; tarmoq xatosida `Result.retry()` | 30 min |
| 2 | **B4** | `markSynced` da `baseTitle`/`baseContent` ni uzatgan snapshot dan (joriy emas); `mergeServerNote` ni `@Transaction` ichida va partial UPDATE | 45 min |
| 3 | **B5** | `lineUnion` ni LCS-ga asoslangan 3-way diff ga almashtirish; `LinkedHashSet` olib tashlash; idempotent `conflictOriginalId` | 60 min |
| 4 | **B10** | `NoteEditViewModel.performSaveLocked` da mavjud (`id != 0`) bo'sh note saqlansin, faqat yangi bo'sh o'chirilsin | 15 min |
| 5 | **B7** | Anonim notelar (userId=0) ni birinchi login vaqtida confirm dialog bilan; keyin userId=0 ga yozishni bloklash (ixtiyoriy, kichik) | 30 min |
| 6 | **B8** | `ApiClient.uploadImage` da `Idempotency-Key` header; cheksiz retry ni 5 martalik limit bilan (WorkManager ga o'tkazish keyingi sprint) | 30 min |
| 7 | **B11** | `NoteEditViewModel.addImageUri` — DB darajasida `file://` → `https://` rewrite, eski VM write-back oldini olish | 45 min |
| 8 | **B12** | `bumpDirty` `updatedAt` ni faqat content editda yangilasin (favorite/pin uchun emas); `exportSchema = true`; `schemas/` papkasi; migration 9→10→11 test | 45 min |
| 9 | **B9** | `ContentBlock` JSON format v2 + legacy parser; `Note.content` ni bitta formatga o'tkazish (eski `|||IMG:` parser to'g'irlanadi, yangi tahrirlar v2 da saqlanadi) | 90 min |

## C bosqichi (Android xavfsizlik) — 5 ta band

| # | Band | Tavsif | Vaqt |
|---|---|---|---|
| 1 | **C2** | `ApiClient.imageClient` interceptor — token faqat `BASE_URL` hostiga; cross-host so'rovlarda `Authorization` yo'q; test MockWebServer | 30 min |
| 2 | **C5** | `app/build.gradle.kts` — release `signingConfig` (keystore.properties), `isMinifyEnabled=true`, proguard-rules (kotlinx.serialization, Room, OkHttp), `gradle.properties` tozalash | 45 min |
| 3 | **C1** | `AuthStore` — `EncryptedSharedPreferences` ga ko'chirish; `backup_rules.xml` da exclude; logout tokenni o'chirsin (saqlangan akkauntlar faqat email) | 60 min |
| 4 | **C4** | `Logger.w` body 200 belgi; release build'da tanasiz; `token`/`Authorization` maskalash (allaqachon `maskSensitiveFields` bor, tekshirish) | 20 min |
| 5 | **C3** | Credential Manager ga o'tish (keng ko'lamli, **keyingi sprintga qoldiriladi**); hozir shu yerda SKIP belgisi | 0 |

## D bosqichi (Rasm pipeline) — 3 ta band

| # | Band | Tavsif | Vaqt |
|---|---|---|---|
| 1 | **D1** | PNG transparency → oq fon (allaqachon bor, tasdiqlash); uploadIdempotency (B8 da qilindi) | 15 min |
| 2 | **D2** | `LoadedImage` ni Coil 3 ga almashtirish — `coil-compose` + `coil-network-okhttp` + `imageClient` fetcher | 60 min |
| 3 | **D3** | `img_*` lokal fayllarni tozalash (kuniga bir) + UUID asoslasa nomlash | 30 min |

## E bosqichi (Tozalash) — kichik ishlar

| # | Band | Vaqt |
|---|---|---|
| 1 | `jsonObjectBody` allaqachon `buildJsonObject` ishlatadi (E1 — DONE) | 0 |
| 2 | `MarkdownExporter` CSS rang tuzatish; escape; birlashtirish | 30 min |
| 3 | Unused code: `note_sync_state`, `getDirtyNotesBounded`, `bumpDirty` (E) | 15 min |
| 4 | Library upgrade (yangi barqaror versiyalar) — **opsional**, hozir skip | 0 |
| 5 | `BackupExporter` restore funksiyasi yoki "faqat eksport" belgisi | 30 min |

## F bosqichi (Testlar) — minimal to'plam

Testlar asosiy xatti-harakatni qamrab olishi kerak (to'liq F juda katta). Minimal:

| # | Test | Maqsad |
|---|---|---|
| 1 | B1: sinxronlangan note o'chirish serverga yetadi + lokal purge | real Room + MockWebServer |
| 2 | B3: parallel push bitta note uchun bitta qator yaratadi | UUID barqaror |
| 3 | B5: turli qator qo'shish auto-merge, bir xil qator o'zgartirish conflict | 3-way merge |
| 4 | B6: 2 akkaunt kursori aralashmaydi | SyncStore namespacing |
| 5 | B10: mavjud bo'sh note saqlanadi | performSaveLocked |
| 6 | C2: token boshqa hostga ketmaydi | MockWebServer |
| 7 | Migration 9→10→11 ma'lumot saqlanishi | MigrationTestHelper |

**Vaqt taxminiy:** 90-120 daqiqa.

## Umumiy vaqt taxminiy

| Bosqich | Vaqt |
|---|---|
| B (9 ta) | 4.5-6 soat |
| C (4 ta) | 2.5-3 soat |
| D (3 ta) | 1.5-2 soat |
| E (3-4 ta) | 1-1.5 soat |
| F (7 ta test) | 1.5-2 soat |
| CHANGES.md + yakuniy build | 30 min |
| **Jami** | **11-15 soat** |

## Nima SKIP qilinadi (sababi bilan)

- **C3 (Credential Manager)** — keng ko'lamli Google Sign-In o'zgarishi, mavjud BeginSignInRequest ishlaydi
- **A8 `/files/<key>` ning to'liq Cache-Control refactor** — A allaqachon bajarilgan
- **BackupExporter restore** — "faqat eksport" deb belgilash osonroq
- **Library yangilashlari** — risk-yuk, alohida sprint
- **JWKS ga o'tish (A7)** — allaqachon tokeninfo yetarli deb qaror qilingan
- **`/auth/refresh`** — 7 kunlik TTL yetarli
- **Migratsiyalardan BOM olib tashlash** — A da bajarilgan

## Har bir band uchun minimal checklist

```
1. Tegishli fayl(lar)ni o'qish (Read tool)
2. O'zgartirish (Edit tool)
3. Tezkor compile: ./gradlew :app:compileDebugKotlin --offline
4. Xato bo'lsa, tuzatish
5. Bandni "completed" ga o'tkazish (todo)
6. CHANGES.md ga yozish (har 2-3 bandda)
```

## Resurslar

- Build: JDK 21 (Temurin), Android SDK 36
- Compile check: `cd notes && $env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" && .\gradlew.bat :app:compileDebugKotlin --offline`
- Full lint: `.\gradlew.bat :app:lintDebug --offline`
- Tests: `.\gradlew.bat :app:testDebugUnitTest --offline`
- Backend: `cd notes-backend && npx tsc --noEmit && npx vitest run`

## Progress tracking

- `todo` ro'yxati har bir bandda yangilanadi
- `CHANGES.md` oxirida B..F natijalari yoziladi
- Oxirgi sessiya: README/PROGRESS.md ga qisqa summary
