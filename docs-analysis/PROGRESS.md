# PROGRESS: Notes Ilovasini Tuzatish Jarayoni

## Boshlang'ich holat
- `docs-analysis/` (00..06) to'liq o'qib chiqildi.
- Kod bazasi va testlar tekshirildi (`gradlew testDebugUnitTest assembleDebug` 100% o'tdi).
- 18 ta topilma (T-01..T-06, TB-01..TB-05, R-01..R-03, D-01..D-04) manba kodidagi qatorlari bilan solishtirildi. Hech qanday to'xtatuvchi ziddiyat topilmadi (`CONFLICTS.md` yaratishga ehtiyoj yo'q).

---

## 1-Bosqich: Testlar va T-03
- **Status:** Bajarildi
- **Bajarilgan ID'lar:** T-03
- **O'tgan testlar:** `StyleRangeSerializerTest` (roundtrip, legacy 4-flag, new 5-flag, empty/malformed handling) + barcha mavjud unit testlar va `assembleDebug`.
- **Commit:** `1ab1bf1` "fix(edit): T-03 fix legacy V2.6 StyleRange decode and encode flags"

---

## 2-Bosqich: T-02/D-01 span algoritmi
- **Status:** Bajarildi
- **Bajarilgan ID'lar:** T-02, D-01
- **O'tgan testlar:** `StyleRangeAlgorithmTest` (bold 0..10 + underline 3..5 split, bo'sh matnga toggle, qisman formatlangan selection, fully styled toggle-off, setColor split/retain, canonical query va active state mosligi) + barcha unit testlar va `assembleDebug`.
- **Commit:** `90d5d19` "fix(edit): T-02 and D-01 canonical span split and merge algorithm"

---

## 3-Bosqich: V3 model va adapter (T-04/T-05/TB-04)
- **Status:** Bajarildi
- **Bajarilgan ID'lar:** T-04 (model qismi), T-05 (model/sweep parsing qismi), TB-04 (model qismi)
- **O'tgan testlar:** `RichDocumentV3Test` (v3 roundtrip, adapter legacy conversion, v2 -> v3 saqlash, 5000+ belgili performance benchmark) + barcha mavjud 103 test va `assembleDebug`.
- **Commit:** `738175f` "feat(model): T-04 T-05 TB-04 RichDocumentV3 model and adapter with seamless v2 fallback"

---

## 4-Bosqich: Bitta-field renderer + H1/H2/P (T-04/TB-05)
- **Status:** 1-qadam yakunlandi va testlar o'tdi (wire-new-editor branchida)
- **Bajarilgan ishlar:**
  - `RichTextBlockEditor` `NoteEditScreen.kt`ga ulandi (`USE_RICH_TEXT_BLOCK_EDITOR = BuildConfig.DEBUG`).
  - `RichDocumentAdapter.kt:63-69` tuzatildi: agar eski blokda `fontSizesByLine` bo'sh bo'lsa va blok darajasida fontSize=2 bo'lsa, butun blok H1 qilinmaydi — faqat 1-paragraf H1, qolgan paragraflar esa `BODY` bo'ladi.
  - Yangi regression testlar qo'shildi (`RichDocumentV3Test`):
    - `legacy block with 3 lines and block-level fontSize 2 converts only line 1 to H1 and rest to BODY`
    - `legacy block with fontSizesByLine preserves explicit per-line heading levels`
  - Barcha 106 ta unit testlar muvaffaqiyatli o'tdi.
  - Commit: `51accb6` "fix(adapter): prevent full-block H1 when fontSizesByLine is empty and add regression tests".
  - Yangilangan APK telefonga (`SM-A556E`) o'rnatildi (`installDebug`) va ishga tushirildi.
- **Keyingi qadam (2-qadam):** Toolbar tugmalarini (Oddiy matn / Sarlavha 1 / Sarlavha 2 dropdown, B, U, S) `EditorCommand`/`EditorResolver`ga ulash.



---

