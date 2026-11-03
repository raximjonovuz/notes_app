package com.example.notes.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notes.BuildConfig

/**
 * Feature flag for the unified single-field RichTextBlockEditor.
 * When enabled, uses bitta-field BasicTextField with paragraph runs and char spans.
 * Defaults to true in debug builds, false in release builds.
 */
var USE_RICH_TEXT_BLOCK_EDITOR = BuildConfig.DEBUG

/**
 * Builds VisualTransformation efficiently in a single sweep for v3 text block.
 */
fun v3VisualTransformation(
    text: String,
    paragraphs: List<ParagraphRun>,
    spans: List<CharSpan>,
    baseTextColor: Color
): VisualTransformation = VisualTransformation { raw ->
    val rawStr = raw.text
    val annotated = buildAnnotatedString {
        append(rawStr)

        // 1. Apply paragraph heading font sizes
        for (p in paragraphs) {
            val s = p.start.coerceIn(0, rawStr.length)
            val e = p.end.coerceIn(0, rawStr.length)
            if (s >= e) continue
            when (p.kind) {
                ParagraphKind.H1 -> addStyle(
                    SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    s, e
                )
                ParagraphKind.H2 -> addStyle(
                    SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    s, e
                )
                ParagraphKind.BODY, ParagraphKind.CHECKLIST -> addStyle(
                    SpanStyle(fontSize = 16.sp),
                    s, e
                )
            }
        }

        // 2. Apply char spans
        for (s in spans) {
            val start = s.start.coerceIn(0, rawStr.length)
            val end = s.end.coerceIn(0, rawStr.length)
            if (start >= end) continue

            val decorations = buildList {
                if (s.underline) add(TextDecoration.Underline)
                if (s.strikethrough) add(TextDecoration.LineThrough)
            }
            val spanStyle = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
                color = s.colorKey?.let { colorForKey(it) } ?: Color.Unspecified
            )
            addStyle(spanStyle, start, end)
        }
    }
    TransformedText(annotated, OffsetMapping.Identity)
}

private fun colorForKey(key: String): Color = when (key.lowercase()) {
    "yellow" -> Color(0xFFF9A825)
    "blue" -> Color(0xFF1565C0)
    "pink" -> Color(0xFFAD1457)
    "green" -> Color(0xFF2E7D32)
    "orange" -> Color(0xFFEF6C00)
    "purple" -> Color(0xFF6A1B9A)
    "red" -> Color(0xFFC62828)
    "teal" -> Color(0xFF00695C)
    "gray" -> Color(0xFF37474F)
    else -> Color.Unspecified
}

@Composable
fun RichTextBlockEditor(
    value: TextFieldValue,
    paragraphs: List<ParagraphRun>,
    spans: List<CharSpan>,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onBlockFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val visualTransformation = remember(value.text, paragraphs, spans, textColor) {
        v3VisualTransformation(value.text, paragraphs, spans, textColor)
    }

    var localValue by remember(value.text) { mutableStateOf(value) }

    LaunchedEffect(value) {
        if (value.text != localValue.text || value.selection != localValue.selection) {
            localValue = value
        }
    }

    BasicTextField(
        value = localValue,
        onValueChange = { newValue ->
            val oldSel = localValue.selection
            val newSel = newValue.selection
            val selectionOnly = newValue.text == localValue.text && newSel != oldSel
            localValue = newValue
            onValueChange(newValue)
            if (selectionOnly) {
                onBlockFocused()
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onBlockFocused()
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = textColor,
            textAlign = TextAlign.Start
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.Sentences
        ),
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (localValue.text.isEmpty()) {
                    Text(
                        text = "Type here...",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Start
                        )
                    )
                }
                inner()
            }
        }
    )
}
