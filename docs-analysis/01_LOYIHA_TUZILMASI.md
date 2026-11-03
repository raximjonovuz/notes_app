# Loyiha tuzilmasi — koddan aniqlangan holat

## Stack

* Android: Kotlin 2.2.10, bitta `:app` moduli, Jetpack Compose + Material 3, Navigation Compose, Room, KSP va Kotlinx Serialization. Versiyalar `notes/gradle/libs.versions.toml:1-119`, Compose yoqilishi va dependencylar `notes/app/build.gradle.kts:77-190` da.
* Lokal ma'lumot: Room, `NoteDatabase` schema v11 (`notes/app/src/main/java/com/example/notes/data/local/NoteDatabase.kt:9-10`), `notes` jadvali `NoteEntity` (`.../NoteEntity.kt:43-86`).
* Tarmoq/sinxronlash: OkHttp, WorkManager (`build.gradle.kts:142-152`), Android klient `ApiClient`/`NotesSyncRepository`.
* Backend: alohida TypeScript Cloudflare Worker, Wrangler 4, D1 SQLite va R2 (`notes-backend/package.json:1-18`, `notes-backend/wrangler.toml:1-14`). Fastify aniqlanmadi.
* Tema: Compose Material 3, light/dark/system va Android 12+ dynamic color (`.../ui/theme/Theme.kt:56-114, 237-287`).

## Muhim paketlar va vazifalar

| Joy | Vazifa |
|---|---|
| `notes/.../data/local` | Room entity, DAO, 1→11 migratsiyalar, auth preference-store |
| `notes/.../data/repository/NoteRepository.kt` | user-scope, local mutation va dirty revision |
| `notes/.../data/remote` | HTTP API, auth va pull/push sinxronlash |
| `notes/.../ui/edit` | blokli rich-text editor, formatlash, checklist, image inline render |
| `notes/.../ui/notes` | ro'yxat/grid, card preview va note actionlar |
| `notes/.../ui/theme` | ColorScheme, note swatch va theme preference |
| `notes/.../work` | fon sinxronlash va eslatma workerlari |
| `notes-backend/src/routes` | auth, note sync/CRUD, upload endpointlari |
| `notes-backend/migrations` | D1 `users`, `notes`, `images`, change-log sxemasi |

## Ma'lumot oqimi

`NoteEditScreen` (`.../NoteEditScreen.kt:221-820`) → `NoteEditViewModel` (`.../NoteEditViewModel.kt:115-1504`) → `NoteRepository` (`.../NoteRepository.kt:20-165`) → `NoteDao` (`.../NoteDao.kt:18-218`) → Room `NoteDatabase` → WorkManager/`NotesSyncRepository` → Worker `/notes` → D1/R2.

UI state `StateFlow<NoteEditState>`dir. Editor `blocks: List<ContentBlock>` saqlaydi (`NoteEditViewModel.kt:77-97`). Persist qilingan `NoteEntity.content` esa bitta `String` (`NoteEntity.kt:60-86`): normal holatda JSON v2 envelope; ichida text/image bloklari, span range, satr font/list xaritalari bor (`ContentBlock.kt:368-447`). Eski tokenli va plain-text kontent ham o'qiladi (`ContentBlock.kt:206-277,515-578`).

## Qo'llanilgan tekshiruv chegarasi

Xulosa manba kod va build/backend konfiguratsiyasiga asoslangan. README, PLAN, CHANGES va mavjud `.md` fayllar xulosa manbasi sifatida ishlatilmadi. UI qurilma/emulyatorda qo'lda bajarilmadi; shu sabab faqat koddan isbotlanmaydigan xatti-harakatlar hisobotda **Tasdiqlanmagan** deb ajratilgan.
