# Tuzatish rejasi — buildni buzmaydigan yo'l

Bu hujjat kodni o'zgartirmaydi. Har `ESKI` parcha audit vaqtidagi aniq source satrlaridan olingan. Tanlangan arxitektura `05_RICH_TEXT_ARXITEKTURA.md`: **har TextBlock bitta BasicTextField**, paragraphlar `\n`-chegarali runlar. Bu cross-paragraph selection/copy/select-allni nativ saqlaydi.

## 0. Oraliq compatibility qatlami (T-04, TB-04)

Avval yangi `RichDocumentV3.kt` qo'shilsin, lekin `ContentBlock` va mavjud `BlocksEditor` o'chirilmasin. `RichDocumentAdapter.fromLegacy(blocks)` va `toLegacy(document)` vaqtincha v3 modelni eski UIga beradi. Shuning uchun model commitidan keyin ilova build bo'ladi. Yangi `RichTextBlockEditor` tayyor bo'lgach adapter faqat old data parseri bo'lib qoladi.

## T-01 — title selection

**Eski, aynan:** `notes/app/src/main/java/com/example/notes/ui/edit/NoteEditScreen.kt:652-657`

```kotlin
BasicTextField(
    value = TextFieldValue(
        text = state.title,
        selection = TextRange(state.title.length)
    ),
    onValueChange = viewModel::onTitleChange,
```

**O'chiriladi:** yuqoridagi `value = TextFieldValue(...)`. **Qo'shiladi:** `NoteEditState`ga `titleSelection: TextRange = TextRange.Zero`; `onTitleChange`da selectionni ham copy qilish; UI:

```kotlin
val titleValue = TextFieldValue(state.title, state.titleSelection)
BasicTextField(value = titleValue, onValueChange = viewModel::onTitleChange /* existing args */)
```

**Ta'sir:** `NoteEditViewModel.kt:77-97,429-439`, editor state test. **Test:** caret 2-pozitsiyada edit va range select; **qo'lda:** title o'rtasida Uzbek IME bilan yozish, rotation.

## T-02/D-01 — canonical span split/merge

**Eski, aynan:** `StyleRange.kt:314-315,356-357`

```kotlin
val outside = ranges.filterNot { it.end > s && it.start < e }
return mergeRanges(outside + replacement)
```

Bu kod `toggleAttribute` (`257-315`) va `setColor` (`322-357`)dan o'chiriladi. Quyidagi file `StyleRanges`ga importlarsiz (u `StyleRange` bilan bir package) to'liq qo'shiladi va ikki eski function uning `mutate` metodiga delegate qilinadi:

```kotlin
private fun StyleRange.slice(from: Int, to: Int) = copy(start = from, end = to)
private fun List<StyleRange>.canonical(): List<StyleRange> {
  val points = flatMap { listOf(it.start, it.end) }.distinct().sorted()
  if (points.size < 2) return filter { it.start < it.end }
  val out = mutableListOf<StyleRange>()
  for (i in 0 until points.lastIndex) {
    val a = points[i]; val b = points[i + 1]
    val covers = filter { it.start <= a && it.end >= b }
    if (covers.isEmpty()) continue
    val lastColor = covers.lastOrNull { it.colorIndex != 0 }?.colorIndex ?: 0
    val run = StyleRange(a, b, covers.any { it.bold }, covers.any { it.italic }, covers.any { it.underline }, covers.any { it.strikethrough }, lastColor)
    if (out.lastOrNull()?.let { it.end == run.start && it.copy(end = run.end) == run } == true) out[out.lastIndex] = out.last().copy(end = run.end) else out += run
  }
  return out
}
private fun mutate(ranges: List<StyleRange>, selection: TextRange, f: (StyleRange) -> StyleRange): List<StyleRange> {
  val s = selection.min; val e = selection.max
  return ranges.canonical().flatMap { r ->
    when { r.end <= s || r.start >= e -> listOf(r)
      else -> buildList { if (r.start < s) add(r.slice(r.start, s)); add(f(r.slice(maxOf(r.start,s), minOf(r.end,e)))); if (r.end > e) add(r.slice(e,r.end)) }
    }
  }.canonical()
}
```

