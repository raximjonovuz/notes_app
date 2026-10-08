# GitHub'dagi Mashhur Ochiq Manbaali Eslatma (Notes) Ilovalari — Texnik Tadqiqot

> **Tadqiqot sanasi:** 2026-yil 8-oktabr  
> **Maqsad:** O'xshash eslatma ilovasini yaratish uchun arxitektura va kod naqshlarini o'rganish

---

## Mundarija

1. [Umumiy ko'rinish va reyting](#umumiy-ko'rinish-va-reyting)
2. [Kross-platformali desktop ilovalar](#kross-platformali-desktop-ilovalar)
3. [Veb-ga asoslangan va real-vaqt ilovalar](#veb-ga-asoslangan-va-real-vaqt-ilovalar)
4. [Markdown / blok-asoslangan muharrirlar](#markdown--blok-asoslangan-muharrirlar)
5. [Mobil-birinchi va ixtisoslashgan platformalar](#mobil-birinchi-va-ixtisoslashgan-platformalar)
6. [Qo'shimcha kashf etilgan loyihalar](#qoshimcha-kashf-etilgan-loyihalar)
7. [Arxitektura va dizayn naqshlari — xulosalar](#arxitektura-va-dizayn-naqshlari--xulosalar)
8. [Tavsiyalar: o'z ilovangizni qurish uchun](#tavsiyalar-oz-ilovangizni-qurish-uchun)

---

## Umumiy ko'rinish va reyting

| # | Loyiha | Yulduzlar ⭐ | Til (asosiy) | Arxitektura | Litsenziya |
|---|--------|------------:|--------------|-------------|-----------|
| 1 | **AppFlowy** | 77.2k | Flutter + Rust | Block-editor, lokal-birinchi | AGPL-3.0 |
| 2 | **AFFiNE** | 73.3k | TypeScript + Rust | CRDT (YJS), canvas + docs | MIT |
| 3 | **Joplin** | 56.6k | TypeScript | Electron + React Native | AGPL-3.0 |
| 4 | **SiYuan** | 46.7k | Go + Electron | Block-DB, JSON .sy | AGPL-3.0 |
| 5 | **Logseq** | 45.2k | ClojureScript | DataScript, Markdown/Org | AGPL-3.0 |
| 6 | **Trilium Notes** | 38.2k | TypeScript | Electron monorepo | AGPL-3.0 |
| 7 | **Notesnook** | 14.7k | TS + React + RN | E2E shifrlash (XChaCha20) | GPL-3.0 |
| 8 | **Notable** | 23.5k | Electron + React | Fayl-asoslangan, lokal | — (kod yopilgan) |
| 9 | **Standard Notes** | 6.6k | TypeScript | Web/Electron/RN, monorepo | AGPL-3.0 |

---

## Kross-platformali desktop ilovalar

### 1. Joplin — `laurent22/joplin`

- **⭐ 56.6k yulduz | 6.3k fork | 15,917 commit**
- **Veb-sayt:** joplinapp.org
- **Litsenziya:** AGPL-3.0
- **Faollik:** Jamoa juda faol, har kuni yangilanishlar

**Texnologiya steki:**
- **Desktop:** Electron + React + Node.js (TypeScript)
- **Mobile:** React Native (Android/iOS)
- **Server (ixtiyoriy):** Docker, Node.js + PostgreSQL/SQLite
- **Ma'lumotlar bazasi:** SQLite (lokal) + Nextcloud/OneDrive/Dropbox/WebDAV (sinc)
- **Tillar:** TypeScript, JavaScript

**Asosiy xususiyatlar:**
- Markdown formatidagi eslatmalar
- Evernote'dan import (.enex)
- Web Clipper (brauzer kengaytmasi)
- End-to-end shifrlash (E2EE)
- Ko'p platformali sinc (Joplin Cloud, Nextcloud, Dropbox, OneDrive, WebDAV)
- To'liq matnli qidiruv (FTS)
- Pluginlar va temalar

**Arxitektura patterni:**
```
packages/
├── app-desktop/        # Electron ilovasi
├── app-mobile/         # React Native ilovasi
├── app-cli/            # CLI interfeys
├── server/             # Sinxronizatsiya serveri
├── lib/                # Asosiy biznes-logika
├── renderer/           # UI komponentlari
└── tools/              # Qurilma skriptlari
```
- **Lerna monorepo** — paketlar orasida kod almashish
- **Service Layer patterni** — `services/` ichida har bir xususiyat uchun alohida modul
- **Repository pattern** — ma'lumotlar bazasi bilan ishlash uchun
- **Reducer pattern** (Redux) — holat boshqaruvi

**Sinxronizatsiya:** P2P emas, markazlashgan. Yuklamalar `.sync/` papkasida va `.md` fayllar sifatida saqlanadi.

**Mobil:** Ha — Android va iOS uchun React Native ilovalari.

---

### 2. AppFlowy — `AppFlowy-IO/AppFlowy`

- **⭐ 77.2k yulduz | 6.0k fork | 7,210 commit**
- **Veb-sayt:** appflowy.com
- **Litsenziya:** AGPL-3.0
- **Faollik:** Faol rivojlanish, tez-tez commitlar

**Texnologiya steki:**
- **Frontend:** Flutter (Dart)
- **Backend/Core:** Rust (yadro biznes-logika)
- **Mobile:** Flutter (iOS/Android)
- **Ma'lumotlar:** AppFlowy-Cloud (PostgreSQL + MinIO)
- **Kross-platforma:** Linux, macOS, Windows, iOS, Android

**Asosiy xususiyatlar:**
- Notion alternativ sifatida
- AI-ga asoslangan ish maydoni
- Blok-asoslangan muharrir
- Ma'lumotlar bazalari (kanban, grid, calendar)
- Lokal-birinchi arxitektura
- Real vaqtda hamkorlik

**Arxitektura patterni:**
```
frontend/
├── appflowy_flutter/   # Flutter ilovasi
└── resources/         # Tarjimalar
```
- **Rust Core + Flutter UI** — yadro mantiq Rust'da, UI Flutter'da (FFI orqali)
- **Flutter Bloc pattern** — holat boshqaruvi
- **Repository pattern** — ma'lumotlar qatlami
- **Plugin arxitekturasi** — kengaytma imkoniyati

**Sinxronizatsiya:** AppFlowy Cloud (o'zining serveri) yoki self-host.

**Mobil:** Ha — iOS va Android (Flutter).

---

### 3. Trilium Notes — `TriliumNext/Trilium`

- **⭐ 38.2k yulduz | 2.6k fork | 39,977 commit**
- **Veb-sayt:** triliumnotes.org
- **Litsenziya:** AGPL-3.0
- **Eslatma:** Original loyiha `zadam/trilium`'dan `TriliumNext/Trilium`'ga ko'chirilgan

**Texnologiya steki:**
- **Backend:** Node.js (Express)
- **Frontend:** TypeScript + CKEditor 5 + Excalidraw + Leaflet + CodeMirror
- **Desktop:** Electron
- **Ma'lumotlar bazasi:** SQLite (default), PostgreSQL (server versiya)
- **Monorepo:** pnpm workspaces

**Asosiy xususiyatlar:**
- Chuqur ierarxik daraxt (ixtiyoriy chuqurlik)
- WYSIWYG muharrir
- Eslatmalarni klonlashgan (bir nechta joyda ko'rsatish)
- Eslatmalar versiyalash
- Kuchli shifrlash (har bir eslatma uchun alohida)
- Skriptlash (JavaScript frontend API)
- REST API (ETAPI)
- Kanvas eslatmalar (Excalidraw)
- MIND MAP, GEO xuddi

**Arxitektura patterni:**
```
apps/
├── server/            # Node.js backend
├── web-client/        # Veb-ilova
├── desktop/           # Electron
└── mobile/            # Mobil-brauzer
packages/
├── ckeditor5/         # Muharrir komponenti
└── excalidraw/        # Kanvas komponenti
```
- **Hierarchical Note Tree** — eslatmalar daraxt sifatida tashkil etilgan
- **Cloning** — bir xil eslatmani daraxtda bir nechta joyda ko'rsatish mumkin
- **Attributes-based metadata** — eslatmaga metadata qo'shish
- **Scripting API** — foydalanuvchi JavaScript orqali funksionallikni kengaytirishi mumkin

**Sinxronizatsiya:** Self-host sinxroniz serveri (o'z protokol orqali).

**Mobil:** Mobil-brauzer orqali (serverdan), shuningdek, [TriliumDroid](https://github.com/FliegendeWurst/TriliumDroid) (uchinchi tomon Android ilovasi).

---

### 4. Notesnook — `streetwriters/notesnook`

- **⭐ 14.7k yulduz | 1.0k fork | 15,661 commit**
- **Veb-sayt:** notesnook.com
- **Litsenziya:** GPL-3.0

**Texnologiya steki:**
- **Frontend:** React + TypeScript
- **Mobile:** React Native
- **Desktop:** Electron
- **Kriptografiya:** XChaCha20-Poly1305 + Argon2 (libsodium)
- **Monorepo:** npm workspaces (lerna o'rniga)

**Asosiy xusosiyatlar:**
- Evernote alternativ
- End-to-end shifrlash (nol ma'lumot)
- Bir nechta platformada sinxron
- Kross-platformali (Web, Desktop, iOS, Android)
- Web Clipper kengaytmasi

**Monorepo tuzilmasi (juda yaxshi o'rganish uchun):**
```
apps/
├── web/           # Veb-klient
├── desktop/       # Desktop klient (Electron)
├── mobile/        # Mobil klient (React Native)
└── web-clipper/   # Web Clipper kengaytmasi
extensions/
└── web-clipper/
packages/
├── core/          # Umumiy yadro (barcha platformalar)
├── crypto/        # Kriptografiya kutubxonasi
├── editor/        # Notesnook muharriri
├── editor-mobile/ # Mobil muharrir (yupqa qoplama)
├── clipper/       # Web Clipper yadro
├── sodium/        # Libsodium qoplama
├── streamable-fs/ # Streamable fayl tizimi
└── theme/         # Tema
```

**Arxitektura patternlari:**
- **Platform-agnostic Core** — `@notesnook/core` barcha platformalarda ishlatiladi
- **Service Layer** — har bir modul alohida servis
- **Crypto wrapper** — barcha shifrlash libsodium orqali yagona API
- **Streamable FS** — IndexedDB-asoslangan fayl tizimi

**Sinxronizatsiya:** O'zining sinxron serveri, E2EE bilan.

**Mobil:** Ha — iOS va Android (React Native).

---

## Veb-ga asoslangan va real-vaqt ilovalar

### 5. AFFiNE — `toeverything/AFFiNE`

- **⭐ 73.3k yulduz | 5.3k fork | 11,523 commit**
- **Veb-sayt:** affine.pro
- **Litsenziya:** MIT (jamoa versiyasi), EE (enterprise alohida)
- **Eng so'nggi commitlar:** juda faol (2026)

**Texnologiya steki:**
- **Frontend:** TypeScript + React + Jotai (holat) + Vite
- **Desktop:** Electron
- **Yadro:** Rust (`y-octo` — YJS CRDT implementatsiya), `OctoBase` (ma'lumotlar bazasi)
- **Editor:** BlockSuite (alohida loyiha)
- **Ma'lumotlar:** CRDT (Yjs), IndexedDB (lokal), PostgreSQL (server)

**Asosiy xususiyatlar:**
- Notion + Miro = AFFiNE
- Real vaqtda hamkorlik (CRDT)
- Lokal-birinchi
- Canvas + docs yagona ish maydoni
- AI yordamchi
- Multi-view ma'lumotlar bazalari
- Self-host imkoniyati

**Arxitektura patternlari:**
```
packages/
├── frontend/           # Web frontend
├── backend/            # Server
├── component/           # Komponentlar kutubxonasi
├── common/             # Umumiy kod
└── ...
blocksuite/            # Alohida editor loyihasi
tools/                   # Qurilma skriptlari
```

- **CRDT (Yjs)** — taqsimlangan ma'lumotlar uchun conflicts-free
- **Block Suite** — hujjat/blok modeli
- **Local-first + Cloud sync** — CRDT orqali
- **Module federation** — `packages/` moduliy arxitekti
- **Rust + Node.js FFI** — `napi-rs` orqali

**Sinxroniz:** CRDT-asoslangan real-vaqt sinxron (server bilan yoki peer-to-peer).

**Mobil:** Hozircha cheklangan (asosan veb va desktop).

---

### 6. Standard Notes — `standardnotes/app`

- **⭐ 6.6k yulduz | 557 fork | 7,261 commit**
- **Veb-sayt:** standardnotes.com
- **Litsenziya:** AGPL-3.0
- **Eslatma:** Server (backend) alohida repo'da (`standardnotes/server`)

**Texnologiya steki:**
- **Frontend:** TypeScript, statik HTML/JS/CSS build
- **Mobile:** React Native (alohida repo)
- **Desktop:** Electron (alohida repo)
- **Backend:** Node.js + PostgreSQL (alohida repo `standardnotes/server`)
- **Monorepo:** Lerna + Yarn

**Asosiy xususiyat:**
- End-to-end shifrlash
- Kross-platformali sinc
- Cheksiz qurilmalar
- Self-host imkoniyati
- Uzoq muddatlilik (longevity) tamoyili

**Arxitektura patternlari:**
- **Extension API** — har bir xususiyat "editor" yoki sifatida bo'ladi
- **Component-based** — mustaqil komponentlar
- **Static Site Generation** — veb-ilova statik HTML/JS
- **Server-Side Rendering optional**

**Monorepo tuzilmasi:**
```
packages/
├── web/                # Veb-ilova
├── mobile/             # Mobil ilova
├── desktop/            # Desktop ilova
├── services/           # Servislar
├── ui/                 # UI kutubxonasi
├── snippets/           # Kod snippetlar
├── files/              # Fayl ilovasi
├── encryption/         # Shifrlash
└── web-publish.yaml/    # Veb-nash
- **Sinxron:** O'zining sinxron serveri, E2EE.

**Mobil:** Ha — iOS va Android (React Native).

---

## Markdown / blok-asoslangan muharrirlar

### 7. Logseq — `logseq/logseq`

- **⭐ 45.2k yulduz | 2.8k fork | 25,857 commit**
- **Veb-sayt:** logseq.com
- **Litsenziya:** AGPL-3.0
- **Faollik:** Faol

**Texnologiya steki:**
- **Frontend:** ClojureScript + shadow-cljs + React
- **Mobile:** ClojureScript + Capacitor (iOS/Android)
- **Desktop:** Electron
- **Ma'lumotlar bazasi:** DataScript (in-memory Datalog DB) + lokal fayllar (Markdown/Org-mode)
- **Parser:** OCaml + Angstrom (`mldoc`)

**Asosiy xususiyatlar:**
- Markdown va Org-mode qo'llab-quvvatlash
- Bilimlar grafi (knowledge graph)
- Lokal-birinchi
- PDF annotatsiya
- Vazifalar boshqaruvi
- Plugin tizimi
- Git-asoslangan sinc (ixtiyoriy)
- Yangi DB versiyasi (SQLite + RTC — real-time collaboration)

**Arxitektura patternlari:**
```
src/
├── main/               # Asosiy ilova kodi (ClojureScript)
│   ├── frontend/       # Frontend komponentlar
│   ├── db/             # DataScript qatlami
│   ├── editor/         # Muharrir
│   └── ...
├── sidecar/            # OCaml parser (alohida)
├── android/            # Android (Capacitor)
├── ios/                # iOS (Capacitor)
└── cli/                # CLI
```
- **Immutable Data + Datalog Queries** — DataScript yordamida
- **Plugin API** — kuchli plagin tizimi
- **Local-first + optional sync** — Git orqali
- **Capacitor** — veb → mobil o'rash
- **OCaml sidecar** — parser alohida jarayonda

**Sinxron:** Lokal fayllar + ihtiyoriyiyang Git bulur. DB versiyada RTC (real-time collab).

**Mobil:** Ha — iOS va Android (Capacitor orqali).

---

### 8. SiYuan — `siyuan-note/siyuan`

- **⭐ 46.7k yulduz | 3.0k fork | 28,932 commit**
- **Veb-sayt:** b3log.org/siyuan
- **Litsenziya:** AGPL-3.0
- **Faollik:** Juda faol (har kuni commitlar)

**Texnologiya steki:**
- **Backend (kernel):** Go
- **Frontend (UI):** TypeScript + Electron
- **Mobile:** Kotlin/Swift (alohida repo'lar)
- **Ma'lumotlar bazasi:** SQLite + `.sy` JSON fayllar
- **Editor:** `lute` (alohida Go kutubxonasi)

**Asosiy xususiyat:**
- Blok-darajali havolalar (block-level references)
- Markdown WYSIWYG
- SQL so'rovlari (SQLite)
- 2 tomonlama havolalar
- Million so'zli hujjat
- AI yozish (OpenAI API)
- Tesseract OCR
- PDF/Word/HTML eksport
- Android/iOS/HarmonyOS

**Arxitektura patternlari (ekosistema):**
```
siyuan/                # Asosiy ilova (bu)
- app/                  # Electron UI (TypeScript)
- kernel/              # Go kernel
  ├── go.mod
  └── ...

Ekosistema:
├── lute/               # Editor engine (Go)
├── chrome/             # Chrome kengaytmasi
├── bazaar/             # Hamjamiyat bozori
├── dejavu/             # Data repo (Git-asoslangan)
├── petal/              # Plugin API
├── android/            # Android ilova (Kotlin)
├── ios/                # iOS ilova (Swift)
├── harmony/            # HarmonyOS ilova
└── riff/               # Spaced repetition
```
- **Kernel + UI ajratish** — Go kernel + Electron UI
- **Block-tree model** — har bir blok alohida saqlanadi
- **Git-asoslangan vesiya** — `dejavu` moduli
- **Plugin API** — `petal` orqali
- **Cross-repo ecosystem** — har bir qism alohida repo'da

**Sinxron:** Bulutli sinc (pullik) yoki self-host (Docker).

**Mobil:** Ha — Android, iOS, HarmonyOS.

---

### 9. Notable — `notable/notable`

- **⭐ 23.5k yulduz | 1.2k fork**
- **Veb-sayt:** notable.app
- **Litsenziya:** **Faqat eski versiyalar ochiq kod!** Loyiha endi yopiq.
- **Faollik:** **To'xtatilgan** — oxirgi yangilanish 2024-06-21

**Texnologiya steki:**
- **Frontend:** Electron + React (TypeScript)
- **Editor:** CodeMirror (VS Code'dan olingan)
- **Ma'lumotlar:** Fayl-asoslangan (lokal `.md` fayllar)

**Asosiy xususiyat:**
- Faqat GitHub Flavored Markdown
- WYSIWYG yo'q
- Vendor lock-in yo'q
- Qidiruv va almashtirish (regex)
- Biriktirmalar (attachments)
- Evernote import
- Zen mode, split editor, multi-note editor

**Arxitektura patternlari:**
- **File-based storage** — eslatmalar oddiy `.md` fayllar
- **CodeMirror integration** — VS Code muharriri
- **KaTeX, Mermaid** — matematika va diagrammalar
- **Dark theme + Custom themes**
- **Single Electron app** — monolit, monorepo emas

**Sinxron:** Sinxron yo'q olmasdan, fayllar Dropbox/iCloud orqali sinxronlanadi.

**Mobil:** Yo'q — faqat desktop.

> ⚠️ **Muhim:** Notable endi **yopiq kod manba (proprietary)**. Yangi versiyalar uchun `notable/notable` kodi mavjud emas. Yangi loyihalar uchun tavsiya etilmaydi.

---

## Mobil-birinchi va ixtisoslashgan platformalar

### 10. FSNotes — `glushchenko/fsnotes`

- **⭐ 7.5k yulduz**
- **Veb-sayt:** fsnotas.app
- **Litsenziya:** MIT
- **Til:** Swift (native macOS/iOS)

**Texnologiya:** Swift, AppKit/UIKit, fayl-asoslangan.

**Xususiyat:** Markdown eslatmalar — Mac va iPhone uchun native.

---

## Qo'shimcha kashf etilgan loyihalar (GitHub qidiruv natijalari)

| # | Loyiha | ⭐ | Til | Izoh |
|---|--------|---:|-----|------|
| 1 | **karakeep-app/karakeep** | 29.5k | TypeScript, Next.js, React Native | Bookmark + notes + images, AI bilan |
| 2 | **BoostIO/BoostNote-Legacy** | 16.9k | JavaScript, Electron | Arxivlangan, eski kod |
| 3 | **codexu/note-gen** | 12.9k | TypeScript | AI bilan lokal-birinchi Markdown |
| 4 | **massCodeIO/massCode** | 7.0k | TypeScript, Electron | Developer workspace (snippets + notes) |
| 5 | **taniarascia/takenote** | 7.1k | TypeScript | Veb-ga asoslangan, developer notes |

### Obsidian (eslatma)

- **Obsidian** — **yopiq kod manba** (proprietary), bepul shaxsiy foydalanish uchun.
- Faqat **Obsidian Sync** kod va **API документация** ochiq manba.
- Repo: `obsidianmd/obsidian-api-docs`
- **Yangi loyiha uchun tavsiya etilmaydi** — kod ko'rib bo'lmaydi.

---

## Arxitektura va dizayn naqshlari — xulosalar

### 🔑 Eng muhim arxitektura naqshlari

#### 1. **Monorepo tuzilmasi**
Eng ko'p loyihalar monorepo ishlatadi:
- **Joplin** — Lerna
- **Notesnook** — npm workspaces
- **Standard Notes** — Lerna + Yarn
- **Trilium** — pnpm workspaces
- **AFFiNE** — Yarn workspaces

**Nega?** — Umumiy kodni (core, types, services) platformalar o'rtasida bo'lishish.

**Tavsiya:** `pnpm workspaces` yoki `nx` — eng zamonaviy va tezkor.

#### 2. **Core/UI ajratish**
Notesnook va Joplin eng yaxshi namuna:
```
packages/core/    # Platformadan mustaqil biznes-logika
packages/crypto/  # Kriptografiya
apps/web/         # Faqat UI
apps/desktop/     # Faqat UI (Electron)
apps/mobile/      # Faqat UI (React Native)
```

**Natija:** Bitta kod, 3-4 platforma.

#### 3. **Lokal-birinchi + Sinxron (local-first)**
Eng zamonaviy yondashuv:
- **Logseq** — DataScript + Markdown fayllar
- **AFFiNE** — Yjs (CRDT) + IndexedDB
- **Trilium** — SQLite + server sinc
- **AppFlowy** — Flutter + lokal + cloud
- **SiYuan** — SQLite + Git-asoslangan vesiya

**Printsipl:** Yaghona naqshihang sinxroniyang veri emas, balkihangiyang veri lokal-darqiyangda saqlanadi.

#### 4. **CRDT (Conflict-free Replicated Data Types)**
- **AFFiNE** — Yjs + `y-octo` (Rust'da)
- **AppFlowy** — CRDT-asoslangan
- Real vaqtda hamkorlik uchun eng yaxshi yondashuv

**Tavsiya:** Agar real-time collab kerak bo'lsa — Yjs yoki Automerge.

#### 5. **Block-asoslangan arxitektura**
- **Notion-style** — AppFlowy, AFFiNE, SiYuan, Logseq DB
- **Markdown-asoslangan** — Joplin, Logseq, Notesnook, Obsidian

#### 6. **Plugin tizimi**
- **Logseq** — eng kuchli plugin API
- **Notesnook** — editor + extensions
- **Joplin** — pluginlar + temalar
- **AFFiNE** — BlockSuite asosida
- **Trilium** — scriptable + widgetlar

#### 7. **Rust + Web/Desktop**
- **AFFiNE** — `y-octo`, `OctoBase`
- **AppFlowy** — Rust Core + Flutter UI (FFI)
- Tezlik va xavfsizlik uchun

---

## Texnologiya taqqoslash jadvali

| Texnologiya | Joplin | AppFlowy | AFFiNE | Logseq | Trilium | SiYuan | Notesnook |
|-------------|:------:|:-------:|:------:|:------:|:-------:|:------:|:---------:|
| **TypeScript** | ✅ | — | ✅ | — | ✅ | ✅ | ✅ |
| **React** | ✅ | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Electron** | ✅ | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| **React Native** | ✅ | — | — | — | — | — | ✅ |
| **Flutter** | — | ✅ | — | — | — | — | — |
| **Rust** | — | ✅ | ✅ | — | — | — | — |
| **Go** | — | — | — | — | — | ✅ | — |
| **ClojureScript** | — | — | — | ✅ | — | — | — |
| **CRDT** | — | ✅ | ✅ | — | — | — | — |
| **E2EE** | ✅ | — | — | — | ✅ | — | ✅ |
| **Mobile** | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ✅ | ✅ |
| **Plugin API** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## Ma'lumotlar bazasi / storage yondashuvlari

| Loyiha | Lokal storage | Sinxron |
|--------|---------------|---------|
| Joplin | SQLite + .md fayllar | Nextcloud, Dropbox, OneDrive, WebDAV, Joplin Cloud |
| AppFlowy | SQLite (Flutter app) | AppFlowy Cloud (PostgreSQL + MinIO) |
| Logseq | Markdown/Org fayllar + DataScript | Git (ixtiyoriy), Yangi DB versiyada SQLite + RTC |
| Trilium | SQLite (default), PostgreSQL (server) | Self-host sync server |
| Notesnook | IndexedDB (streamable-fs) | Notesnook Sync server (E2EE) |
| AFFiNE | IndexedDB + Yjs (CRDT) | Cloud server (real-time CRDT sync) |
| SiYuan | SQLite + .sy JSON files + dejavu (Git) | Cloud sync (pullik) yoki self-host |
| Standard Notes | IndexedDB | Standard Notes server (E2EE) |
| Notable | Fayl-asoslangan `.md` | Yo'q (Dropbox/iCloud orqali) |

---

## Litsenziya taqqoslash

| Litsenziya | Loyihalar |
|-----------|-----------|
| **AGPL-3.0** | Joplin, AppFlowy, Logseq, Trilium, SiYuan, Standard Notes |
| **GPL-3.0** | Notesnook |
| **MIT** | AFFiNE (community edition) |
| **Yopiq** | Notable (eski kod ochiq), Obsidian |

> **Maslahat:** AGPL-3.0 tijorat maqsadlarida foydalanishda cheklovlar bor. MIT eng erkin.

---

## Tavsiyalar: o'z ilovangizni qurish uchun

### 🎯 Texnologiya tanlash bo'yicha

#### A. Agar **tez** va **sodda** boshlash kerak bo'lsa:
```
Frontend: React + TypeScript + Vite
Desktop:  Electron
Mobile:   React Native
Storage:  SQLite (lokal) + fayl-asoslangan
Sync:     Dropbox/OneDrive/WebDAV (Joplin usuli)
```

#### B. Agar **zamonaviy** va **kuchli** qilmoqchi bo'lsangiz:
```
Frontend: React + TypeScript + Jotai (affine hauziyahiy)
Editor:   BlockSuite yoki Tiptap (ProseMirror)
CRDT:     Yjs (y-octo - Rust uchun)
Backend:  Rust (OctoBase usuli)
Storage:  IndexedDB + PostgreSQL
Sync:     CRDT real-time
Desktop:  Electron
Mobile:   React Native
```

#### C. Agar **tezkor native** kerak bo'lsa:
```
Frontend: Flutter (AppFlowy usuli)
Core:     Rust (FFI orqali)
Storage:  SQLite
```

#### D. Agar **xavfsizlik** asosiy bo'lsa:
```
E2EE:     XChaCha20-Poly1305 + Argon2 (Notesnook)
Storage:  IndexedDB
Sync:     O'z server + E2EE
Pattern:  Notesnook monorepo tuzilmasi
```

### 🏗 Arxitektura bo'yicha tavsiyalar

1. **Monorepo** — albatross, `pnpm workspaces` bilan boshlang.
2. **Core/UI ajratish** — birinchi kunidan.
3. **Lokal-birinchi** — `local-first` falsafasini qabul qiling.
4. **Block model** — Notion uslubidagi blok modeli foydalanuvchilar uchun qulay.
5. **Markdown fallback** — eksport uchun Markdown formatini saqlang.
6. **Plugin API** — dastlabki bosqichda bo'lmasada, keyin qo'shish uchun aralashni to'g'ri qo'ying.
7. **CRDT** — agar real-time hamkorlik kerak bo'lsa — Yjs.

### 📚 O'rganish uchun eng yaxshi repositorlar

1. **Notesnook** — `packages/` tuzilmasi juda yaxshi, eng yaxshi monorepo beispiel.
2. **AFFiNE** — CRDT va Rust integratsiyasi uchun.
3. **AppFlowy** — Flutter + Rust uchun.
4. **Trilium** — murakkab ilovalar uchun arxitektura.
5. **Logseq** — lokal-birinchi va DataScript uchun.

---

## Manbalar

- Joplin: https://github.com/laurent22/joplin
- AppFlowy: https://github.com/AppFlowy-IO/AppFlowy
- Logseq: https://github.com/logseq/logseq
- Trilium Notes: https://github.com/TriliumNext/Trilium
- Standard Notes: https://github.com/standardnotes/app
- Notesnook: https://github.com/streetwriters/notesnook
- AFFiNE: https://github.com/toeverything/AFFiNE
- SiYuan: https://github.com/siyuan-note/siyuan
- Notable: https://github.com/notable/notable
- GitHub search: https://github.com/search?q=notes+app&type=repositories&s=stars

---

**Muallif eslatmasi:** Bu tadqiqot 2026-yil 8-oktabr holatiga ko'ra tayyorlangan. Yulduzlar soni va loyiha holati tez o'zgarishi mumkin.