## 5-Bosqich: Checklist (TB-01..TB-04)
- **Status:** Qisman bajarildi (Adapter va prefiks mosligi to'liq tuzatildi, ChecklistReducer modeli tayyor, bitta-field UI'ga ulanmagan)
- **Bajarilgan ishlar:**
  - `ChecklistReducer` (Enter, Backspace, reorder checked) yaratildi va testlandi.
  - `RichDocumentAdapter.toLegacy` da CHECKLIST paragraphlar uchun markdown prefikslari (`"- [ ] "`, `"- [x] "`) qayta tiklandi (`rebuiltLines` ishlatildi).
  - Span offsetlari har ikki yo'nalishda (+/- 6 har checklist qator uchun) matematik aniq siljitildi, formatlash (bold, underline) roundtripda buzilmaydi.
  - Takror prefiks himoyasi: ketma-ket takrorlangan prefikslar (`"- [ ] - [ ] "`) bitta deb olinadi, `toLegacy` da mavjud prefiks ustiga qayta prefiks qo'shilmaydi (idempotent roundtrip).
  - Aralash bloklar (bir blokda CHECKLIST va BODY) to'g'ri ajratilib, faqat CHECKLIST qatorlariga prefiks qo'shiladi.
  - 118 ta unit testlar muvaffaqiyatli o'tdi.
- **Muhim eslatma:** `ChecklistReducer.reorderChecked` matn va spanlarni ko'chirmaydi (faqat run ro'yxatini filterlaydi), yangi bitta-field editor ulanganda text bo'laklari va spanlarni ham ko'chirish shart.
- **Qolgan ishlar:** Yangi rendererda checkbox overlay, indent, tap va ChecklistReducer'ni UI'ga ulash, eski `- [ ]` prefikslarini chiqarib tashlash.

---

## 6-Bosqich: Input barqarorligi (T-01/T-06/D-02)
- **Status:** Qisman bajarildi (T-01 va D-02 UI'ga ulandi, T-06 ulanmagan)
- **Bajarilgan ishlar:** Title selection holati ko'tarildi (T-01), IME composition paytida snapshot yozilmasligi qilindi (D-02).
- **Qolgan ishlar:** `SavedStateHandle` (T-06) orqali note id, title selection va block selection'ni saqlash.

---

## 7-Bosqich: Ranglar va DB (R-01..R-03, D-03, D-04)
- **Status:** Bajarildi
- **Bajarilgan ID'lar:** R-01, R-02, R-03, D-03, D-04
- **O'tgan testlar:**
  - `NoteColorPaletteTest` — barcha 12 ta rang (Default, Red, Orange, Yellow, Green, Teal, Blue, Dark Blue, Purple, Pink, Brown, Gray) uchun yorug' va qorong'u rejimlarda WCAG AA >= 4.5:1 kontrasti tasdiqlandi.
  - Room 11 -> 12 migratsiya va sxema validatsiyasi (`12.json` avtomatik yaratildi).
  - Backend TypeScript tekshiruvi (`tsc --noEmit`) va Vitest testlari (21 ta test) 100% o'tdi.
  - Android unit testlari (104 ta test) va `assembleDebug` 100% muvaffaqiyatli yakunlandi.
- **Commit:** `546f530` "feat(palette,db): R-01 R-02 R-03 Room v12 migration, 12-color WCAG palette, and D1 backend migration"

---

## 8-Bosqich: Tozalash, Versiya oshirish va Google Play Console Relizi
- **Status:** Bajarildi
- **Bajarilgan ishlar:**
  - Checklist Enter debouncing va ro'yxat kartochkalaridagi bo'sh prefikslar xatosi tuzatildi (5a4de54).
  - Versiya 1.0.6 (versionCode 7) ga ko'tarildi (d902f96).
  - wire-new-editor branchidagi barcha tuzatishlar master branchiga to'liq va xatosiz merge qilindi.
  - Google Play Console uchun imzolangan Notes-v1.0.6-release.aab va mustaqil o'rnatish uchun Notes-v1.0.6-release.apk muvaffaqiyatli yig'ildi (bundleRelease va assembleRelease).
  - Reliz fayllari Desktop (C:\Users\user\Desktop) va dist/ jildiga joylashtirildi.
  - Desktopdagi eski, vaqtincha va keraksiz kesh fayllar tozalandi.