**Test:** 0..10 bold, 3..5 underline; old boldning 0..3/5..10 qismi qoladi; overlap active state renderer bilan teng. **Qo'lda:** bold/underline/strike kombinatsiyasi, undo/redo. **Ta'sir:** `StyleRange.kt`, `NoteEditViewModel.kt:830-916`, `NoteEditScreen.kt:350-441`.

## T-03 — V2.6 decoderni o'chirmasdan tuzatish

**Eski, aynan:** `notes/app/src/main/java/com/example/notes/ui/edit/ContentBlock.kt:629-650`da `val parts = segment.split(':')` va `if (parts.size != 5) continue`.

**Yangi to'liq decode:**

```kotlin
fun decode(payload: String): List<StyleRange> = payload.split(RANGE_SEP).mapNotNull { segment ->
  val pair = segment.split(':', limit = 2); if (pair.size != 2) return@mapNotNull null
  val bounds = pair[0].split(FIELD_SEP); val flags = pair[1].split(FLAG_SEP)
  if (bounds.size != 2 || flags.size !in 4..5) return@mapNotNull null
  val start = bounds[0].toIntOrNull() ?: return@mapNotNull null
  val end = bounds[1].toIntOrNull() ?: return@mapNotNull null
  if (start < 0 || end < start) return@mapNotNull null
  val b = flags[0] == "b"; val i = flags[1] == "i"; val u = flags[2] == "u"
  val strike = flags.getOrNull(3) == "s"
  val colorFlag = flags.last(); val color = colorFlag.removePrefix("c").toIntOrNull()?.coerceIn(0,5) ?: 0
  StyleRange(start,end,b,i,u,strike,color).takeUnless { it.start == it.end && !it.isStyled }
}
```

`encode`ni 5 tokenli `b|i|u|s|cN`ga yangilang; decoder yuqoridagi `4..5` bilan eski `b|i|u|cN`ni ham o'qiydi. **Test:** old va yangi payload decode, serialize→decode equivalence. **Ta'sir:** `ContentBlock.kt:606-652,295-309,349-356`.

## T-04/T-05/TB-04 — V3 serializer va adapter

**Eski:** `ContentBlock.kt:389-500` (`JsonBlockText`, `JsonBlockImage`, v2 write/read) o'chirilmaydi; `parseBlocksFromJsonV2` legacy reader sifatida qoladi. `serializeBlocksToContent:368-371`dagi v2 writer v3 writerga almashtiriladi.

**Yangi file, importlar bilan:**

```kotlin
package com.example.notes.ui.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
@Serializable enum class ParagraphKind { BODY, H1, H2, CHECKLIST }
@Serializable data class ParagraphRun(val start:Int,val end:Int,val kind:ParagraphKind=ParagraphKind.BODY,val checked:Boolean=false)
@Serializable data class CharSpan(val start:Int,val end:Int,val bold:Boolean=false,val italic:Boolean=false,val underline:Boolean=false,val strikethrough:Boolean=false,val colorKey:String?=null)
@Serializable sealed class V3Block { @Serializable @SerialName("text") data class Text(val text:String,val paragraphs:List<ParagraphRun>,val spans:List<CharSpan>):V3Block(); @Serializable @SerialName("img") data class Image(val uri:String):V3Block() }
@Serializable data class RichDocumentV3(val v:Int=3,val blocks:List<V3Block>)
private val v3Json=Json { ignoreUnknownKeys=true; encodeDefaults=true; classDiscriminator="t" }
fun serializeV3(d:RichDocumentV3)=v3Json.encodeToString(d)
fun parseV3(raw:String):RichDocumentV3?=runCatching { v3Json.decodeFromString<RichDocumentV3>(raw).takeIf { it.v==3 } }.getOrNull()
```

`parseContent` tartibi `parseV3 ?: adapter.fromV2(parseBlocksFromJsonV2(raw)) ?: adapter.fromLegacy(parseBlocksFromLegacy(raw))`. Har save v3 yozadi; v2 readerlar bir release saqlanadi. Renderer sweep bilan annotated runlarni bir marta tuzadi; `StyleRange.kt:121-168`dagi per-char `filter` o'chiriladi. **Test:** v3 roundtrip, v2/V2.6/plain/image conversion, 5000 char benchmark. **Qo'lda:** old note open/save/reopen/sync, image+format preservation.

