# Rich text arxitekturasi: tanlov va asos

## Hozirgi kodning haqiqiy editor topologiyasi

`BlocksEditor` har `ContentBlock`ni `blocks.forEachIndexed` bilan alohida render qiladi (`notes/app/src/main/java/com/example/notes/ui/edit/NoteEditScreen.kt:1043-1117`). Oddiy `TextBlock` **bitta** `TextBlockEditor` va **bitta** `BasicTextField`dir (`1139-1219`); block ichidagi barcha `\n` ham o'sha bitta inputda. Bullet/number `ListBlockEditor` ham **bitta** `BasicTextField` (`1229-1333`). Faqat `isChecklist=true` bo'lsa `ChecklistBlockEditor` matnni satrlarga ajratadi (`1356-1419`) va **har satr uchun alohida** `ChecklistLineRow`/`BasicTextField` yaratadi (`1451-1543,1546-1687`). Image tabiatan alohida `ImageBlock`.

Hozir `ContentBlock.TextBlock` bitta `String`, `styleRanges`, block-default va line-index xaritalardan iborat (`notes/app/src/main/java/com/example/notes/ui/edit/ContentBlock.kt:60-90`); storage v2 JSON (`368-500`). Shuning uchun oddiy textda bitta block ichida nativ selection/copy/select-all ishlaydi, checklistda qatorlararo nativ selection yo'q.

## Variantlar

| Variant | Yutuq | Narx |
|---|---|---|
| A — paragraph = alohida `BasicTextField` | paragraph va checkbox row layouti soddaroq; local state aniq | Android selection bir field doirasida. Cross-paragraph drag, Select all, Copy/Cut/Paste, focus/IME/accessibility uchun custom global selection va clipboard protokoli yozilishi kerak. Bu yuqori riskli, ayniqsa clipboardda styles saqlanishida. |
| B — `TextBlock` = bitta `BasicTextField`; paragraph `\n`-chegarali range | hozirgi normal editorga mos; native selection/copy/select-all/paste/IME bir TextBlock ichida saqlanadi; char span va paragraph style bitta global offset koordinatasida | checklist checkboxi textga token qo'shmasdan `TextLayoutResult` line offsetida overlay/hit-test bilan chiziladi; image bloklari orasidagi selection hozirgidek tabiatan uziladi. |

## Qaror: B

Variant **B** tanlanadi. TextBlock bitta field bo'lib qoladi. Domain model:

```kotlin
@Serializable enum class ParagraphKind { BODY, H1, H2, CHECKLIST }
@Serializable data class ParagraphRun(val start: Int, val end: Int, val kind: ParagraphKind, val checked: Boolean = false)
@Serializable data class CharSpan(val start: Int, val end: Int, val bold: Boolean=false, val underline: Boolean=false, val strikethrough: Boolean=false, val italic: Boolean=false, val colorKey: String?=null)
```

`ParagraphRun`lar `\n` bilan ajralgan paragraphlarni qoplaydi, `CharSpan` esa belgi intervalini qoplaydi. Selection `TextRange`ning kesishgan paragraphlari H1/H2/BODY commandini oladi; span command faqat selected char intervalini oladi. Shu sabab bir necha paragraphni native Select all/Copy/Paste qilish mumkin.

Checklist ham bitta fieldda: `checked` `ParagraphRun`ga tegishli; `- [ ]`/`- [x]` `text`ga hech qachon yozilmaydi. Renderer `TextLayoutResult`dan checklist paragraph start line-ini olib overlay checkbox chizadi; tap reducerga `ToggleChecked(paragraphIndex)` yuboradi. Enter/Backspace `TextFieldValue` diffidan reducerga yuboriladi. Checked sort yoqilgan bo'lsa reducer paragraph matni + paragraph metadata + kesilgan spanlarni stable partition bilan pastga ko'chiradi va yangi selection qaytaradi.

## V3 format va migration

`content` JSON: `{"v":3,"blocks":[{"t":"text","text":"H\nP","paragraphs":[...],"spans":[...]},{"t":"img","uri":"..."}]}`. O'qish tartibi v3 → v2 JSON → V2.6 header → legacy/plain. V2 line maps har satrga `ParagraphRun` hosil qiladi; V2 checklist prefixlari strip qilinib `checked`ga o'tadi; eski spanlar ayni global offsetda ko'chadi. `ImageBlock.uri`, bold, underline, strikethrough saqlanadi. `AnnotatedString` storage emas, render projectiondir; Android docs `SpanStyleRange` va `ParagraphStyleRange`ni shu ajratish uchun beradi ([Android docs](https://developer.android.com/develop/ui/compose/text/style-text)).
