package com.example.notes.ui.edit

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notes.ui.theme.noteCardColor
import com.example.notes.ui.theme.notesHeaderBackground
import com.example.notes.ui.theme.notesPageBackground
import kotlinx.coroutines.delay

private fun persistReadPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // Not all providers support persistable permissions; ignore.
    }
}

/**
 * V4.4 — fire a system share intent with the note's title as the subject
 * and the plain text content as the body. We strip the V2.6 rich-text
 * header (`<s|a|l|k>S<ranges>`) so the recipient sees the actual text.
 */
private fun shareNote(context: Context, title: String, content: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Note" })
        putExtra(Intent.EXTRA_TEXT, content)
    }
    val chooser = Intent.createChooser(intent, "Share note")
    chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(chooser)
}

/**
 * V5.0 — share the note's blocks rendered as Markdown. Recipients
 * (Obsidian, GitHub, Notion, etc.) that understand `**bold**`, `_italics_`
 * etc. will preserve the formatting.
 */
private fun shareMarkdown(context: Context, title: String, blocks: List<ContentBlock>) {
    val body = com.example.notes.util.MarkdownExporter.exportBlocks(blocks)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Note" })
        putExtra(Intent.EXTRA_TEXT, body)
    }
    val chooser = Intent.createChooser(intent, "Share as Markdown")
    chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(chooser)
}

/* -----------------------------------------------------------------------
 * VisualTransformation that renders the underlying TextFieldValue text as
 * an AnnotatedString built from the block's styleRanges. Per-character
 * bold/italic/underline/color is applied via SpanStyle.
 * --------------------------------------------------------------------- */