## TB-01/TB-02/TB-03 — checklist reducer

**Eski:** `NoteEditScreen.kt:1494-1536,1613-1621,1656-1664`dagi per-row split/merge va `- [ ]` serialization o'chiriladi, chunki B variantida qator fieldlari yo'q.

**Yangi reducer (import kerak emas):**

```kotlin
fun onChecklistEnter(p: ParagraphRun, cursor:Int): Pair<ParagraphRun,ParagraphRun> =
 if (p.kind==ParagraphKind.CHECKLIST && p.start==p.end) p.copy(kind=ParagraphKind.BODY) to p.copy(start=p.end,end=p.end,kind=ParagraphKind.BODY)
 else p.copy(end=cursor) to p.copy(start=cursor, checked=false)
fun reorderChecked(runs:List<ParagraphRun>) = runs.filter { !it.checked } + runs.filter { it.checked }
```

Real reducer textning split qismi, barcha keyingi run offsetlari va spansni `shiftOnEdit` bilan yangilaydi; empty CHECKLIST Enter/Backspace `BODY`ga aylantiradi; checked change `moveCheckedToBottom` preference true bo'lsa `reorderChecked`ni qo'llaydi. **Test:** Enter, empty Enter exit, first empty Backspace exit, merge, checked persistence/reorder. **Qo'lda:** checklist save/reopen, Undo/Redo, sync.

## TB-05/T-06/D-02 — command resolver va saved state

**Eski:** callbacklar `NoteEditScreen.kt:713-731`; public mutations `NoteEditViewModel.kt:830-1133`. **Yangi:**

```kotlin
sealed interface EditorCommand { data object ToggleBold:EditorCommand; data object ToggleUnderline:EditorCommand; data object ToggleStrike:EditorCommand; data class SetParagraph(val kind:ParagraphKind):EditorCommand; data object ToggleChecklist:EditorCommand }
data class EditorToolbarState(val bold:Boolean,val underline:Boolean,val strike:Boolean,val paragraph:ParagraphKind,val checklist:Boolean)
fun dispatch(command:EditorCommand) { /* single selected TextBlock + TextRange reducer */ }
```

Resolver state va mutationni bitta canonical run modelidan chiqaradi. `SavedStateHandle`ga note id, title selection, block id/selection saqlanadi; IME composition diskka yozilmaydi. Title undo body kabi composition tugagach group qilinadi. **Test:** state recreation, 51 keystroke undo grouping, toolbar active/mixed. **Qo'lda:** rotation/process kill, keyboard dock.

## R-01/R-02/R-03 — Room v12 va palette

**Eski:** `NoteEditViewModel.kt:1127-1134`dagi `coerceIn(0,7)` va int picker oqimi. **Yangi migration:**

```kotlin
private val MIGRATION_11_12 = object: Migration(11,12) {
 override fun migrate(db:SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE notes ADD COLUMN backgroundColorKey TEXT NOT NULL DEFAULT 'default'")
  db.execSQL("UPDATE notes SET backgroundColorKey=CASE backgroundColor WHEN 1 THEN 'yellow' WHEN 2 THEN 'blue' WHEN 3 THEN 'pink' WHEN 4 THEN 'green' WHEN 5 THEN 'orange' WHEN 6 THEN 'purple' WHEN 7 THEN 'red' WHEN 8 THEN 'teal' WHEN 9 THEN 'gray' ELSE 'default' END")
 }
}
```

`NoteEntity`ga key, pickerga `NoteColorKey`, rendererga `NotePalette` (`04`dagi to'liq kod) qo'shiladi. **Test:** 0..9 migration, 12 swatch, contrast. **Qo'lda:** Teal/Gray select/reopen/dark/light/dynamic. D1 migration va Android sync DTOga `background_color_key` qo'shiladi.

## D-03/D-04

`Theme.kt:140-146,200-225`da default/comment bitta `SYSTEM`, dynamic false policyga tekislanadi. `NoteEntity.kt:70-71`dagi derived `fontWeight/textSize` v3 rolloutdan keyin v13da drop qilinadi, v12da read/write qilinmaydi. Unit migration va release upgrade test majburiy.
