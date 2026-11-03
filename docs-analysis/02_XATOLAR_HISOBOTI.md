# Xatolar hisoboti

Jiddiylik: Kritik — ma'lumot/format yo'qolishi; Yuqori — asosiy oqim buzilishi; O'rta — sezilarli UX/performance; Past — kelajakdagi xavf.

## A. Matn va formatlash

### T-01 — Yuqori — title caret/selection har recompositionda oxirga sakraydi
**Fayl:** `notes/app/src/main/java/com/example/notes/ui/edit/NoteEditScreen.kt:652-657`.

Hozir `BasicTextField.value` har recompositionda `TextFieldValue(text=state.title, selection=TextRange(state.title.length))` qilib qayta yaratiladi. Natija: title ichida o'rtaga kursor qo'yib yozish yoki range tanlashdagi selection oxirga suriladi. Kutilgani — `TextFieldValue` (text, selection va composition) bitta state manbasida saqlanishi.

Takrorlash: titlega `abcdef` yozing; `cd` oldiga caret qo'ying; belgi kiriting. State emissiondan keyin caret endga o'tadi.

### T-02 — Kritik — selectionga tegadigan formatlash mavjud span chegaralaridan tashqarisini o'chiradi
**Fayl:** `.../StyleRange.kt:257-315,322-357`; chaqiruvchi `NoteEditViewModel.kt:830-916`.

Sabab: `outside = ranges.filterNot { it.end > s && it.start < e }` selection bilan kesishgan butun old range-ni olib tashlaydi; keyin faqat selection uchun replacement qo'shiladi. Masalan 0..10 bold range ichidan 3..5 underline qilinsa, 0..3 va 5..10 dagi bold yo'qoladi. Kutilgani — range chap/markaz/o'ng segmentlarga split qilinishi va faqat kerakli attribute o'zgarishi.

Takrorlash: 10 belgini bold qiling, 4–6 belgini tanlab underline bosing; tanlov tashqarisidagi bold yo'qolishini kuzating.

### T-03 — Yuqori — V2.6 legacy style-range serializer o'zini o'zi decode qila olmaydi
**Fayl:** `notes/app/src/main/java/com/example/notes/ui/edit/ContentBlock.kt:606-652`; ishlatilishi `ContentBlock.kt:295-309,349-356`.

`encode` `0-3:b|-|u|c0` ko'rinishida bitta `:` bilan yozadi. `decode` esa `segment.split(':')` uchun `parts.size != 5` bo'lsa skip qiladi. Shuning uchun V2.6 headerdan ochilgan spanlar bo'sh bo'ladi. V2 JSON oqimi bunda ishlaydi, ammo header formatiga tushgan ma'lumot/migratsiya formatlashini yo'qotadi.

### T-04 — Yuqori — H1/H2/P semantik paragraph modeli yo'q
**Fayllar:** `ContentBlock.kt:60-90,389-400`; `NoteEditViewModel.kt:1044-1100,1417-1432`.

Hozir `fontSize` integer va `fontSizesByLine: Map<Int,Int>`; `insertHeading` butun active blockning `fontSize`ini almashtiradi va barcha satr override-ni tozalaydi (`1425-1428`). Bu H1/H2/P uslubi emas va multi-paragraph blokda butun matnga ta'sir qiladi. Kutilgani — har paragraph uchun explicit `paragraphStyle` (H1/H2/BODY), char interval uchun span.

### T-05 — O'rta — uzun text rendering O(n×r) va har editda to'liq transform
**Fayl:** `StyleRange.kt:121-168`; `NoteEditScreen.kt:197-217,1146-1149`.

Har character uchun `ranges.filter`, keyin har keyingi character uchun yana `filter` ishlaydi. R range va N belgi uchun yomon holatda O(N×R); transform butun blockga qo'llanadi. Uzun note sekinlashishi koddan kutiladi. Aniq FPS o'lchovi **Tasdiqlanmagan**.

### T-06 — O'rta — process deathdan keyin yangi, hali DBga yozilmagan editor state tiklanmaydi
**Fayl:** `NoteEditViewModel.kt:132,196-199,252-311`; factory `1506-1523`.

`SavedStateHandle` yo'q; state faqat xotiradagi `MutableStateFlow`. Config-change ViewModel saqlanishi Android tomonidan boshqariladi, ammo process deathda yangi note state tiklanishi uchun serializable saved state ko'rinmaydi. Debounced 600ms (`1168-1181`) ichida process o'lsa oxirgi edit ham DBga bormasligi mumkin.

## B. Toolbar va checklist

### TB-01 — Yuqori — checkbox checked itemni pastga ko'chirmaydi
**Fayl:** `NoteEditScreen.kt:1474-1480`.

Click faqat `done`ni inverse qilib, ayni `working[lineIdx]`ni saqlaydi; reorder yo'q. Google Keep uslubida checked itemlar (sorting tanlovi bo'lsa) pastga tushirilishi kerak. Kutilgan sort preference/tartib modeli yo'q.

### TB-02 — Yuqori — bo'sh checklist itemda Enter checklistni yakunlamaydi
**Fayl:** `NoteEditScreen.kt:1494-1521,1613-1621`.

