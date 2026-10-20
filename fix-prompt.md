# VAZIFA: Notes loyihasidagi barcha xato va kamchiliklarni tuzatish

Sen tajribali Android (Kotlin/Compose/Room) va Cloudflare Workers (TypeScript, D1, R2) dasturchisisan.
Loyiha: Google Notes klon. Android ilova (`app/`) + backend (`src/`, `migrations/`, `wrangler.toml`).
Quyida kod auditi natijasida topilgan xatolar ro'yxati. Har birini tuzatishing kerak.

## UMUMIY QOIDALAR
1. Avval tegishli fayllarni to'liq o'qi, keyin o'zgartir. Taxmin qilma.
2. Kichik, alohida qadamlar bilan ishla. Har bosqichdan keyin kompilyatsiya (`./gradlew :app:compileDebugKotlin`, backend uchun `npx tsc --noEmit`) va testlarni ishga tushir.
3. Mavjud migratsiya fayllarini (0001, 0002, 0003) O'ZGARTIRMA. Yangi o'zgarishlar uchun `0004_*.sql` yarat. Room bazasi versiyasini 10 dan 11 ga ko'tar va `MIGRATION_10_11` yoz (destructive fallback QO'SHMA).
4. Foydalanuvchi ma'lumotini yo'qotadigan yechim yozma. Shubha bo'lsa, ma'lumotni saqlaydigan variantni tanla.
5. Public API shartnomasi o'zgarsa (yangi maydon), eski klientlar buzilmasligi uchun maydonlar ixtiyoriy (optional) bo'lsin.
6. Har bir tuzatish uchun test yoz (quyida talablar bor). Testlar haqiqiy Room (Robolectric, in-memory) bilan ishlasin, qo'lda yozilgan fake DAO bilan emas.
7. Hech qanday sir/kalit/token kodga yozilmasin va loglanmasin.
8. Oxirida `CHANGES.md` yarat: har bir band uchun nima o'zgargani, qaysi fayllar, qanday tekshirilgani.
9. Ishni quyidagi tartibda bajar (A → F). Har bosqich oxirida qisqa hisobot ber.

---

## A BOSQICH — Backend xavfsizlik va to'g'rilik (src/)

### A1. IDOR: boshqa foydalanuvchi notesini ustiga yozish (src/routes/notes.ts, upsertNote)
- Muammo: `INSERT ... ON CONFLICT(id) DO UPDATE` `user_id` ni tekshirmaydi. Boshqa user shu UUID bilan POST qilsa, ega o'zgarmaydi, lekin `title/content` ustiga yoziladi. Shuningdek `UPDATE notes SET change_id = ? WHERE id = ?` da `user_id` yo'q (deleteNote da ham).
- Tuzat: avval `SELECT user_id FROM notes WHERE id = ?`. Agar qator bor va `user_id` boshqa bo'lsa, 409/404 qaytar (mavjudligini oshkor qilmaslik uchun 404 yaxshiroq). Barcha UPDATE/DELETE so'rovlariga `AND user_id = ?` qo'sh. `ON CONFLICT ... DO UPDATE ... WHERE notes.user_id = excluded.user_id` ishlat.

### A2. Atomiklik (notes.ts)
- `upsertNote` va `deleteNote` dagi ketma-ket yozuvlar (notes yozish, change_log INSERT, notes.change_id UPDATE) alohida so'rovlar. `env.DB.batch([...])` bilan bitta atomik batch qil. change_id ni olish uchun batch ichida `INSERT INTO change_log ... RETURNING id` natijasidan foydalan yoki `notes.change_id` ni subquery bilan yangila: `UPDATE notes SET change_id = (SELECT MAX(id) FROM change_log WHERE note_id = ? AND user_id = ?)`.

### A3. Konflikt aniqlash (server tomonida LWW ni tuzatish)
- Muammo: server har doim ustiga yozadi. Eski klient yangi kontentni bosib ketadi.
- Tuzat: `POST /notes` body ga ixtiyoriy `base_change_id` (klient oxirgi bilgan `change_id`) qo'sh. Agar yuborilgan bo'lsa va serverdagi `change_id` undan katta bo'lsa, ustiga YOZMA, `409` va `{ "conflict": true, "note": <serverdagi hozirgi versiya> }` qaytar. `base_change_id` yuborilmasa (eski klient) eski xatti-harakat qolsin.
- Soft-delete qilingan notega eski klient tahrir yuborsa, avtomatik "tirilish" faqat `base_change_id` yo'q bo'lganda yoki klient aniq `undelete: true` yuborganda bo'lsin.

