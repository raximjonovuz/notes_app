# Rang palitrasi va WCAG hisobi

## Hozirgi koddagi holat

Rang konstanta `Color.kt:108-128`, renderer `Theme.kt:306-326`, picker `NoteEditScreen.kt:778-795`da. Dark mode mavjud (`Theme.kt:237-287`). R-01 sabab index 8–9 hozir saqlanmaydi.

WCAG 2.1 AA normal text maqsadi 4.5:1 ([W3C](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html)). Hisob sRGB relative luminance `(L1+.05)/(L2+.05)` bilan, haqiqiy koddagi `onColor` (`#202124` light, `#E8EAED` dark) uchun 2 xonali yaxlitlangan.

| Hozirgi index | Light fill / on | nisbat | Dark fill / on | nisbat |
|---|---:|---:|---:|---:|
| 0 Default | FFFFFF / 202124 | 16.1 | 202124 / E8EAED | 13.9 |
| 1 Yellow | FFF475 / 202124 | 14.4 | 534E23 / E8EAED | 7.2 |
| 2 Blue | CBF0F8 / 202124 | 13.4 | 072731 / E8EAED | 13.4 |
| 3 Pink | FDCFE8 / 202124 | 12.6 | 42273B / E8EAED | 10.1 |
| 4 Green | CCFF90 / 202124 | 14.4 | 16352E / E8EAED | 11.6 |
| 5 Orange | FBBC04 / 202124 | 9.8 | 6A4F2A / E8EAED | 6.8 |
| 6 Purple | D7AEFB / 202124 | 9.9 | 321F36 / E8EAED | 13.2 |
| 7 Red | F28B82 / 202124 | 7.7 | 77172E / E8EAED | 8.5 |
| 8 Teal | A7FFEB / 202124 | 14.6 | 0C3B3E / E8EAED | 10.9 |
| 9 Gray/Clay | E8EAED / 202124 | 13.9 | 3C2A21 / E8EAED | 10.3 |

Demak muammo kontrastdan ko'ra index bugi, palette semantikasi va token arxitekturasidadir.

## Tavsiya: versionlangan 12 rangli Keep-uslubidagi palette

`backgroundColor: Int` indeksini saqlashni davom ettirmang: server/local migration uchun stable `colorKey: String` (`default`, `red`...) ishlating. Light/dark fill va o'zgarmas on-color quyidagicha.

| key | Light / on | light nisbat | Dark / on | dark nisbat |
|---|---|---:|---|---:|
| default | #FFFFFF / #202124 | 16.10:1 | #202124 / #E8EAED | 13.36:1 |
| red | #F28B82 / #202124 | 6.74:1 | #77172E / #E8EAED | 8.95:1 |
| orange | #FBBE83 / #202124 | 9.81:1 | #6D4C2B / #E8EAED | 6.41:1 |
| yellow | #FFF475 / #202124 | 14.14:1 | #534E23 / #E8EAED | 7.02:1 |
| green | #CCFB90 / #202124 | 13.62:1 | #1E4620 / #E8EAED | 8.93:1 |
| teal | #A7FFEB / #202124 | 13.91:1 | #0F464A / #E8EAED | 8.73:1 |
| blue | #CBF0F8 / #202124 | 13.31:1 | #09333F / #E8EAED | 11.19:1 |
| dark_blue | **#AFCBFA / #202124** | 9.77:1 | **#1B3A6F / #E8EAED** | 9.26:1 |
| purple | #D7AEFB / #202124 | 8.69:1 | #4A2A62 / #E8EAED | 9.69:1 |
| pink | #FDCFE8 / #202124 | 11.70:1 | #5A2E4A / #E8EAED | 9.05:1 |
| brown | #E6C9A8 / #202124 | 10.18:1 | #513A2D / #E8EAED | 8.74:1 |
| gray | #E8EAED / #202124 | 13.36:1 | #3C4043 / #E8EAED | 8.68:1 |

Hisob sRGB relative-luminance formulasi `(L1 + 0.05) / (L2 + 0.05)` bilan olindi. Barcha light juftlik `#202124`, dark juftlik `#E8EAED` bilan 4.5:1dan yuqori; implementationda shu float hisobni unit-test bilan assert qiling.

## Tayyor token kodi (yangi `NoteColorPalette.kt`)

```kotlin
enum class NoteColorKey { DEFAULT, RED, ORANGE, YELLOW, GREEN, TEAL, BLUE, DARK_BLUE, PURPLE, PINK, BROWN, GRAY }
data class NoteColorToken(val key: NoteColorKey, val light: Color, val dark: Color)
val NotePalette = listOf(
  NoteColorToken(NoteColorKey.DEFAULT, Color(0xFFFFFFFF), Color(0xFF202124)),
  NoteColorToken(NoteColorKey.RED, Color(0xFFF28B82), Color(0xFF77172E)),
  NoteColorToken(NoteColorKey.ORANGE, Color(0xFFFBBE83), Color(0xFF6D4C2B)),
  NoteColorToken(NoteColorKey.YELLOW, Color(0xFFFFF475), Color(0xFF534E23)),
  NoteColorToken(NoteColorKey.GREEN, Color(0xFFCCFB90), Color(0xFF1E4620)),
  NoteColorToken(NoteColorKey.TEAL, Color(0xFFA7FFEB), Color(0xFF0F464A)),
  NoteColorToken(NoteColorKey.BLUE, Color(0xFFCBF0F8), Color(0xFF09333F)),
  NoteColorToken(NoteColorKey.DARK_BLUE, Color(0xFFAFCBFA), Color(0xFF1B3A6F)),
  NoteColorToken(NoteColorKey.PURPLE, Color(0xFFD7AEFB), Color(0xFF4A2A62)),
  NoteColorToken(NoteColorKey.PINK, Color(0xFFFDCFE8), Color(0xFF5A2E4A)),
  NoteColorToken(NoteColorKey.BROWN, Color(0xFFE6C9A8), Color(0xFF513A2D)),
  NoteColorToken(NoteColorKey.GRAY, Color(0xFFE8EAED), Color(0xFF3C4043))
)
fun noteOnColor(isDark: Boolean) = if (isDark) Color(0xFFE8EAED) else Color(0xFF202124)
```

## Migration jadvali

| Eski int | Yangi key |
|---:|---|
| 0 | default |
| 1 | yellow |
| 2 | blue |
| 3 | pink |
| 4 | green |
| 5 | orange |
| 6 | purple |
| 7 | red |
| 8 | teal |
| 9 | gray |

Room v12: `backgroundColorKey TEXT NOT NULL DEFAULT 'default'`; SQL `UPDATE notes SET backgroundColorKey = CASE backgroundColor ... END`; bir release davomida old intni faqat backward compatibility uchun o'qing, keyingi breaking release da olib tashlang. Backend D1 `notes`ga ham `background_color_key TEXT NOT NULL DEFAULT 'default'` qo'shilib Android push/pull DTOga kiritilishi kerak.