Bo'sh itemda ham Enter yangi `hasCheckbox=true` row qo'shadi. Normal textga o'tish yoki checklistdan chiqish yo'q. Kutilgani: bo'sh itemdagi Enter — checklist mode exit (yoki qo'shni P paragraph), non-emptyda yangi unchecked item.

### TB-03 — O'rta — birinchi bo'sh checklist itemda Backspace normal paragraphga qaytarmaydi
**Fayl:** `NoteEditScreen.kt:1523-1536,1656-1664`.

Faqat `lineIdx > 0` dagi row oldingiga merge qilinadi. Birinchi/only bo'sh item false qaytaradi; prefix/mode olib tashlanmaydi. Kutilgani: only empty item Backspace → plain P; boshidagi bo'sh itemda old paragraph bilan semantic merge.

### TB-04 — O'rta — checklist text modelida prefixlar matnning o'ziga aralashgan
**Fayllar:** `NoteEditViewModel.kt:940-1041`; `NoteEditScreen.kt:1356-1382`.

`- [ ] ` va `- [x] ` textning 6 belgisi. Toggle/satr ofsetlari ham shu magic numberga tayanadi. Bu editing va style offsetlarni murakkablashtiradi; checkbox state paragraph modelidan ajralmagan. Saqlash/yuklash ishlashi JSON v2 orqali kodda mavjud (`ContentBlock.kt:416-500`), lekin resilient model emas.

### TB-05 — O'rta — toolbar kengaytirish uchun typed command/state kontrakti yo'q
**Fayl:** `NoteEditScreen.kt:713-731`; `NoteEditViewModel.kt:830-1133`.

Toolbar callbacklar alohida lambda va ViewModel public functionlari sifatida tarqalgan. Active state screen ichida qayta hisoblanadi (`316-441`). Bu yangi action qo'shganda UI va VM parallel o'zgartirilishini talab qiladi; sealed `EditorCommand`/`EditorSelectionState` yo'q.

## C. Ranglar

### R-01 — Yuqori — color picker 10 swatch beradi, ViewModel 0..7 ga clamp qiladi
**Fayl:** `NoteEditScreen.kt:778-795`; `NoteEditViewModel.kt:1127-1134`; renderer `Theme.kt:306-319`.

UI `Teal=8`, `Clay=9`ni taklif qiladi, ammo `setBackgroundColorIndex` `coerceIn(0,7)` qiladi. Teal/Clay tanlash Red (7) bo'lib saqlanadi. Takrorlash: Teal yoki Clay tanlang, yopib qayta oching.

### R-02 — O'rta — rang index semantikasi nomlari va palette talabiga mos emas
**Fayl:** `Color.kt:108-128`; picker `NoteEditScreen.kt:781-790`.

Hozir index ketma-ketligi Default/Yellow/Blue/Pink/Green/Orange/Purple/Red/Teal/Clay; Keep talabi bo'yicha Red, Orange, Yellow, Green, Teal, Blue, Dark blue, Purple, Pink, Brown, Gray to'liq emas. `Clay` rangida Gray darkga Brown (`#3C2A21`) berilgan. `backgroundColor` faqat `Int` (`NoteEntity.kt:69`), meaning/version yo'q.

### R-03 — O'rta — dynamic color note text va note fill juftligini boshqarmaydi
**Fayl:** `Theme.kt:251-258,323-326`.

Card matni har lightda `#202124`, darkda `#E8EAED`; user dynamic M3 color faollashtirsa ham note-card-specific on-color o'zgarmaydi. Hozirgi literal filllar bilan kontrast ko'p hollarda yetarli (04 fayl), lekin tokenlangan juftlik kafolati yo'q.

## D. Qo'shimcha topilmalar

### D-01 — Yuqori — `StyleRange.attributeAt`/querylar birinchi overlapping range-ni oladi, renderer union/last-color ishlatadi
**Fayl:** `StyleRange.kt:121-168,201-216,233-243,269-305`.

Renderer bold/underline uchun barcha matching range union qiladi, color uchun last matchingni oladi; active-state/toggle esa `find` bilan birinchi range-ni oladi. Overlap bo'lsa toolbar holati renderdan farq qiladi.

### D-02 — O'rta — title undo snapshot har keystroke, IME composition va debounce bilan guruhlanmagan
**Fayl:** `NoteEditViewModel.kt:429-434,201-210`.

`onTitleChange` text o'zgarsa, composition holatini tekshirmay snapshot push qiladi; 50 snapshot juda tez to'ladi. Body `onBlockValueChange` compositionda snapshotni skip qiladi (`443-476`), title esa boshqa siyosatga ega.

### D-03 — O'rta — dark/dynamic default bo'yicha kod va comment zid
**Fayl:** `Theme.kt:29-32,140-146,200-225`.

Comment default system/dynamic=false deydi; real default `LIGHT` va `dynamicColorEnabled=true`; birinchi migration prefga false yozib, so'ng 224da true qiladi. Bu deterministik boshlang'ich theme emas.

### D-04 — Past — `fontWeight` va `textSize` Room ustunlari rich textning haqiqiy manbasi emas
**Fayl:** `NoteEntity.kt:70-71`; `NoteEditViewModel.kt:1255-1256,1285-1286`.

Ular faqat last text blockdan derived qilib yoziladi, read path esa `content`ni parse qiladi (`270`). Shu redundant scalarlar noto'g'ri preview/migratsiya qaroriga olib kelishi mumkin.