### A4. created_at xatosi (notes.ts)
- INSERT da `created_at` ga `clientUpdated` bog'lanyapti. `clientCreated` bo'lishi kerak. `rowToNote` `created_at` ni `client_created_at` dan (agar 0 bo'lmasa) qaytarsin. Mavjud noto'g'ri qatorlar uchun `0004` migratsiyada `UPDATE notes SET created_at = client_created_at WHERE client_created_at > 0`.
- `src/routes/auth.ts` Google yo'lida `created_at` sekundda yozilyapti (`createdAtSec`), jadval default'i millisekund. Millisekundga birxillashtir (`Date.now()`), mavjud sekundli qatorlar uchun migratsiyada tuzat (`created_at < 100000000000` bo'lsa `* 1000`).

### A5. listNotes: dublikatlar va kursor (notes.ts)
- `since` so'rovi `change_log` bilan JOIN qilib, bitta note uchun ko'p qator qaytaradi. `GROUP BY n.id` yoki `SELECT DISTINCT` ishlat, har note faqat bir marta chiqsin.
- `sync_cursor` ni `MAX(change_log.id)` (shu user uchun, shu javobga kirgan qatorlar bo'yicha) deb hisobla, tombstone'lar filtrlanishidan OLDIN. Javob bo'sh bo'lsa, klient yuborgan `since` ni qaytar (0 emas).
- Tombstone'lar: `ops=deleted` bo'lmasa ham `since` bilan so'ralganda o'chirilgan notelar `deleted_at` bilan qaytsin (klient o'zining lokal nusxasini o'chirishi uchun). Faqat to'liq (since yo'q) ro'yxatdan tombstone'lar chiqarilmasin. Javobni shunga moslab, README ni yangila.
- Sahifalash qo'sh: `limit` (default 200, max 500) va javobda `has_more`. Klient `has_more` bo'lguncha tortsin.
- Kesh: `since` so'rovlari kesh qilinmaydi (hozirgidek).

### A6. Soft-delete va maxfiylik
- `deleteNote` da `title = ''`, `content = ''` ga tozala (tombstone matnni saqlamasin).
- Retention: `change_log` va tombstone qatorlarni 90 kundan keyin tozalaydigan funksiya yoz (`scheduled` handler + `wrangler.toml` da `[triggers] crons`). Tozalashdan oldin `since` kursori tozalangan oraliqdan eski bo'lsa, server `410 Gone` + `{"resync": true}` qaytarsin va klient to'liq sinxronga o'tsin.

### A7. Autentifikatsiya (src/routes/auth.ts, src/auth.ts, src/utils.ts)
- Email'ni hamma joyda `trim().toLowerCase()` qil (register, login, google). Migratsiyada mavjud emaillarni lowercase qilishdan oldin to'qnashuvni tekshir.
- Ilova faqat Google bilan kiradi. `POST /auth/register` va `/auth/login` ni `env.ALLOW_PASSWORD_AUTH === "true"` bo'lgandagina yoqilsin (default o'chiq, 404 qaytarsin). Bu hisob "pre-hijack" ni yopadi.
- Google: `info.email_verified` `"true"` satri bo'lishi mumkin, qat'iy tekshir (`=== true || === "true"`). `iss` (`accounts.google.com` yoki `https://accounts.google.com`) va `exp` ni tekshir. Imkon bo'lsa tokeninfo o'rniga JWKS (`https://www.googleapis.com/oauth2/v3/certs`) orqali imzo tekshirishga o't.
- Login'da mavjud bo'lmagan email uchun ham soxta PBKDF2 hisobla (vaqt orqali enumeratsiya yopilsin).
- Rate limit: login/register/google uchun IP bo'yicha (Cloudflare Rate Limiting binding yoki D1/KV hisoblagich) daqiqada 10 urinish.
- JWT: `JWT_SECRET` yo'q yoki 32 belgidan qisqa bo'lsa, 500 qaytar va logla (sirni emas). `verifyToken` sarlavhadagi `alg === "HS256"` ni tekshirsin. Tokenga `jti` va foydalanuvchida `token_version` (yangi ustun, migratsiya 0004) qo'sh: `verifyToken` bazadan `token_version` ni solishtirsin (bekor qilish imkoni). `POST /auth/logout` token_version ni oshirsin. TTL 30 kundan 7 kunga tushirilsin va `POST /auth/refresh` qo'shilsin (ixtiyoriy, murakkab bo'lsa qoldir va CHANGES.md ga yoz).
- `getUserId` dagi keraksiz dynamic `import('./auth')` ni oddiy static import qil. `meRoute` ichida tokenni qayta tekshirish takroriy, router bergan `userId` dan foydalansin.

### A8. Upload va fayl berish (src/routes/upload.ts)
- Body hajmini avval `Content-Length` bilan tekshir (11 MB dan katta bo'lsa 413), JSON ni o'qishdan oldin.
- Tur tekshiruvi: klient yuborgan `content_type` ga ishonma. Baytlarning magic number'ini tekshir (PNG, JPEG, GIF, WebP, HEIC/HEIF) va faqat shularni ruxsat et. `image/svg+xml` ni rad et. Saqlanadigan `contentType` aniqlangan turdan olinsin.
- Per-user kvota: foydalanuvchining jami `size_bytes` ≤ 200 MB va fayllar soni ≤ 2000 (images jadvalidan hisobla).
- `/files/<key>` javobi: `Cache-Control: private, max-age=31536000, immutable`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`. `Access-Control-Allow-Origin: *` ni olib tashla (mobil ilovaga kerak emas), `corsHeaders()` ni ham cheklangan qil yoki olib tashla.
- Yangi `DELETE /files/<key>` endpoint: egasi o'chira olsin (R2 va images jadvalidan). Yangi `DELETE /notes/<id>` o'chirilganda note ichidagi shu foydalanuvchining `/files/...` rasmlari ham o'chirilsin (kontentdan `|||IMG:` URL'larini ajratib ol) yoki orphan tozalash cron'i yoz (note_id bog'lanmagan va 7 kundan eski images).
- `images.note_id` ni ishlat yoki ustunni olib tashlash haqida qaror ber va CHANGES.md ga yoz.

### A9. Redis kesh (src/cache.ts)
- Upstash REST ga qiymatni URL yo'liga emas, POST body ga JSON massiv sifatida yubor: `fetch(URL, {method:'POST', headers:{Authorization}, body: JSON.stringify(['SET', key, value, 'EX', ttl])})`. Shunda katta ro'yxat va note matnlari URL'larga tushmaydi.
- Invalidatsiya poygasi: yozishdan keyin `cacheDel` qilinsin (hozirgidek), lekin o'qish yo'lida kesh yozish faqat o'qish boshlanishidan keyin yozuv bo'lmagan bo'lsa amalga oshsin (versiya hisoblagich kalit: `notes:user:<id>:ver`, kesh kalitiga versiya qo'shilsin).
- `meRoute` kesh qiymati `created_at` bilan mos kelsin.

### A10. Boshqa
- `Note` tipidan javobda `user_id` ni olib tashla (klientga keraksiz).
- `title` ≤ 500 belgi, `content` ≤ 1 MB, `background_color` 0..7 oralig'ida, `updated_at` kelajakdagi (> hozir + 24 soat) bo'lsa hozirgi vaqtga tenglashtirilsin. Aks holda 400.
- Barcha `.ts`, `.sql` fayllardan UTF-8 BOM ni olib tashla (migratsiyalardan BOM olib tashlash xavfsiz, lekin D1 allaqachon qo'llagan migratsiya fayllarining mazmuni o'zgarmasin: faqat birinchi 3 baytni olib tashla; shubha bo'lsa 0001-0003 ga tegma).
- `package.json`: wrangler va TypeScript yangi barqaror versiyaga ko'tarilsin, `compatibility_date` yangilansin, `npm run typecheck` skripti qo'shilsin.
- Backend testlari: `vitest` + `@cloudflare/vitest-pool-workers` (yoki miniflare) bilan quyidagilarni yoz: (1) boshqa user note UUID'siga POST qilsa rad etiladi; (2) base_change_id eskirgan bo'lsa 409; (3) since bilan dublikat qaytmaydi; (4) tombstone since bilan qaytadi; (5) delete content'ni tozalaydi; (6) email lowercase; (7) rate limit; (8) SVG upload rad etiladi.

---

## B BOSQICH — Android: ma'lumotlar qatlami va sinxronizatsiya

### B1. O'chirish sinxronizatsiyasi (NoteDao.getDirtyNotes, NotesSyncRepository)
- Muammo: `getDirtyNotes` dagi `(deletedAt IS NULL OR serverChangeId = 0)` sharti sinxronlangan notelarning tombstone'larini push ro'yxatidan chiqaradi, `purgeAcknowledgedDeletes` ham xuddi shu so'rovdan foydalanadi (shuning uchun doim bo'sh). O'chirish serverga yetmaydi va lokal qator abadiy qoladi.
- Tuzat: `getDirtyNotes` shartidan `deletedAt` qismini olib tashla (`dirtyRevision > lastSyncedRevision` yetarli). Tombstone muvaffaqiyatli `DELETE` dan keyin (yoki server 404 bersa) darhol `purgeNoteById` qilinsin, pull'ni kutmasdan. DELETE muvaffaqiyatsiz bo'lsa, qator saqlansin va keyingi sinxronda qayta uriniladi. `purgeAcknowledgedDeletes` ni alohida `getTombstonesAcknowledged()` so'rovi bilan almashtir yoki olib tashla. Server tombstone yuborganda (pull) lokal qatorni darhol purge qil (`softDeleteNote` o'rniga).
- `ApiClient.listNotes` yangi server shartnomasiga moslansin (A5): since bilan tombstone'lar keladi.

### B2. Tartib: pull → merge → push (NotesSyncRepository.syncIfOnline)
- Hozir push keyin pull. Teskari qil: avval pull (merge), keyin push. Pushda har note uchun `base_change_id = note.serverChangeId` yubor (A3). Server 409 qaytarsa, javobdagi server versiyasi bilan `tryThreeWayMerge` ni ishga tushir va natijani qayta push qil (maksimum 3 urinish), muvaffaqiyatsiz bo'lsa conflict nusxasi yarat.
- `syncIfOnline` natijasi push va pull ikkalasining muvaffaqiyatini hisobga olsin. Hozir push xatosi yashirinadi.
- 401 qaytsa: sessiya muddati tugagan deb belgila, foydalanuvchiga "qayta kiring" holatini ko'rsat (`SyncStatus.error`), `AuthStore.clearActive`.
- Eksponensial backoff: tarmoq xatosida `BackgroundSyncWorker` `Result.retry()` qaytarsin (hozir xatolar yutiladi va success qaytadi). Davriy sinxron kuniga bir marta emas, 6 soatda bir marta bo'lsin.

### B3. Yagona NotesSyncRepository va UUID (NotesApplication, NotesListViewModel.Factory, NoteEditViewModel)
- `NotesListViewModel.Factory` yangi `NotesSyncRepository` yaratadi. `app.syncRepository` ni ishlat. Hamma joyda bitta nusxa. `pushLocalChangesInternal` ni har doim `pushMutex` ichida chaqir (`syncIfOnline` ham mutex olsin). Pull ham xuddi shu mutex (yoki alohida `syncMutex`) bilan himoyalansin, push va pull bir vaqtda ishlamasin.
- `NoteEntity.serverId` ni note yaratilganda lokal UUID sifatida yoz (`UUID.randomUUID()`), push paytida emas. `id = note.serverId` doim mavjud bo'ladi. `serverId` ustuni uchun UNIQUE indeks qo'sh (Room `@Index(value=["serverId"], unique=true)`), migratsiyada avval dublikatlarni birlashtir (bir xil serverId li qatorlardan eng yangisini qoldir).
- Eski qatorlar uchun `MIGRATION_10_11` serverId NULL bo'lgan qatorlarga UUID bersin (SQLite'da `lower(hex(randomblob(16)))` dan UUID formatida yasash).

### B4. Push paytida eski snapshot yozish (pushLocalChangesInternal)
- `noteDao.updateNote(note.copy(serverId = response.id))` ni olib tashla (B3 bilan keraksiz bo'ladi). Hozirgi `markSynced` yagona atomik UPDATE bo'lib qolsin va faqat `WHERE id = :localId AND dirtyRevision = :syncedRev` bilan emas, `lastSyncedRevision` ni ham to'g'ri qo'ysin: foydalanuvchi push paytida tahrir qilgan bo'lsa (dirtyRevision oshgan), `dirtyRevision` ni QAYTA YOZMA, faqat `lastSyncedRevision`, `serverChangeId`, `serverUpdatedAt` va base ustunlarini push qilingan snapshot qiymatlari bilan yangila (`baseTitle/baseContent` ni DB dagi `title/content` dan emas, yuborilgan qiymatlardan ol).
- Pull `mergeServerNote` dagi read-modify-write ni `@Transaction` ichiga ol va faqat o'zgargan maydonlarni `@Query UPDATE` bilan yoz, butun qatorni `updateNote(local.copy(...))` bilan emas, aks holda editor yozgan matn yo'qoladi.

### B5. Merge algoritmi (tryThreeWayMerge, lineUnion)
- `lineUnion` ni to'g'ri 3-way diff bilan almashtir: base, local, server qatorlari bo'yicha LCS diff, ikkala tomonning qo'shilgan/o'chirilgan qatorlarini birlashtir. Takroriy va bo'sh qatorlar saqlansin (`LinkedHashSet` ishlatilmasin). Bir xil joyda ikki xil o'zgarish bo'lsa conflict.
- Hard conflict: lokal nusxa YANGI mahalliy UUID oladi (`serverId` boshqa), `isConflict = true`. Server nusxasi asl `serverId` ni saqlaydi. Conflict nusxa push qilinsa, yangi note sifatida ketadi va serverdagi asl nusxani bosmaydi. Har pullda yangi dublikat yaratilmasin (idempotent: `conflictOriginalId` + server `change_id` bo'yicha tekshir).
- Testlar: ikki tomon turli qator qo'shsa, avtomatik birlashadi; bir xil qatorni o'zgartirsa conflict; qator o'chirilsa qayta paydo bo'lmaydi; bo'sh qatorlar saqlanadi.

### B6. Kursor foydalanuvchiga bog'langan bo'lsin (SyncStore)
- `last_sync_cursor` va `pending_flush_version` kalitlarini `userId` bilan namespace qil (`last_sync_cursor_<userId>`). Akkaunt almashtirilganda boshqa akkauntning kursori ishlatilmasin. Server `410 resync` qaytarsa, kursorni o'chirib to'liq sinx qil.
- `activeUserId` getter har chaqiruvda SharedPreferences JSON'ni parse qiladi (pull'da har note uchun). Sinxron boshida bir marta o'qib, `val userId` sifatida uzat.
- Sinxron paytida akkaunt almashsa (userId o'zgarsa), joriy sinxron to'xtatilsin.

### B7. Anonim notelar (NoteRepository.refreshActiveUser, migrateAnonymousNotesToUser)
- Chiqib ketgandan keyin yaratilgan `userId = 0` notelar keyingi kirgan ixtiyoriy akkauntga o'tib ketadi. Foydalanuvchidan tasdiq so'ra ("Mahalliy notelarni shu akkauntga qo'shamizmi?") yoki faqat birinchi kirishdagina ko'chir, keyin `userId=0` ga yozishni bloklab, login talab qil.

### B8. Rasm bilan note push (pushLocalChangesInternal)
- Rasm yuklanmaguncha butun note push qilinmaydi va har 5 soniyada cheksiz qayta uriniladi. O'zgartir: matnni push qil, `file://` rasm bloklarini kontentdan vaqtincha olib tashlab (yoki `pending-image:<localId>` placeholder bilan) yubor. Yuklash tugagach kontentni yangila. Yoki rasm yuklashni WorkManager'ga (`OneTimeWork`, `NetworkType.CONNECTED`, exponential backoff, max 5 urinish) ko'chir, cheksiz tsikl bo'lmasin.
- Doimiy xato (413, 400, fayl yo'q) bo'lsa, urinishlarni to'xtat va foydalanuvchiga xabar ber.

### B9. Content serializatsiya (ContentBlock.kt: serializeTextBlock, parseBlocksFromContent)
- Muammo: ko'p qatorli `TextBlock` qayta ochilganda bo'linadi (sarlavhasiz qatorlar yangi default blok bo'ladi); `listTypesByLine` va `fontSizesByLine` saqlanmaydi; `styleRanges` offsetlari buziladi.
- Tuzat: kontent formatini JSON ga o'tkaz: `{"v":2,"blocks":[{"t":"text","text":"...","fontSize":1,"align":0,"list":0,"check":false,"ranges":[...],"fontSizesByLine":{},"listTypesByLine":{}},{"t":"img","uri":"..."}]}`. `kotlinx.serialization` ishlat. Parser: agar kontent `{"v":2` bilan boshlansa, yangi format, aks holda eski formatni (V2.5/V2.6, `|||IMG:`) o'qiydigan legacy parser (uni ham ko'p qatorli matnni to'g'ri o'qiydigan qilib tuzat: sarlavhadan keyingi sarlavhasiz qatorlar o'sha blokning davomi bo'lsin). Saqlash doim yangi formatda. Server `content` maydoni matn bo'lib qolaveradi, o'zgarish shart emas.
- Eski klientlar yangi formatni o'qiy olmasligini hisobga ol: `Note.content` boshida v2 belgisi bo'lsin va eski parser uni oddiy matn sifatida ko'rsatmasligi uchun CHANGES.md da migratsiya ogohlantirishini yoz.
- Testlar: ko'p qatorli blok, bo'sh qatorlar, uslub diapazonlari, qator darajasidagi ro'yxat va o'lcham, rasm bloklari, matnda `<`, `>` va `|||IMG:` bo'lganda round-trip (serializatsiya → parse → bir xil).

### B10. Bo'sh note saqlanmaydi (NoteEditViewModel.performSaveLocked)
- Mavjud note (`current.id != 0L`) uchun sarlavha va matn bo'sh bo'lsa ham saqlansin (foydalanuvchi hamma narsani o'chirgan bo'lishi mumkin). Faqat yangi (id == 0) bo'sh note saqlanmasin. Chiqishda mavjud note butunlay bo'sh bo'lsa, "bo'sh notelarni avtomatik o'chirish" ni qaror qil va CHANGES.md ga yoz.

### B11. Eskirgan ViewModel poygasi (addImageUri)
- Yuklash tugaganda eski (tozalangan) `NoteEditViewModel` o'z eski holatini DB ga yozmasin. Rasm URL'ini almashtirishni DB darajasida bajar: DB dagi joriy kontentni o'qi, `file://...` URI ni `https://...` ga almashtir (matn almashtirish emas, blok parse orqali), `updateNote` ni faqat o'zgargan bo'lsa chaqir. Editor ochiq bo'lsa, state'ni DB dan yangila.

### B12. Boshqa data qatlami
- `bumpDirty` favorite/pin o'zgartirilganda `updatedAt` ni yangilamasin (ro'yxat tartibi sakramasin): `updatedAt` faqat kontent o'zgarganda yangilansin, alohida `metaUpdatedAt` kerak bo'lsa qo'sh.
- `isPinned` ni sinxronla: `NoteSyncDto`, server DTO, `notes.is_pinned` ustuni (migratsiya 0004), `rowToNote`, `upsertNote`.
- `exportSchema = true` qil, schema JSON'larini `app/schemas` ga chiqar (`room.schemaLocation` KSP argumenti), migratsiya testlari (`MigrationTestHelper`) 1→11 uchun yoz.
- `findByContentAndUser` (legacy backfill) ni olib tashla, chunki B3 dan keyin keraksiz va xavfli (bir xil matnli ikki notega noto'g'ri serverId bog'laydi).

---

## C BOSQICH — Android: xavfsizlik

### C1. Token saqlash va backup (AuthStore.kt, AndroidManifest.xml, res/xml/*.xml)
- Tokenlarni `EncryptedSharedPreferences` (androidx.security:security-crypto) yoki Android Keystore bilan shifrlangan DataStore'da saqlа. Mavjud ochiq `auth_prefs` dan bir martalik ko'chirish va eskisini o'chirish.
- `backup_rules.xml` va `data_extraction_rules.xml`: `auth_prefs`, shifrlangan prefs fayli va `sync_prefs` ni cloud-backup va device-transfer dan `<exclude>` qil. Yoki `android:allowBackup="false"`. Rasm fayllari (`filesDir/img_*`) uchun ham qaror ber.
- Logout: serverga `POST /auth/logout` (A7), lokal tokenni o'chir. "Saqlangan sessiyalar" tokenni saqlashda davom etadi. Buni o'zgartir: logout tokenni to'liq o'chirsin, saqlangan akkaunt ro'yxati faqat email ko'rsatsin va qayta kirish Google orqali bo'lsin.

### C2. Token faqat o'z hostiga (ApiClient.imageClient)
- Interceptor `Authorization` ni faqat `request.url.host == BASE_URL host` bo'lganda qo'shsin. Boshqa hostlar uchun token yo'q. Redirect'larda ham tekshir (`followRedirects` o'chirib, qo'lda boshqarish yoki `Authorization` ni cross-host da olib tashlash). Faqat `https` ruxsat etilsin. Test: `MockWebServer` bilan boshqa host so'rovida Authorization sarlavhasi yo'qligini tasdiqla.
- `ApiClient.BASE_URL` yangi note ichidagi rasm URL'larini absolyut saqlamasligi uchun: kontentda nisbiy `r2_key` (`/files/uploads/...`) saqla, ko'rsatishda BASE_URL bilan birlashtir. Eski absolyut URL'lar o'qilganda ham ishlayversin.

### C3. Google Sign-In (LoginScreen.kt, ApiClient.googleLogin, backend)
- `BeginSignInRequest` (eskirgan) o'rniga Credential Manager (`androidx.credentials` + `googleid`) ga o't.
- Nonce: klient tasodifiy nonce yaratsin, Google so'roviga qo'ysin, backend tokenning `nonce` claim'ini tekshirsin (replay himoyasi). Nonce backend'da bir martalik bo'lsin (kesh/D1 da 5 daqiqa).
- Loglarda idToken'ning hech qanday qismi chiqmasin (debug'da ham).

### C4. Logging (Logger.kt, ApiClient.execute)
- `Logger.w` har doim yozadi va `ApiClient` xato javob tanasini (`text`) to'liq logga yozadi (auth javoblarida token bo'lishi mumkin). Tanani 200 belgiga qisqart va `token`, `Authorization` maydonlarini maskala. Release build'da `w` faqat xabarni (tanasiz) yozsin.

### C5. Release build (app/build.gradle.kts)
- `release` `signingConfig = debug` ni olib tashla: `keystore.properties` (gitignore'da) dan o'qiydigan haqiqiy `signingConfigs.release` qo'y, fayl yo'q bo'lsa release assemble xato bersin.
- `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-rules.pro` ga kotlinx.serialization, Room, OkHttp, Play Services uchun kerakli qoidalarni yoz. Release APK'ni ishga tushirib tekshir (R8 sindirmaganini testlar bilan).
- `gradle.properties` dagi `org.gradle.java.home=C:\...` qatorini olib tashla (boshqa mashina va CI da build buziladi). JDK versiyalarini moslashtir (`toolchainVersion=25` va Java 11 mos emas: `jvmToolchain(17)` yoki 21 ni tanla va hamma joyda birxil qil).
- `local.properties` allaqachon `.gitignore` da, tekshir: repoga tushmaganiga ishonch hosil qil.

---

## D BOSQICH — Android: rasm pipeline

### D1. Yuklash (ApiClient.uploadImage)
- Baytlar JPEG ga siqilsa, `content_type` = `image/jpeg` va kengaytma `jpg` bo'lsin (hozir asl format MIME'i yuboriladi). Siqish muvaffaqiyatsiz bo'lib asl baytlar yuborilsa, MIME asl formatniki bo'lsin. Shaffoflik bor PNG/WebP ni PNG sifatida saqla yoki fon rangini oq qilib JPEG qil (qora fon bo'lmasin).
- `readBytes()` ni 16 MB gacha cheklang: `InputStream` dan chegarali o'qi, oshsa xato. `BitmapFactory` uchun avval `inJustDecodeBounds`, keyin `inSampleSize` (hozirgidek), lekin `decodeByteArray` o'rniga fayl/stream asosida (ikki marta to'liq massiv ushlamaslik uchun).
- `retryOnConnectionFailure(true)` upload (POST) da dublikat yaratishi mumkin: yuklashga idempotensiya kaliti (`Idempotency-Key` sarlavhasi = lokal UUID) qo'sh, server (A8) bir xil kalit bilan qayta kelsa mavjud rasmni qaytarsin.

### D2. Ko'rsatish (LoadedImage.kt)
- `rememberLoadedImage` o'rniga Coil 3 (`coil-compose` + `coil-network-okhttp`) ishlat, `ApiClient.imageClient` (C2 qoidalari bilan) ni network fetcher sifatida ber. Disk va xotira keshi yoq. `size` cheklangan bo'lsin (ekran o'lchamiga downsample), shunda 12 MP fayl to'liq dekodlanmaydi.
- Placeholder va xato holati ko'rsatilsin, "qayta urinish" tugmasi.

### D3. Fayllarni tozalash
- Rasm yuklangach va kontent `https` URL bilan yangilangach, `filesDir/img_*` lokal nusxasini o'chir (faqat kontentda boshqa joyda ishlatilmasa). Rasm bloki olib tashlansa, lokal fayl va (online bo'lsa) `DELETE /files/<key>` (A8). Note purge qilinganda uning barcha lokal rasm fayllari o'chirilsin. Ilova ishga tushganda hech bir note ishlatmaydigan `img_*` fayllarini tozalaydigan bir martalik (kuniga bir) tozalash yoz.
- Fayl nomi `img_${currentTimeMillis}` to'qnashishi mumkin: `UUID` ishlat.

---

## E BOSQICH — Kichik tuzatishlar va tozalash

- `ApiClient.jsonObjectBody` / `jsonString` qo'lda yozilgan JSON kodlovchini olib tashla, `kotlinx.serialization` `buildJsonObject` ishlat (surrogat juftliklar va boshqaruv belgilari to'g'ri chiqadi).
- `MarkdownExporter`: `<span style="color:N">` (N indeks, CSS emas) noto'g'ri. Haqiqiy rang kodiga (`StyleRanges.colorFor`) o'tkaz yoki rangni tashlab yubor. Har belgi uchun alohida teg o'rniga ketma-ket bir xil uslubli belgilarni bitta teg ichiga birlashtir. Markdown maxsus belgilarini (`*`, `_`, `#`, `[`, `` ` ``) escape qil. Ishlatilmagan `i` o'zgaruvchilarni olib tashla.
- Ishlatilmaydigan kodni olib tashla: `note_sync_state` jadvali (yangi migratsiyada DROP), `NoteDao.getDirtyNotesBounded`, `NoteDao.bumpDirty`, `AuthStore` dagi eskirgan `save/load/clear`, `AuthRepository.currentSession/logout`, `handleCors`, `utils.ts` dagi ishlatilmaydigan eksportlar, `ExampleUnitTest`, `ExampleInstrumentedTest`.
- Kutubxonalarni yangila (`libs.versions.toml`): activity-compose, lifecycle, navigation-compose, core-ktx, OkHttp, Room, work-runtime Compose BOM 2026.02 bilan mos so'nggi barqaror versiyalarga. Har yangilashdan keyin build va testlarni ishga tushir.
- `BackupExporter`: eksport faylida `serverId`, sync-holat ustunlari bor. Restore funksiyasini yoz (JSON'ni import qilish, dublikatlarsiz) yoki README da "faqat eksport" deb belgila. Fayl hajmi katta bo'lsa OOM bo'lmasligi uchun oqim (stream) bilan yoz.
- README va PROGRESS.md ni yangi shartnomaga moslab yangila (endpoint'lar, `base_change_id`, tombstone qoidalari, kontent formati v2, auth endpointlari holati). `PROGRESS.md` dagi "BUG" bandlariga haqiqiy holatni yoz.
- `DEPLOY_V5_3.md` ga yangi deploy qadamlari (0004 migratsiya, yangi secretlar, cron) qo'sh.

---

## F BOSQICH — Testlar va yakuniy tekshiruv

Majburiy testlar (Robolectric + in-memory Room, `MockWebServer`):
1. B1: sinxronlangan notani o'chirish serverga DELETE yuboradi va lokal qator purge bo'ladi; offline bo'lsa qator saqlanadi va keyin yuboriladi; server tombstone'i pull'da lokal qatorni o'chiradi.
2. B2/A3: ikki qurilma simulyatsiyasi: A tahrir qiladi, B eski base bilan tahrir qiladi, B 409 oladi va merge natijasi ikkala tahrirni saqlaydi.
3. B3: parallel `push` chaqiruvlari bitta note uchun serverda bitta qator yaratadi (UUID barqaror).
4. B4: push paytida yozilgan matn yo'qolmaydi.
5. B6: ikki akkaunt kursori aralashmaydi.
6. B8: yuklanmagan rasmli note matni baribir sinxronlanadi; cheksiz qayta urinish yo'q.
7. B9: serializatsiya round-trip.
8. B10: mavjud notani bo'shatish saqlanadi.
9. C1/C2: token boshqa hostga ketmaydi; backup qoidalari tokenni chiqarib tashlaydi (manifest/XML testi).
10. D1: PNG yuklanganda `content_type` bayt turiga mos.
11. Migratsiya testi 9→10→11 va ma'lumot saqlanishi.
12. Backend testlari (A10 ro'yxati).

Yakuniy tekshiruv ro'yxati:
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` xatosiz.
- `npx tsc --noEmit` va backend testlari xatosiz.
- `grep -rn "Bearer\|id_token\|password" app/src src` natijalarida loglarga tushadigan joy yo'qligini tekshir.
- `CHANGES.md` da har bir band (A1..E) uchun holat: bajarildi / qisman / bajarilmadi (sababi bilan).

Qiyin yoki xavfli (ma'lumotni buzishi mumkin) band bo'lsa, o'zboshimchalik bilan hal qilma: ishni to'xtatib, qaysi variantlar borligini va tavsiyangni yoz, keyin davom et.