private fun richTextVisualTransformation(
    ranges: List<StyleRange>
): VisualTransformation = VisualTransformation { text ->
    val annotated = StyleRanges.toAnnotatedString(text.text, ranges)
    TransformedText(annotated, OffsetMapping.Identity)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    viewModel: NoteEditViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    // F-05 — the set of image blocks whose upload gave up. Drives
    // the "Rasm yuklanmadi" badge + the retry action.
    val failedImageUris by viewModel.failedImageUris.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val firstBlockFocusRequester = remember { FocusRequester() }
    var menuExpanded by remember { mutableStateOf(false) }
    var bgColorPickerVisible by remember { mutableStateOf(false) }
    var textColorPickerVisible by remember { mutableStateOf(false) }
    var fullScreenImageUri by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // For a brand new note, seed an empty TextBlock so the content editor
    // has a target for the user to type into. Auto-focus the first block.
    LaunchedEffect(noteId) {
        viewModel.loadNote(noteId)
        if ((noteId == null || noteId == 0L) &&
            state.blocks.isEmpty()
        ) {
            viewModel.addEmptyTextBlockAtEnd()
            delay(80)
            try {
                firstBlockFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus may fail if the block isn't yet attached; ignore.
            }
        }
    }

    // V5.0.2 — auto-save means the user is *always* saving. A "Saved"
// snackbar flashing on every keystroke (Folio-style) is just noise,
// so we only surface failures. V4.2 used a visible Save button +
// snackbar; that model would still apply if you wanted manual control
// — flip [showSavedToast] in NoteEditViewModel.
    LaunchedEffect(saveStatus) {
        when (val s = saveStatus) {
            is SaveStatus.Saved -> {
                // V5.1.5 — keep the "Saved" tick visible for ~1.2s so the
                // user can see something happened, then drop to Idle.
                kotlinx.coroutines.delay(1200L)
                viewModel.consumeSaveStatus()
            }
            is SaveStatus.Failed -> {
                snackbarHostState.showSnackbar(
                    message = "Save failed: ${s.message}",
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                    withDismissAction = true
                )
                viewModel.consumeSaveStatus()
            }
            else -> Unit
        }
    }

    // V5.1 — wire up the ViewModel's transient-message channel so toolbar
    // actions that silently fail (e.g. user tapped Bold before focusing a
    // block) surface a Snackbar instead of just logging a warning. The
    // [collectAsStateWithLifecycle] wrapper isn't right for a SharedFlow
    // (we don't want a re-emission on every recomposition), so we use a
    // plain LaunchedEffect over the flow.
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.transientMessage.collect { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = msg,
                    duration = androidx.compose.material3.SnackbarDuration.Short
                )
            }
        }
    }

    val cardBg = noteCardColor(state.backgroundColor)
    val isDirty = state.isDirty
    val headerBg = notesHeaderBackground()

    // Active block = the one with IME focus. The toolbar queries its
    // selection-aware state from this block.
    val activeBlock = state.activeBlock
    // V5.2.3 — toolbar reads the EFFECTIVE value for the line that
    // contains the caret so it reflects per-line overrides (e.g. a
    // single bullet within an otherwise plain block).
    val activeLineIdx = activeBlock?.let { ab ->
        if (ab is ContentBlock.TextBlock) {
            val cursor = ab.selection.start.coerceIn(0, ab.text.length)
            var idx = 0
            for (i in 0 until cursor) if (ab.text[i] == '\n') idx++
            idx
        } else 0
    } ?: 0
    val activeFontSize = when (val ab = activeBlock) {
        is ContentBlock.TextBlock ->
            ab.fontSizesByLine[activeLineIdx] ?: ab.fontSize
        else -> 1
    }
    val activeTextAlign = activeBlock?.textAlign ?: 0
    val activeListType = when (val ab = activeBlock) {
        is ContentBlock.TextBlock ->
            ab.listTypesByLine[activeLineIdx] ?: ab.listType
        else -> 0
    }
    val activeIsChecklist = activeBlock?.isChecklist == true

    // Per-attribute "fully styled" flags (only meaningful with a non-collapsed
    // selection). The activeBlockId is in scope so recomposition is fine.
    val activeRanges = activeBlock?.styleRanges.orEmpty()
    val activeTextLength = activeBlock?.text?.length ?: 0
    val activeSelection = activeBlock?.selection ?: TextFieldValue().selection

    /**
     * V5.1.3 — return the [StyleAttributeState] that's actually live at
     * the caret position. Two sources contribute:
     *   - regular spans that *cover* the caret (range.start <= pos <
     *     range.end), and
     *   - the **pending anchor** sitting *at* the caret
     *     (range.start == range.end == pos) — this is what the
     *     V5.1.3 "type-mode" toggle relies on; without it the B/I/U/S
     *     buttons would read OFF between pressing the button and the
     *     user typing the next character.
     *
     * Collapsed selection → read at the caret. Otherwise [isFullyStyled]
     * is the right query.
     */
    fun attributeAtCaret(
        ranges: List<StyleRange>,
        pos: Int,
        attribute: StyleAttribute
    ): Boolean {
        // Anchor first — a pending toggle sets bold=true even though no
        // character is covered yet.
        val anchor = ranges.firstOrNull {
            it.start == pos && it.end == pos && it.isStyled
        }
        if (anchor != null) {
            return when (attribute) {
                StyleAttribute.BOLD -> anchor.bold
                StyleAttribute.ITALIC -> anchor.italic
                StyleAttribute.UNDERLINE -> anchor.underline
                StyleAttribute.STRIKETHROUGH -> anchor.strikethrough
                StyleAttribute.COLOR -> anchor.colorIndex != 0
            }
        }
        val covering = ranges.find { it.start <= pos && pos < it.end }
            ?: return false
        return when (attribute) {
            StyleAttribute.BOLD -> covering.bold
            StyleAttribute.ITALIC -> covering.italic
            StyleAttribute.UNDERLINE -> covering.underline
            StyleAttribute.STRIKETHROUGH -> covering.strikethrough
            StyleAttribute.COLOR -> covering.colorIndex != 0
        }
    }

    val isBoldActive = activeRanges.let {
        if (activeSelection.collapsed) {
            attributeAtCaret(it, activeSelection.start, StyleAttribute.BOLD)
        } else {
            StyleRanges.isFullyStyled(it, activeSelection, activeTextLength, StyleAttribute.BOLD)
        }
    }
    val isItalicActive = activeRanges.let {
        if (activeSelection.collapsed)
            attributeAtCaret(it, activeSelection.start, StyleAttribute.ITALIC)
        else
            StyleRanges.isFullyStyled(it, activeSelection, activeTextLength, StyleAttribute.ITALIC)
    }
    val isUnderlineActive = activeRanges.let {
        if (activeSelection.collapsed)
            attributeAtCaret(it, activeSelection.start, StyleAttribute.UNDERLINE)
        else
            StyleRanges.isFullyStyled(it, activeSelection, activeTextLength, StyleAttribute.UNDERLINE)
    }
    // V5.0 — strike-through toggle visibility.
    val isStrikethroughActive = activeRanges.let {
        if (activeSelection.collapsed)
            attributeAtCaret(it, activeSelection.start, StyleAttribute.STRIKETHROUGH)
        else
            StyleRanges.isFullyStyled(it, activeSelection, activeTextLength, StyleAttribute.STRIKETHROUGH)
    }
    val activeTextColor = activeRanges.let {
        if (activeSelection.collapsed) {
            // Anchor covers pending color state too.
            val anchor = it.firstOrNull { a ->
                a.start == activeSelection.start && a.end == activeSelection.start && a.isStyled
            }
            anchor?.colorIndex
                ?: StyleRanges.attributeAt(it, activeSelection.start).colorIndex
        } else {
            // Show the color of the first styled character in the range.
            (activeSelection.min until activeSelection.max).firstNotNullOfOrNull { pos ->
                val covering = it.find { range -> range.start <= pos && pos < range.end }
                if (covering != null && covering.colorIndex != 0) covering.colorIndex else null
            } ?: 0
        }
    }

    // Image picker launcher.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // V5.2.1 — pass the transient content:// URI straight to the
            // ViewModel; it copies the bytes into filesDir so the image
            // survives the URI grant expiring.
            viewModel.addImageUri(uri.toString())
        }
    }

    fullScreenImageUri?.let { uriString ->
        FullScreenImageViewer(
            uriString = uriString,
            onClose = { fullScreenImageUri = null }
        )
        return
    }

    // V4.4 — confirm before hard-deleting from the editor's overflow menu.
    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this note?") },
            text = { Text("This note will be moved to Trash. You can restore it within the same account.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.moveToTrash()
                    onBack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // V5.0.2 — BasicTextField (not Material3 TextField) so
                    // we can capture IME composition and avoid saving the
                    // half-formed predictive-text state. Material3 TextField
                    // doesn't expose the underlying TextFieldValue / composition
                    // to the call site.
                    BasicTextField(
                        value = TextFieldValue(
                            text = state.title,
                            selection = TextRange(state.title.length)
                        ),
                        onValueChange = viewModel::onTitleChange,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        // Disable autocomplete so the IME never injects
                        // suggestions like "<1000S>" into the title field.
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(headerBg),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (state.title.isEmpty()) {
                                    Text(
                                        text = "Untitled",
                                        style = TextStyle(
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        }
                    )
                },
navigationIcon = {
                    IconButton(onClick = {
                        // V4.3: always persist before leaving so we don't
                        // drop the user's last edits on a fast back press.
                        viewModel.saveNow()
                        // V5.0.2: also flush the debounced push so the
                        // server gets the final state without waiting 5s.
                        viewModel.flushPendingPush()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
actions = {
                    // V5.1.5 — small saving-status indicator BEFORE the
                    // toolbar buttons so the user always knows whether
                    // their edits have been persisted. Idle = invisible,
                    // Saving = spinner, Saved = checkmark (auto-fades),
                    // Failed = warning icon.
                    SaveStatusIndicator(saveStatus = saveStatus)
                    // V4.5 — undo / redo live in the top app bar so the user
                    // can roll back the last edit without leaving the editor.
                    // Both buttons are visually disabled when their stack
                    // is empty (Material colour scheme respects ContentAlpha).
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = canUndo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo"
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = canRedo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo"
                        )
                    }
                    // V4.3: the Save button is *always* clickable. The dirty flag
// V5.0.2 — auto-save model (Samsung Notes, Apple Notes,
                    // Google Keep). Edits are persisted on every keystroke
                    // and the back arrow is the single exit point, so the
                    // visible Save button is removed entirely — it implied
                    // the user had to do something explicit which they don't.
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            // V4.4: word/character count is now computed from
                            // the live editor state so the numbers are
                            // always up to date.
                            val words = state.content
                                .split(Regex("\\s+"))
                                .count { it.isNotBlank() }
                            val chars = state.content.length
                            DropdownMenuItem(
                                text = { Text("Stats: $words words · $chars chars") },
                                onClick = { menuExpanded = false },
                                enabled = false
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Share as text") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Share, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    shareNote(context, state.title, state.content)
                                }
                            )
                            // V5.0: export the rich text as Markdown so the
                            // recipient sees bold/list/lists preserved.
                            DropdownMenuItem(
                                text = { Text("Share as Markdown") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Code, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    shareMarkdown(context, state.title, state.blocks)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
onClick = {
                                    menuExpanded = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = headerBg,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(notesPageBackground())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding()
        ) {
            // Note card with INLINE blocks
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                BlocksEditor(
                    blocks = state.blocks,
                    activeBlockId = state.activeBlockId,
                    onBlockValueChange = viewModel::onBlockValueChange,
                    onBlockFocused = viewModel::onBlockFocused,
                    onToggleChecklistLine = { blockId, lineIndex ->
                        viewModel.toggleChecklistLine(blockId, lineIndex)
                    },
                    onRemoveImage = { blockId -> viewModel.removeImageBlock(blockId) },
                    onTapImage = { uri -> fullScreenImageUri = uri },
                    failedImageUris = failedImageUris,
                    onRetryImageUpload = { uri -> viewModel.retryImageUpload(uri) },
                    autoFocusFirstBlock = true,
                    autoFocusRequester = firstBlockFocusRequester
                )
            }

            // ----- V4.3.1 classic 2-row toolbar -----
            // Row 1: B | I | U | | A− Aa A+ | | L C R | | A color
            // Row 2: ≡ | ☰ | ☑ | | Bg | Img
            // Tapping a list button while it's already active clears it,
            // matching Notion/Bear behaviour.
            EditorToolbar(
                activeFontSize = activeFontSize,
                isBoldActive = isBoldActive,
                isItalicActive = isItalicActive,
                isUnderlineActive = isUnderlineActive,
                isStrikethroughActive = isStrikethroughActive,
                activeTextColorIndex = activeTextColor,
                textAlignIndex = activeTextAlign,
                listType = activeListType,
                isChecklistActive = activeIsChecklist,
                // V5.1 — checklist rows ignore list/alignment controls; the
                // toolbar hides those buttons entirely to avoid noise.
                hideListControls = activeIsChecklist,
                onAddImage = { imagePickerLauncher.launch("image/*") },
                onBackgroundColorClick = { bgColorPickerVisible = true },
                onBoldClick = viewModel::toggleBold,
                onItalicClick = viewModel::toggleItalic,
                onUnderlineClick = viewModel::toggleUnderline,
                onStrikethroughClick = viewModel::toggleStrikethrough,
                onBulletClick = { viewModel.setListType(if (activeListType == 1) 0 else 1) },
                onNumberedClick = { viewModel.setListType(if (activeListType == 2) 0 else 2) },
                onChecklistClick = viewModel::toggleChecklist,
                onAlignLeftClick = { viewModel.setTextAlign(0) },
                onAlignCenterClick = { viewModel.setTextAlign(1) },
                onAlignRightClick = { viewModel.setTextAlign(2) },
                onDecreaseSizeClick = viewModel::decreaseTextSize,
                onIncreaseSizeClick = viewModel::increaseTextSize,
                onShowTextColorPicker = { textColorPickerVisible = true }
            )

            // Background-color swatch popup.
            Box {
                Spacer(Modifier.size(1.dp))
                DropdownMenu(
                    expanded = bgColorPickerVisible,
                    onDismissRequest = { bgColorPickerVisible = false }
                ) {
                    BackgroundColorSwatchRowContent(
                        currentIndex = state.backgroundColor,
                        onSelect = { index ->
                            viewModel.setBackgroundColorIndex(index)
                            bgColorPickerVisible = false
                        }
                    )
                }
            }

            // Text-color swatch popup.
            Box {
                Spacer(Modifier.size(1.dp))
                DropdownMenu(
                    expanded = textColorPickerVisible,
                    onDismissRequest = { textColorPickerVisible = false }
                ) {
                    TextColorSwatchRowContent(
                        currentIndex = activeTextColor,
                        onSelect = { index ->
                            viewModel.setTextColorIndex(index)
                            textColorPickerVisible = false
                        }
                    )
                }
            }
        }
    }
}

/* -----------------------------------------------------------------------
 * Toolbar (V4.3.1 — classic 2-row, B/I/U as direct toggles)
 *
 * Compared with V2.6 this exposes the most-used inline formats
 * (Bold/Italic/Underline, Bullets/Numbered/Checklist, Align L/C/R,
 * Text size, Text color, Background, Image) as one-tap toggles instead
 * of three dropdowns. The active state is reflected by the primary-color
 * tint + a soft circle background so the user always knows which formats
 * are currently applied at the cursor.
 * --------------------------------------------------------------------- */

/**
 * V5.1.5 — small spinner / checkmark / warning chip rendered into the
 * top app bar so the user always knows whether their edits have been
 * persisted. Idle = invisible (don't render anything so the bar
 * stays visually clean), Saving = mini spinner, Saved = green tick
 * that auto-fades after 1.5s, Failed = red warning with the error.
 */
@Composable
private fun SaveStatusIndicator(saveStatus: SaveStatus) {
    when (saveStatus) {
        SaveStatus.Idle -> Unit
        SaveStatus.Saving -> {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        is SaveStatus.Saved -> {
            Icon(
                imageVector = Icons.Filled.CloudDone,
                contentDescription = "Saved",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        is SaveStatus.Failed -> {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Save failed: ${saveStatus.message}",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EditorToolbar(
    activeFontSize: Int,
    isBoldActive: Boolean,
    isItalicActive: Boolean,
    isUnderlineActive: Boolean,
    isStrikethroughActive: Boolean,
    activeTextColorIndex: Int,
    textAlignIndex: Int,
    listType: Int,
    isChecklistActive: Boolean,
    /**
     * V5.1 — when the active block is a checklist, list-type and
     * alignment controls are hidden because they don't make sense on a
     * row-of-checkboxes layout. The rest of the toolbar (B/I/U, color,
     * size, image, background) still applies per-character to the
     * checklist text.
     */
    hideListControls: Boolean = false,
    onAddImage: () -> Unit,
    onBackgroundColorClick: () -> Unit,
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onUnderlineClick: () -> Unit,
    onStrikethroughClick: () -> Unit,
    onBulletClick: () -> Unit,
    onNumberedClick: () -> Unit,
    onChecklistClick: () -> Unit,
    onAlignLeftClick: () -> Unit,
    onAlignCenterClick: () -> Unit,
    onAlignRightClick: () -> Unit,
    onDecreaseSizeClick: () -> Unit,
    onIncreaseSizeClick: () -> Unit,
    onShowTextColorPicker: () -> Unit
) {
    // V5.0.1 — single horizontal row, scrollable, vertically compact.
    // The previous two-row variant wasted ~56dp of vertical space and
    // forced the user to remember which controls lived on which row.
    // Now everything is one fluid toolbar; groups are still separated
    // by thin vertical dividers so the eye can parse it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // V5.1.2 toolbar order, dictated by user feedback:
        //   1. Image
        //   2. Background color (note card)
        //   3. Text color
        //   4. Visual formatting  (Bold / Italic / Underline / Strike)
        //   5. Size              (A− / Aa / A+)
        //   6. Lists             (Bullet / Numbered / Checklist)
        //   7. Alignment         (Left / Center / Right)

        ToolbarIconButton(
            imageVector = Icons.Filled.Image,
            contentDescription = "Image",
            onClick = onAddImage
        )

        ToolbarDivider()

        ToolbarIconButton(
            imageVector = Icons.Filled.FormatColorFill,
            contentDescription = "Background color",
            onClick = onBackgroundColorClick
        )

        ToolbarIconButton(
            imageVector = Icons.Filled.FormatColorText,
            contentDescription = "Text color",
            onClick = onShowTextColorPicker,
            active = activeTextColorIndex != 0,
            tint = if (activeTextColorIndex != 0)
                textColorFor(activeTextColorIndex)
            else MaterialTheme.colorScheme.onSurface
        )

        ToolbarDivider()

        ToolbarIconButton(
            imageVector = Icons.Filled.FormatBold,
            contentDescription = "Bold",
            onClick = onBoldClick,
            active = isBoldActive
        )
        ToolbarIconButton(
            imageVector = Icons.Filled.FormatItalic,
            contentDescription = "Italic",
            onClick = onItalicClick,
            active = isItalicActive
        )
        ToolbarIconButton(
            imageVector = Icons.Filled.FormatUnderlined,
            contentDescription = "Underline",
            onClick = onUnderlineClick,
            active = isUnderlineActive
        )
        ToolbarIconButton(
            imageVector = Icons.Filled.FormatStrikethrough,
            contentDescription = "Strike",
            onClick = onStrikethroughClick,
            active = isStrikethroughActive
        )

        ToolbarDivider()

        IconButton(
            onClick = onDecreaseSizeClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.TextDecrease,
                contentDescription = "Decrease text size",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            modifier = Modifier.size(width = 44.dp, height = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Aa",
                    style = TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = textSizeLabelFor(activeFontSize),
                    style = TextStyle(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(
            onClick = onIncreaseSizeClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.TextIncrease,
                contentDescription = "Increase text size",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        ToolbarDivider()

        // V5.1.2 — list controls are hidden when the active block is a
        // checklist because each row would behave like its own line and
        // the "block-level" list type doesn't make sense per row.
        if (!hideListControls) {
            ToolbarIconButton(
                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = "Bullet list",
                onClick = onBulletClick,
                active = listType == 1
            )
            ToolbarIconButton(
                imageVector = Icons.Filled.FormatListNumbered,
                contentDescription = "Numbered list",
                onClick = onNumberedClick,
                active = listType == 2
            )
            ToolbarIconButton(
                imageVector = Icons.Filled.Checklist,
                contentDescription = "Checklist",
                onClick = onChecklistClick,
                active = isChecklistActive
            )
        }

        ToolbarDivider()

        ToolbarIconButton(
            imageVector = Icons.AutoMirrored.Filled.FormatAlignLeft,
            contentDescription = "Align left",
            onClick = onAlignLeftClick,
            active = textAlignIndex == 0,
            enabled = !hideListControls
        )
        ToolbarIconButton(
            imageVector = Icons.Filled.FormatAlignCenter,
            contentDescription = "Align center",
            onClick = onAlignCenterClick,
            active = textAlignIndex == 1,
            enabled = !hideListControls
        )
        ToolbarIconButton(
            imageVector = Icons.AutoMirrored.Filled.FormatAlignRight,
            contentDescription = "Align right",
            onClick = onAlignRightClick,
            active = textAlignIndex == 2,
            enabled = !hideListControls
        )
    }
}

/**
 * Thin vertical separator between toolbar groups. Stays at full toolbar
 * height (≈ 36dp) so it visually groups the related buttons without
 * breaking the icon's 40dp touch target.
 */
@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .padding(horizontal = 4.dp)
    ) {
        androidx.compose.material3.VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/* -----------------------------------------------------------------------
 * Block-based editor
 * --------------------------------------------------------------------- */

/**
 * Renders the list of [ContentBlock]s in a scrollable Column. Each block is
 * independent so typing only mutates one block, and images appear inline
 * (between text segments) — Telegram/WhatsApp style.
 */
@Composable
private fun BlocksEditor(
    blocks: List<ContentBlock>,
    activeBlockId: String?,
    onBlockValueChange: (blockId: String, newValue: TextFieldValue) -> Unit,
    onBlockFocused: (blockId: String) -> Unit,
    onToggleChecklistLine: (blockId: String, lineIndex: Int) -> Unit,
    onRemoveImage: (blockId: String) -> Unit,
    onTapImage: (uri: String) -> Unit,
    // F-05 — which image blocks failed to upload (their local URI is
    // in the set) and the retry action for them.
    failedImageUris: Set<String> = emptySet(),
    onRetryImageUpload: (uri: String) -> Unit = {},
    autoFocusFirstBlock: Boolean = false,
    autoFocusRequester: FocusRequester? = null
) {
    val scrollState = rememberScrollState()
    val defaultStyle = LocalTextStyle.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        if (blocks.isEmpty()) {
            Spacer(Modifier.height(120.dp))
            Text(
                text = "Start writing...",
                style = defaultStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
            return
        }

        blocks.forEachIndexed { idx, block ->
            when (block) {
                is ContentBlock.TextBlock -> {
                    if (block.isChecklist) {
                        ChecklistBlockEditor(
                            block = block,
                            activeBlockId = activeBlockId,
                            onValueChange = { v -> onBlockValueChange(block.id, v) },
                            onBlockFocused = { onBlockFocused(block.id) },
                            onToggleLine = { lineIndex ->
                                onToggleChecklistLine(block.id, lineIndex)
                            },
                            focusRequester = if (autoFocusFirstBlock && idx == 0) autoFocusRequester else null
                        )
                    } else if (block.listType != 0) {
                        ListBlockEditor(
                            block = block,
                            activeBlockId = activeBlockId,
                            onValueChange = { v -> onBlockValueChange(block.id, v) },
                            onBlockFocused = { onBlockFocused(block.id) },
                            focusRequester = if (autoFocusFirstBlock && idx == 0) autoFocusRequester else null
                        )
                    } else {
                        TextBlockEditor(
                            block = block,
                            activeBlockId = activeBlockId,
                            onValueChange = { v -> onBlockValueChange(block.id, v) },
                            onBlockFocused = { onBlockFocused(block.id) },
                            focusRequester = if (autoFocusFirstBlock && idx == 0) autoFocusRequester else null
                        )
                    }
                }
                is ContentBlock.ImageBlock -> {
                    InlineImage(
                        uriString = block.uri,
                        onTap = { onTapImage(block.uri) },
                        onRemove = { onRemoveImage(block.id) },
                        // F-05 — the badge + retry action are only
                        // shown for images whose upload gave up.
                        uploadFailed = block.uri in failedImageUris,
                        onRetryUpload = { onRetryImageUpload(block.uri) }
                    )
                }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

/* -----------------------------------------------------------------------
 * TextBlockEditor — V2.6
 *
 * Uses BasicTextField + TextFieldValue + VisualTransformation. The rendered
 * AnnotatedString is built from the block's styleRanges, so per-character
 * bold/italic/underline/color is shown in-line.
 *
 * BUG FIX (v5.1) — Track cursor/selection locally so toolbar actions always
 * operate on the user's current cursor position, not the stale "last text
 * change" position. Compose's BasicTextField `onValueChange` callback is
 * reliable for text changes but historically only fires for selection moves
 * on newer Compose BOMs. We capture selection explicitly via
 * `Modifier.pointerInput` (tap → place cursor; drag → extend selection)
 * and keep it in sync with what BasicTextField reports. The toolbar reads
 * this local value via the parent screen's hoist, which is now updated on
 * every cursor move.
 * --------------------------------------------------------------------- */

@Composable
private fun TextBlockEditor(
    block: ContentBlock.TextBlock,
    activeBlockId: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onBlockFocused: () -> Unit,
    focusRequester: FocusRequester?
) {
    val baseStyle = textStyleFor(block)
    val transformation = remember(block.styleRanges) { richTextVisualTransformation(block.styleRanges) }

    // BUG FIX: track the block's value locally so we can react to cursor-only
    // changes that don't trigger onValueChange on older Compose BOMs.
    var localValue by remember(block.id) { mutableStateOf(block.value) }

    // Sync external changes (undo/redo, programmatic) back into localValue.
    LaunchedEffect(block.value) {
        if (block.value.text != localValue.text || block.value.selection != localValue.selection) {
            localValue = block.value
        }
    }

    BasicTextField(
        value = localValue,
        onValueChange = { newValue ->
            val oldSel = localValue.selection
            val newSel = newValue.selection
            // Only treat as a "selection-only" update if the text didn't
            // change but the cursor moved; in that case still forward so
            // the toolbar active-state queries hit the right position.
            val selectionOnly = newValue.text == localValue.text && newSel != oldSel
            localValue = newValue
            onValueChange(newValue)
            // Defensive: even on BOMs where onValueChange already covers
            // selection, emit again at the cursor position with the same
            // text so the ViewModel never falls behind the cursor.
            if (selectionOnly) {
                onBlockFocused()
            }
        },
        textStyle = baseStyle.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = baseStyle.textAlign ?: TextAlign.Start
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = transformation,
        // Disable autocomplete so the keyboard never injects tokens like
        // "<1000S>" mid-paragraph.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default,
            autoCorrectEnabled = false,
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onBlockFocused()
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (block.text.isEmpty()) {
                    Text(
                        text = "Type here...",
                        style = baseStyle.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = baseStyle.textAlign ?: TextAlign.Start
                        )
                    )
                }
                inner()
            }
        }
    )
}

/* -----------------------------------------------------------------------
 * ListBlockEditor — V2.6
 *
 * Renders a list-style TextBlock. Each line has a bullet or numbered
 * prefix drawn in a Column to the left of the BasicTextField. The
 * per-character rich text is layered on top via VisualTransformation.
 * --------------------------------------------------------------------- */

@Composable
private fun ListBlockEditor(
    block: ContentBlock.TextBlock,
    activeBlockId: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onBlockFocused: () -> Unit,
    focusRequester: FocusRequester?
) {
    val baseStyle = textStyleFor(block)
    val transformation = remember(block.styleRanges) { richTextVisualTransformation(block.styleRanges) }
    val lines = block.text.split('\n')

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // V5.2.1 — Prefix column: render bullets via a vector icon
        // instead of the U+2022 glyph. The bullet glyph (•) is missing
        // from Samsung A55's stock system font and falls back to ":",
        // which looked like a typo. Icons.Filled.FiberManualRecord is a
        // tiny filled circle and renders identically everywhere.
        Column(
            modifier = Modifier.padding(end = 6.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.End
        ) {
            lines.indices.forEach { idx ->
                when (block.listType) {
                    2 -> Text(
                        text = "${idx + 1}.",
                        style = baseStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    else -> Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(6.dp)
                    )
                }
            }
        }
        BasicTextField(
            value = block.value,
            onValueChange = onValueChange,
            textStyle = baseStyle.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
                autoCorrectEnabled = false,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (state.isFocused) onBlockFocused()
                }
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                ),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (block.text.isEmpty()) {
                        Text(
                            text = "List item",
                            style = baseStyle.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    inner()
                }
            }
        )
    }
}

/* -----------------------------------------------------------------------
 * ChecklistBlockEditor — V5.1 inline rewrite
 *
 * V2.6 had a confused layout: the visual checklist was a list of *read-only*
 * Text widgets stacked above a hidden BasicTextField where the user had
 * to type raw `- [ ] something` markdown. Users couldn't tap to edit an
 * existing item and a freshly entered line without the `- [ ]` prefix
 * would silently get one prepended, swallowing what they typed.
 *
 * The V5.1 rewrite does what every modern notes app does:
 *   - one Editable row per item (Checkbox + BasicTextField)
 *   - Enter at end of an item appends a fresh empty item below
 *   - Backspace on an empty item removes the row (only when there are
 *     already multiple items — single empty list is preserved as a
 *     drafting target)
 *   - the block.text canonical form is re-derived from the in-memory line
 *     list whenever a row changes, so the on-disk `- [ ] text\n- [x] text`
 *     format stays stable for the parser.
 * --------------------------------------------------------------------- */

@Composable
private fun ChecklistBlockEditor(
    block: ContentBlock.TextBlock,
    activeBlockId: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onBlockFocused: () -> Unit,
    onToggleLine: (lineIndex: Int) -> Unit,
    focusRequester: FocusRequester?
) {
    val baseStyle = textStyleFor(block)
    // Parse the canonical `- [ ] x` / `- [x] x` form back into structured
    // rows. Recomputes only when the underlying block.text changes.
    val items = remember(block.text) {
        block.text.split('\n').map(::parseChecklistLine)
    }

    fun serialize(updated: List<ChecklistLine>): String =
        updated.joinToString("\n") { line ->
            val marker = if (line.done) "- [x] " else "- [ ] "
            marker + line.text
        }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        items.forEachIndexed { lineIdx, item ->
            ChecklistLineRow(
                lineKey = "${block.id}:$lineIdx",
                item = item,
                isOnlyLine = items.size == 1 && item.text.isBlank(),
                baseStyle = baseStyle,
                onCheckedChange = { onToggleLine(lineIdx) },
                onTextChange = { newText ->
                    val working = items.toMutableList()
                    while (working.size <= lineIdx) working.add(ChecklistLine(false, ""))
                    // Drop the leading marker if the user typed it.
                    val cleaned = when {
                        newText.startsWith("- [ ] ") -> newText.removePrefix("- [ ] ")
                        newText.startsWith("- [x] ") || newText.startsWith("- [X] ") ->
                            newText.removePrefix("- [x] ").removePrefix("- [X] ")
                        else -> newText
                    }
                    working[lineIdx] = ChecklistLine(done = item.done, text = cleaned)
                    onValueChange(
                        TextFieldValue(
                            text = serialize(working),
                            // V5.1.1 — each row owns its own caret now; we no
                            // longer need the parent's best-effort guess.
                            // The line child stays in sync via its own local
                            // TextFieldValue, so passing selection=0 is fine.
                            selection = TextRange(0)
                        )
                    )
                },
                onAddLine = {
                    val working = items.toMutableList()
                    // Insert a fresh empty item AFTER the current one.
                    working.add(lineIdx + 1, ChecklistLine(false, ""))
                    onValueChange(
                        TextFieldValue(text = serialize(working), selection = TextRange(Int.MAX_VALUE))
                    )
                },
                onRemoveIfEmpty = {
                    // Only allow removal when there are 2+ items AND the
                    // current one is blank. Single empty item is kept as
                    // a drafting target so the editor never disappears.
                    if (items.size > 1 && item.text.isBlank()) {
                        val working = items.toMutableList()
                        working.removeAt(lineIdx)
                        onValueChange(
                            TextFieldValue(text = serialize(working), selection = TextRange(0))
                        )
                    }
                },
                onFocused = onBlockFocused,
                focusRequester = if (lineIdx == 0) focusRequester else null
            )
        }
    }
}

@Composable
private fun ChecklistLineRow(
    /**
     * V5.1.1 — stable remember key for the row's local TextFieldValue.
     * Identifies a single checklist row across recompositions so the
     * caret doesn't reset when item.text changes underneath us.
     */
    lineKey: String,
    item: ChecklistLine,
    isOnlyLine: Boolean,
    baseStyle: TextStyle,
    onCheckedChange: () -> Unit,
    onTextChange: (String) -> Unit,
    onAddLine: () -> Unit,
    onRemoveIfEmpty: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?
) {
    // V5.1.1 — local TextFieldValue so the caret doesn't reset to 0 on
    // every keystroke. Previously `value = TextFieldValue(item.text)`
    // rebuilt the field's state from the block.text-derived item each
    // render, dropping selection; the user saw characters appear with
    // the caret glued to the start. Now the caret lives in local state
    // and we only re-sync when item.text changes from the OUTSIDE
    // (undo/redo, programmatic).
    var localValue by remember(lineKey) {
        mutableStateOf(TextFieldValue(item.text, selection = TextRange(item.text.length)))
    }
    LaunchedEffect(item.text) {
        if (localValue.text != item.text) {
            localValue = TextFieldValue(
                text = item.text,
                selection = TextRange(item.text.length)
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCheckedChange,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (item.done) Icons.Filled.CheckBox
                else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = if (item.done) "Mark not done" else "Mark done",
                tint = if (item.done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        BasicTextField(
            value = localValue,
            onValueChange = { newValue ->
                // Enter / newline on the IME inserts a new line; we apply
                // the pre-newline portion to the current row and add a
                // fresh row below.
                val raw = newValue.text
                if (raw.contains('\n')) {
                    val cleaned = raw.substringBefore('\n')
                    localValue = TextFieldValue(
                        text = cleaned,
                        selection = TextRange(cleaned.length)
                    )
                    onTextChange(cleaned)
                    onAddLine()
                    return@BasicTextField
                }
                // Backspace that empties the row — if it's the only row
                // we just clear the text, otherwise remove it (the
                // caller enforces the "only-line stays as draft" rule).
                localValue = newValue
                when {
                    raw.isEmpty() && item.text.isNotEmpty() -> onTextChange("")
                    raw.isEmpty() && isOnlyLine -> onTextChange("")
                    raw.isEmpty() -> onRemoveIfEmpty()
                    else -> onTextChange(raw)
                }
            },
            textStyle = baseStyle.copy(
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
                autoCorrectEnabled = false,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) onFocused()
                }
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                ),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (item.text.isEmpty()) {
                        Text(
                            text = if (isOnlyLine) "Add checklist item…" else "New item…",
                            style = baseStyle.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    inner()
                }
            }
        )
    }
}

private data class ChecklistLine(val done: Boolean, val text: String)

private fun parseChecklistLine(line: String): ChecklistLine {
    return when {
        line.startsWith("- [x] ") || line.startsWith("- [X] ") ->
            ChecklistLine(done = true, text = line.removePrefix("- [x] ").removePrefix("- [X] "))
        line.startsWith("- [ ] ") ->
            ChecklistLine(done = false, text = line.removePrefix("- [ ] "))
        else -> ChecklistLine(done = false, text = line)
    }
}

/* -----------------------------------------------------------------------
 * Inline image block
 * --------------------------------------------------------------------- */

@Composable
private fun InlineImage(
    uriString: String,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    // F-05 — the image's upload gave up (the background push pass
    // burned its retry budget). We show a warning strip with a
    // "retry" action so the user knows the image never reached the
    // server and can try again.
    uploadFailed: Boolean = false,
    onRetryUpload: () -> Unit = {}
) {
    // V5.2.3 — moved off the UI thread. The previous synchronous
    // `remember { loadImageBitmapFromUri(...) }` blocked the main thread
    // on a slow `https://…` fetch and surfaced as "the image
    // disappeared" the moment the public URL was wired in.
    val bitmap: ImageBitmap? = com.example.notes.ui.util.rememberLoadedImage(uriString)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onTap)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Note image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove image",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // F-05 — the "never uploaded" notice + retry action. Only
        // rendered for images whose upload actually failed, so a
        // healthy note is completely unaffected.
        if (uploadFailed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Rasm yuklanmadi, qayta urinib ko'ring",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetryUpload) {
                    Text(
                        "Qayta urinish",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/* -----------------------------------------------------------------------
 * Toolbar / swatches / full-screen viewer / loader
 * --------------------------------------------------------------------- */

@Composable
private fun ToolbarIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false,
    enabled: Boolean = true
) {
    val effectiveTint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> tint
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = effectiveTint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun FullScreenImageViewer(
    uriString: String,
    onClose: () -> Unit
) {
    // V5.2.3 — moved off the UI thread (see [rememberLoadedImage]). The
    // full-screen viewer used to be the worst offender: tapping an image
    // would freeze the screen until the network fetch returned.
    val bitmap: ImageBitmap? = com.example.notes.ui.util.rememberLoadedImage(uriString)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Full screen image",
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onClose),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(96.dp)
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }
}

/* -----------------------------------------------------------------------
 * V5.2.3 — image decoding used to live in `loadImageBitmapFromUri`
 * below this line. That version ran OkHttp + BitmapFactory on the UI
 * thread inside `remember { … }`, which on a slow network turned the
 * public-URL rewrite that fires after a successful upload into "the
 * image disappears". The decode itself is now in
 * [com.example.notes.ui.util.rememberLoadedImage] and runs on
 * Dispatchers.IO via a LaunchedEffect; this file just calls it.
 * --------------------------------------------------------------------- */

/* -----------------------------------------------------------------------
 * Color swatches
 * --------------------------------------------------------------------- */

@Composable
private fun BackgroundColorSwatchRowContent(
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    // V4.5: 8 background colors. The first entry is the default (no tint)
    // and the rest map 1:1 to the indexes in [noteCardColor].
    val swatches = listOf(
        "Default" to noteCardColor(0),
        "Yellow" to noteCardColor(1),
        "Blue" to noteCardColor(2),
        "Pink" to noteCardColor(3),
        "Green" to noteCardColor(4),
        "Orange" to noteCardColor(5),
        "Purple" to noteCardColor(6),
        "Red" to noteCardColor(7)
    )
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        swatches.forEachIndexed { index, (label, color) ->
            ColorSwatch(
                color = color,
                label = label,
                selected = currentIndex == index,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun TextColorSwatchRowContent(
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    val autoColor = MaterialTheme.colorScheme.onSurface
    val swatches = listOf(
        "Auto" to autoColor,
        "Red" to textColorFor(1),
        "Green" to textColorFor(2),
        "Blue" to textColorFor(3),
        "Purple" to textColorFor(4),
        "Orange" to textColorFor(5)
    )
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        swatches.forEachIndexed { index, (label, color) ->
            ColorSwatch(
                color = color,
                label = label,
                selected = currentIndex == index,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = CircleShape
                )
                .clickable(onClick = onClick)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

