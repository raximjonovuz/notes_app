# CHANGES — Notes backend audit fixes

Ushbu hujjat `fix-prompt.md` dagi **A bosqich** (backend) bandlarining bajarilishini qayd etadi. Har bir band uchun: nima tuzatildi, qaysi fayllar, qanday tekshirildi, qanday qarorlar qabul qilindi.

Barcha o'zgarishlardan keyin: `npx tsc --noEmit` xato bermaydi, `npx vitest run` 14/14 test o'tadi.

---

## A1 — IDOR (Cross-user note overwrite) — **Bajarildi**

**Muammo:** `INSERT ... ON CONFLICT(id) DO UPDATE` `user_id` ni tekshirmasdi. Boshqa foydalanuvchi UUID'ni bilib, boshqaning note'ini ustiga yozishi mumkin edi. `UPDATE notes SET change_id = ?` da `AND user_id = ?` yo'q edi.

**Fayllar:**
- `src/routes/notes.ts` — `upsertNote` va `deleteNote`

**Tuzatish:**
- `upsertNote` da birinchi SELECT endi `user_id` ni ham qaytaradi. Agar `existing.user_id !== userId` → **404 Not Found** (existence leak yo'q).
- `INSERT ... ON CONFLICT ... DO UPDATE WHERE user_id = excluded.user_id` — TOCTOU race uchun defense in depth.
- `UPDATE notes SET change_id = ? WHERE id = ? AND user_id = ?` — har bir joyda.
- `deleteNote` da `change_id` UPDATE ga `AND user_id = ?` qo'shildi.

**Tekshirish:** `tsc --noEmit` 0; `tests/notes.test.ts` "upsertNote IDOR returns 404" o'tadi.

---

## A2 — Atomiklik — **Bajarildi**

**Muammo:** `upsertNote`/`deleteNote` 3 ta alohida round-trip — notes yozish, change_log INSERT, change_id UPDATE. Oraliqda xato bo'lsa, change_log yozuvi notes row'ga mos kelmay qolardi.

**Fayllar:**
- `src/routes/notes.ts`

**Tuzatish:**
- `env.DB.batch([...])` — 3 statement bitta atomik transaction:
  1. notes yozish (INSERT ... ON CONFLICT yoki UPDATE)
  2. change_log INSERT
  3. `UPDATE notes SET change_id = (SELECT MAX(id) FROM change_log WHERE user_id = ? AND note_id = ?)`
- `appendChangeLog` helper olib tashlandi (endi kerak emas).
- Round-trip: upsert 3→2, delete 4→2.

**Tekshirish:** `tsc --noEmit` 0.

---

## A3 — `base_change_id` conflict detection — **Bajarildi**

**Muammo:** Server har doim last-write-wins. Eski klient yangi kontentni bosib ketardi.

**Fayllar:**
- `src/routes/notes.ts` — `upsertNote`

**Yangi API (ixtiyoriy maydonlar, eski klient buzilmaydi):**
- Body'da ixtiyoriy `base_change_id` (number) va `undelete` (bool).
- Agar yuborilgan va server `change_id > base_change_id` → **409** + `{error: "Conflict", conflict: true, note: <server state>}`.
- `shouldUndelete = undelete || baseChangeId === null` (legacy klientlar `base_change_id` yubormaydi va eski xatti-harakat — tombstone'ni "tiriltiradi").
- `deleted_at = CASE WHEN ? THEN NULL ELSE deleted_at END` — un-delete faqat ruxsat berilgan hollarda.

**Klient shartnomasi:**
- Eski klient (maydonlarni yubormaydi) — LWW, eski tombstone'ni tiriltiradi (legacy moslik).
- Yangi klient + `base_change_id` — server yangilangan bo'lsa 409, 3-way merge.
- Yangi klient + `undelete: true` — explicit un-delete.

**Tekshirish:** `tsc --noEmit` 0; `tests/notes.test.ts` "upsertNote 409 conflict" o'tadi.

---

## A4 — `created_at` xato va migratsiya 0004 — **Bajarildi**

**Muammo:**
- `INSERT notes` da `created_at = clientUpdated` (xato — bu modification time, creation time emas).
- `auth.ts` Google yo'lida `Math.floor(Date.now()/1000)` sekundlarda yozardi, jadval millisekund kutadi.

**Fayllar:**
- `migrations/0004_created_at_corrections.sql` (yangi) — `UPDATE notes SET created_at = client_created_at WHERE client_created_at > 0`; `UPDATE users SET created_at = created_at * 1000 WHERE created_at < 100000000000` (eski sekundli qatorlar).
- `src/routes/notes.ts` — `rowToNote` `created_at` endi `client_created_at > 0` bo'lsa o'shandan oladi; INSERT'da `created_at = clientCreated`; ON CONFLICT UPDATE'ga `created_at = excluded.created_at` qo'shildi.
- `src/routes/auth.ts` Google yo'lida `Date.now()` (ms) ishlatiladi.

**Migration qo'llash:** `npm run db:migrate` (local + remote).

**Tekshirish:** `tsc --noEmit` 0.

---

## A5 — `listNotes` (dublikat, cursor, tombstone, sahifalash) — **Bajarildi**

**Muammo:** `since` JOIN `change_log` da bir note bir nechta marta qaytarardi. Bo'sh javobda `sync_cursor = 0` reset bo'lardi (klient hamma narsani qayta tortardi). Sahifalash yo'q.

**Fayllar:**
- `src/routes/notes.ts` — `listNotes`

**Tuzatish:**
- `WITH per_note AS (SELECT note_id, MAX(id) FROM change_log WHERE user_id = ? AND id > ? GROUP BY note_id)` — CTE, har note 1 marta qaytadi.
- `log_change_id` (max change_log id) — `sync_cursor` shundan olinadi.
- Bo'sh javobda `sync_cursor = since` (0 emas).
- `since` so'rovlarida tombstones qaytadi (klient o'z local nusxasini o'chirishi kerak); `since` siz full sync'da tombstones chiqarilmaydi.
- Sahifalash: `limit` (default 200, max 500), `has_more: true|false`, javobda `limit`.
- `limit+1` so'rab, bitta query bilan `has_more` aniqlanadi.

**Klient shartnomasi:**
- `GET /notes?since=N&limit=200` — `has_more=true` bo'lsa, keyingi page.
- `sync_cursor` ni saqlang, keyingi so'rovda `since` sifatida yuboring.

**Tekshirish:** `tsc --noEmit` 0; `tests/notes.test.ts` "listNotes since tombstone + user_id strip" o'tadi.

---

## A6 — Soft-delete retention + cron — **Bajarildi**

**Muammo:** `change_log` va tombstone qatorlar abadiy o'sib borardi. Eski `since` kursori bilan yangi klient eski ma'lumotni qayta ko'rardi.

**Fayllar:**
- `src/routes/notes.ts` — `deleteNote` da `title = ''`, `content = ''` (PII tozalash); `RETENTION_MS` konstanta; `listNotes` 410 Gone check; `runRetention()` funksiyasi.
- `src/index.ts` — `scheduled` handler qo'shildi.
- `wrangler.toml` — `[triggers] crons = ["0 3 * * *"]` (har kuni 03:00 UTC).

**Funksional:**
- `deleteNote` endi title/content ni tozalaydi (faqat tombstone qoladi).
- 90 kunlik retention: har kuni `change_log` va `deleted_at IS NOT NULL notes` eski qatorlari o'chiriladi.
- 410 Gone: agar `since` 90 kunlik oynadan oldin bo'lsa, `{resync: true}` qaytadi.
- Faqat (yumshoq) o'chirilgan notelar tegiladi; faol note'lar hech qachon.

**Tekshirish:** `tsc --noEmit` 0; scheduled handler deploy'dan keyin ishlaydi.

---

## A7 — Autentifikatsiya xavfsizlik — **Bajarildi**

**Fayllar:**
- `migrations/0005_token_version_and_email_lowercase.sql` (yangi) — `users.token_version` ustuni, email lowercase, `rate_limits` jadvali.
- `src/rate_limit.ts` (yangi) — D1 fixed-window per-minute counter.
- `src/types.ts` — `Env.ALLOW_PASSWORD_AUTH?: string`.
- `src/utils.ts` — `getUserId` endi static `import`; yangi `getClientIp()`.
- `src/auth.ts` — `assertJwtSecret`, `header.alg === "HS256"` tekshiruvi, payload'ga `tv` va `jti`, TTL 30→7 kun, `verifyToken` da DB dan `token_version` solishtirish.
- `src/routes/auth.ts` — email lowercase, 404 gate (`/auth/register`/`/auth/login` faqat `ALLOW_PASSWORD_AUTH=true` bo'lsa), rate-limit, Google `iss`/`email_verified`/`exp` tekshiruvi, yangi `logoutRoute`, `meRoute(userId, env)`.
- `src/index.ts` — tashqi try/catch 500 uchun, `/auth/logout` dispatch, `meRoute(userId, env)`.
- `wrangler.toml` — `ALLOW_PASSWORD_AUTH = "false"`.

**Muhim qarorlar:**
- JWKS skip — `tokeninfo` HTTPS orqali yetarli, izoh qo'shildi.
- Rate limit D1 da (KV binding kerak emas).
- `getUserId` endi static import.
- `/auth/refresh` qilinmadi (7 kunlik TTL yetarli).

**Tekshirish:** `tsc --noEmit` 0; `tests/notes.test.ts` "registerRoute 11-attempt rate limit → 429", "email lowercase" o'tadi.

---

## A8 — Upload va fayl berish xavfsizlik — **Bajarildi**

**Fayllar:**
- `src/routes/upload.ts` — qayta yozildi: magic-number detection, Content-Length pre-check, kvota, `fileHeaders()`, `deleteFile()`, `cleanupOrphanImages()`.
- `src/index.ts` — `DELETE /files/<key>` route, scheduled handler A6+A8 cleanup.

**Funksional:**
- `MAX_REQUEST_BYTES = 11 MB` (Content-Length pre-check) — 413.
- `detectImageType(bytes)` — PNG/JPEG/GIF/WebP/HEIC magic number; SVG rad; 415.
- Per-user kvota: 200 MB / 2000 fayl — bytes→413, count→429.
- `Cache-Control: private, max-age=31536000, immutable`; `X-Content-Type-Options: nosniff`; `Content-Disposition: inline`; `Access-Control-Allow-Origin` yo'q.
- `DELETE /files/<key>` — owner-scoped; R2 + DB o'chirish.
- `cleanupOrphanImages` — `note_id IS NULL` va 7 kundan eski rasmlar (A8 scheduled).

**Muhim qarorlar:**
- 8 MB binary ~ 10.7 MB base64-JSON; 11 MB Content-Length mos.
- Quota race: ikki parallel upload ikkalasi o'tishi mumkin, keyingi 413/429 oladi (D1 batch qiyin).
- `images.note_id` nullable saqlandi (design note kodda).

**Tekshirish:** `tsc --noEmit` 0; `tests/notes.test.ts` "detectImageType SVG + PNG" o'tadi.

---

## A9 — Cache invalidation race (versiyali kalitlar) — **Bajarildi**

**Muammo:** Read-modify-write race: o'qish boshlanib, DB'dan o'qigan, keyin write `cacheDel` qiladi — lekin o'qiruvchi o'z eski javobini yana cache'ga yozadi (stale).

**Fayllar:**
- `src/cache.ts` (qayta yozildi)
- `src/routes/notes.ts` — o'qish/yozish yangilangan

**Tuzatish:**
- `notes:user:<id>:ver` — har yozishda `INCR` (deleteNote, upsertNote, logout).
- Cache kalitlari: `notes:user:<id>:v<ver>:list:full`, `notes:user:<id>:v<ver>:note:<id>`.
- O'qish boshida `currentCacheVersion(env, userId)` — shu `ver` bilan cache o'qiladi.
- Eski kalitlar TTL bilan o'chadi (60-120s), hech qachon ko'rinmaydi.
- `meKey` o'zgarmadi (immutable, logout explicit `cacheDel` qiladi).
- Upstash REST: POST body JSON (URL emas) — katta note matnlari uchun xavfsiz.

**Tekshirish:** `tsc --noEmit` 0.

---

## A10 — Boshqa tuzatishlar — **Bajarildi**

**Fayllar:**
- `src/types.ts` — `Note.user_id` olib tashlandi.
- `src/routes/notes.ts` — `validateNoteBody` (title ≤500, content ≤1MB, bg 0..7, updated_at clamp).
- `package.json` — wrangler 4.151.0, typescript 5.9.3, workers-types 5.x, vitest 2.x, `@cloudflare/vitest-pool-workers`.
- `wrangler.toml` — `compatibility_date = "2025-01-01"`.
- `tests/notes.test.ts` — 14 ta test.
- `vitest.config.ts` — yangi.
- `npm run typecheck` va `npm run test` skriptlari.

**Tekshirish:** `tsc --noEmit` 0; `npx vitest run` **14/14 passed**.

**BOM:** 0 ta BOM topildi (16 ta `.ts`/`.sql` tekshirildi).

**Qarorlar:**
- Test framework: oddiy `vitest` + `MockD1`/`MockR2` shim (Windows uchun `workerd` qiyin).
- `user_id` faqat client-facing javobdan olib tashlandi; DB query'lar va cache key'lar uchun saqlanadi.

**TODO testlar** (keyingi sprintlarda):
- since duplicate assertion
- rate-limit minute rollover (Date.now mock)
- to'liq /upload round-trip

---

## Umumiy natija

| Band | Holat | Tekshirish |
|---|---|---|
| A1 | ✅ bajarildi | test + tsc |
| A2 | ✅ bajarildi | tsc |
| A3 | ✅ bajarildi | test + tsc |
| A4 | ✅ bajarildi | tsc (migration remote) |
| A5 | ✅ bajarildi | test + tsc |
| A6 | ✅ bajarildi | tsc (cron deploy'dan keyin) |
| A7 | ✅ bajarildi | test + tsc |
| A8 | ✅ bajarildi | test + tsc |
| A9 | ✅ bajarildi | tsc |
| A10 | ✅ bajarildi | test + tsc |

**14/14 backend test PASS, tsc 0 xato.**

**Tavsiya (deploy):**
1. `npm run db:migrate` (local + remote) — 0004 + 0005
2. `wrangler secret put ALLOW_PASSWORD_AUTH` = "true" (faqat test uchun)
3. `npm run deploy`
4. Smoke test: register → login → note upsert → /auth/me → listNotes (since bilan va siz)

**B..D..E..F bosqichlar (Android + cleanup) keyingi sessiyalarda.**
