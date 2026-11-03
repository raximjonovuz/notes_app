# Boshqa AI uchun bajarish buyrug'i

Sen senior Android/Kotlin muhandisisan. `docs-analysis/02_XATOLAR_HISOBOTI.md` va `03_TUZATISH_REJASI.md`ni bajar. Har commitdan oldin `git diff`, keyin `gradlew.bat testDebugUnitTest assembleDebug`; backend o'zgarsa `npm run typecheck` va `npm test`. Ishlayotgan image, bold, underline, strikethroughni regression test bilan himoyala.

## Build saqlanadigan bosqichlar

1. **Test-only, T-01/T-02/T-03/D-01/D-02.** StyleRange serializer/decode, split/merge va title selection uchun testlar yoz. T-03 decoder fixini qil; source v2 UI o'zgarmaydi. Commit/build.
2. **V3 domain + adapter, T-04/T-05/TB-04.** `RichDocumentV3.kt`, v3 serializer/parser va `RichDocumentAdapter` qo'sh. `ContentBlock.kt`dagi v2/legacy parserlar va `BlocksEditor` hali ishlashda davom etadi; load v3ni adapter bilan eski `ContentBlock`ga beradi, save v3 yozadi. Shu commitning o'zi full v3↔legacy conversion testlari bilan build bo'ladi. Commit/build.
3. **Bitta-field renderer, T-04/T-05/TB-05.** `RichTextBlockEditor`ni qo'sh, feature flag/debug pathda adapter outputi bilan render qil. TextBlock uchun bitta `BasicTextField`; native selection/copy/select-all test. Eski `TextBlockEditor` fallback sifatida qoladi. `EditorCommand`/toolbar resolver qo'sh. Commit/build.
4. **Checklist semantic reducer, TB-01–TB-04.** Yangi rendererda checkbox overlay + paragraph reducer qil. Empty Enter/Backspace, merge, reorder preference, save/reopen/sync test. Eski `ChecklistBlockEditor`ni faqat barcha tests green bo'lgach olib tashla. Commit/build.
5. **Input resilience, T-01/T-06/D-02.** Title selection, `SavedStateHandle`, IME composition va grouped undo. V3 renderer all users uchun yoqiladi; old UI fallbackni keyingi releasegacha qoldir. Commit/build.
6. **Palette/DB, R-01–R-03/D-03/D-04.** `NoteColorKey`, 12 swatch, Room 11→12 migration, backend DTO/D1 migration. Har palette juftligi WCAG test. Old intni compatibility read sifatida qoldir. Commit/build.
7. **Cleanup.** Telemetry/migration confidence tasdiqlangandan keyin v2 writer, old checklist UI, redundant `fontWeight/textSize`ni alohida schema releaseida olib tashla. Upgrade tests va full manual matrix bajar.

Manual matrix: old v2/V2.6/plain/image note open-save-reopen; selected bold/underline/strike; multi-paragraph H1/H2/P and copy/select-all; checklist Enter/Backspace/reorder; Uzbek/emoji IME; rotation/process recreate; image upload/sync/offline; dark/light/system/dynamic palette. Ma'lumot tushunarsiz bo'lsa o'chirma: legacy raw parser/fallbackni saqla.
