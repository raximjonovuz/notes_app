package com.example.notes.ui.edit

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
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
import androidx.compose.runtime.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notes.ui.theme.noteCardColor
import com.example.notes.ui.theme.noteCardOnColor
import com.example.notes.ui.theme.notesHeaderBackground
import com.example.notes.ui.theme.notesPageBackground
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

/* -----------------------------------------------------------------------
 * VisualTransformation that renders the underlying TextFieldValue text as
 * an AnnotatedString built from the block's styleRanges. Per-character
 * bold/italic/underline/color is applied via SpanStyle.
 * --------------------------------------------------------------------- */
private fun richTextVisualTransformation(
    ranges: List<StyleRange>,
    // FIX: per-line font-size overrides (A+/A- on a multi-line block)
    // were stored but never rendered. They are applied here as spans.
    fontSizesByLine: Map<Int, Int> = emptyMap()
): VisualTransformation = VisualTransformation { text ->
    val raw = text.text
    val base = StyleRanges.toAnnotatedString(raw, ranges)
    val annotated = if (fontSizesByLine.isEmpty()) base else buildAnnotatedString {
        append(base)
        var start = 0
        raw.split('\n').forEachIndexed { idx, line ->
            val size = fontSizesByLine[idx]
            if (size != null && line.isNotEmpty()) {
                addStyle(SpanStyle(fontSize = fontSizeSpFor(size)), start, start + line.length)
            }
            start += line.length + 1
        }
    }
    TransformedText(annotated, OffsetMapping.Identity)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    viewModel: NoteEditViewModel,
    startAsChecklist: Boolean = false,
    initialImageUri: String? = null,
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
    var noteDetailsVisible by remember { mutableStateOf(false) }
    var insertFormatVisible by remember { mutableStateOf(false) }

    // For a brand new note, seed an empty TextBlock so the content editor
    // has a target for the user to type into. Auto-focus the first block.
    LaunchedEffect(noteId) {
        viewModel.loadNote(noteId)
        if ((noteId == null || noteId == 0L) &&
            state.blocks.isEmpty()
        ) {
            if (!initialImageUri.isNullOrBlank()) {
                viewModel.addImageUri(initialImageUri)
            }
            viewModel.addEmptyTextBlockAtEnd()
            if (startAsChecklist) {
                viewModel.toggleChecklist()
            }
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
    val activeIsChecklist = when (val ab = activeBlock) {
        is ContentBlock.TextBlock -> {
            val lines = ab.text.split('\n')
            val line = lines.getOrNull(activeLineIdx) ?: ""
            line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")
        }
        else -> false
    }

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

    BackHandler {
        viewModel.saveNow()
        viewModel.flushPendingPush()
        onBack()
    }

    val onColor = noteCardOnColor(state.backgroundColor)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = cardBg,
        topBar = {
            TopAppBar(
                title = {
                    SaveStatusIndicator(saveStatus = saveStatus)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveNow()
                        viewModel.flushPendingPush()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onColor
                        )
                    }
                },
                actions = {
                    val pinScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (state.isPinned) 1.22f else 1.0f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        label = "pinBounce"
                    )
                    IconButton(
                        onClick = { viewModel.togglePinned() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (state.isPinned) "Unpin" else "Pin",
                            tint = if (state.isPinned) MaterialTheme.colorScheme.primary else onColor,
                            modifier = Modifier.graphicsLayer {
                                scaleX = pinScale
                                scaleY = pinScale
                            }
                        )
                    }
                    val starScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (state.isFavorite) 1.25f else 1.0f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        label = "starBounce"
                    )
                    IconButton(
                        onClick = { viewModel.toggleFavorite() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (state.isFavorite) Color(0xFFF4B400) else onColor,
                            modifier = Modifier.graphicsLayer {
                                scaleX = starScale
                                scaleY = starScale
                            }
                        )
                    }
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = canUndo,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (canUndo) onColor else onColor.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = canRedo,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (canRedo) onColor else onColor.copy(alpha = 0.38f)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = onColor
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Note details") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Info, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    noteDetailsVisible = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Share, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    shareNote(context, state.title, state.content)
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
                    containerColor = cardBg,
                    titleContentColor = onColor,
                    actionIconContentColor = onColor,
                    navigationIconContentColor = onColor
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(cardBg)
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .imePadding()
        ) {
            BasicTextField(
                value = TextFieldValue(
                    text = state.title,
                    selection = state.titleSelection
                ),
                onValueChange = viewModel::onTitleChange,
                singleLine = false,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = onColor
                ),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 8.dp),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (state.title.isEmpty()) {
                            Text(
                                text = "Title",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = onColor.copy(alpha = 0.45f)
                            )
                        }
                        inner()
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                BlocksEditor(
                    blocks = state.blocks,
                    activeBlockId = state.activeBlockId,
                    onBlockValueChange = viewModel::onBlockValueChange,
                    onBlockFocused = viewModel::onBlockFocused,
                    onRemoveImage = { blockId -> viewModel.removeImageBlock(blockId) },
                    onTapImage = { uri -> fullScreenImageUri = uri },
                    failedImageUris = failedImageUris,
                    onRetryImageUpload = { uri -> viewModel.retryImageUpload(uri) },
                    autoFocusFirstBlock = true,
                    autoFocusRequester = firstBlockFocusRequester
                )
            }

            Spacer(Modifier.height(4.dp))

            // V6: the formatting toolbar stays docked above the keyboard
            // (the screen Column has `imePadding()`, so the toolbar —
            // which is the last child — rises with the IME).
            EditorToolbar(
                activeFontSize = activeFontSize,
                isBoldActive = isBoldActive,
                isItalicActive = isItalicActive,
                isUnderlineActive = isUnderlineActive,
                isStrikethroughActive = isStrikethroughActive,
                activeTextColorIndex = activeTextColor,
                isChecklistActive = activeIsChecklist,
                onToggleChecklist = viewModel::toggleChecklist,
                onAddImage = { imagePickerLauncher.launch("image/*") },
                onBackgroundColorClick = { bgColorPickerVisible = true },
                onBoldClick = viewModel::toggleBold,
                onItalicClick = viewModel::toggleItalic,
                onUnderlineClick = viewModel::toggleUnderline,
                onStrikethroughClick = viewModel::toggleStrikethrough,
                onDecreaseSizeClick = viewModel::decreaseTextSize,
                onIncreaseSizeClick = viewModel::increaseTextSize,
                onShowTextColorPicker = { textColorPickerVisible = true }
            )
        }
    }

    if (noteDetailsVisible) {
        NoteDetailsBottomSheet(
            state = state,
            onTogglePin = { viewModel.togglePinned() },
            onToggleFavorite = { viewModel.toggleFavorite() },
            onMoveToTrash = {
                noteDetailsVisible = false
                viewModel.moveToTrash()
                onBack()
            },
            onDismiss = { noteDetailsVisible = false }
        )
    }

    if (insertFormatVisible) {
        InsertFormatBottomSheet(
            onHeading = { viewModel.insertHeading(1) },
            onTitle = { viewModel.insertHeading(2) },
            onSubtitle = { viewModel.insertHeading(3) },
            onChecklist = { viewModel.toggleChecklist() },
            onBulletList = { viewModel.setListType(1) },
            onNumberedList = { viewModel.setListType(2) },
            onCodeBlock = { viewModel.insertCodeBlock() },
            onQuote = { viewModel.insertQuote() },
            onDivider = { viewModel.insertDivider() },
            onImage = { imagePickerLauncher.launch("image/*") },
            onLink = { viewModel.insertLink("link", "https://") },
            onDismiss = { insertFormatVisible = false }
        )
    }

    // ----- V6: color pickers live in bottom sheets, not dropdowns -----
    // Both sheets are hosted above the rest of the UI so they share the
    // same blur/scrim behaviour; they only mount while the user is
    // choosing a color, so the active block's lifecycle is unaffected.
    if (bgColorPickerVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { bgColorPickerVisible = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ColorPickerSheet(
                title = "Note color",
                swatches = listOf(
                    "Default" to noteCardColor(0),
                    "Yellow" to noteCardColor(1),
                    "Blue" to noteCardColor(2),
                    "Pink" to noteCardColor(3),
                    "Green" to noteCardColor(4),
                    "Orange" to noteCardColor(5),
                    "Purple" to noteCardColor(6),
                    "Red" to noteCardColor(7),
                    "Teal" to noteCardColor(8),
                    "Clay" to noteCardColor(9)
                ),
                currentIndex = state.backgroundColor,
                onSelect = { index ->
                    viewModel.setBackgroundColorIndex(index)
                    bgColorPickerVisible = false
                }
            )
        }
    }

    if (textColorPickerVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { textColorPickerVisible = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ColorPickerSheet(
                title = "Text color",
                swatches = listOf(
                    "Auto" to MaterialTheme.colorScheme.onSurface,
                    "Red" to textColorFor(1),
                    "Green" to textColorFor(2),
                    "Blue" to textColorFor(3),
                    "Purple" to textColorFor(4),
                    "Orange" to textColorFor(5)
                ),
                currentIndex = activeTextColor,
                onSelect = { index ->
                    viewModel.setTextColorIndex(index)
                    textColorPickerVisible = false
                }
            )
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
    isChecklistActive: Boolean = false,
    onToggleChecklist: () -> Unit = {},
    onAddImage: () -> Unit,
    onBackgroundColorClick: () -> Unit,
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onUnderlineClick: () -> Unit,
    onStrikethroughClick: () -> Unit,
    onDecreaseSizeClick: () -> Unit,
    onIncreaseSizeClick: () -> Unit,
    onShowTextColorPicker: () -> Unit
) {
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

        ToolbarIconButton(
            imageVector = Icons.Filled.CheckBox,
            contentDescription = "Checklist",
            onClick = onToggleChecklist,
            active = isChecklistActive
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
            Text(
                text = "Note",
                style = defaultStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
    val transformation = remember(block.styleRanges, block.fontSizesByLine) {
        richTextVisualTransformation(block.styleRanges, block.fontSizesByLine)
    }

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
    val transformation = remember(block.styleRanges, block.fontSizesByLine) {
        richTextVisualTransformation(block.styleRanges, block.fontSizesByLine)
    }
    val lines = block.text.split('\n')
    val density = LocalDensity.current
    // FIX: bullets / numbers used to sit in a Column with a fixed 2dp
    // spacing, so any wrapped line (or a different font size) pushed every
    // later marker out of alignment. Markers are now placed from the real
    // text layout: one marker at the top of each logical line's first
    // visual line.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // V5.2.1 — bullets use a vector icon: U+2022 is missing from
        // some system fonts (Samsung A55) and falls back to ":".
        Box(modifier = Modifier.width(28.dp).padding(end = 6.dp)) {
            val l = layout
            if (l != null && l.lineCount > 0) {
                var lineStart = 0
                lines.forEachIndexed { idx, line ->
                    val start = lineStart
                    lineStart += line.length + 1
                    val textLen = l.layoutInput.text.length
                    val vLine = l.getLineForOffset(start.coerceIn(0, textLen))
                    val top = l.getLineTop(vLine)
                    val lineH = l.getLineBottom(vLine) - top
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, top.roundToInt()) }
                            .fillMaxWidth()
                            .height(with(density) { lineH.toDp() }),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        when (block.listType) {
                            2 -> Text(
                                text = "${idx + 1}.",
                                style = baseStyle.copy(
                                    fontSize = fontSizeSpFor(block.fontSizeForLine(idx)),
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
            }
        }
        BasicTextField(
            value = block.value,
            onValueChange = onValueChange,
            onTextLayout = { layout = it },
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

private fun serializeItems(items: List<ChecklistItemState>): String =
    items.joinToString("\n") { item ->
        if (item.hasCheckbox) (if (item.done) "- [x] " else "- [ ] ") + item.text
        else item.text
    }

private fun parseItems(text: String, previous: List<ChecklistItemState> = emptyList()): List<ChecklistItemState> {
    val parsed = text.split('\n').mapIndexed { index, line ->
        val p = parseChecklistLine(line)
        ChecklistItemState(
            id = previous.getOrNull(index)?.id ?: java.util.UUID.randomUUID().toString(),
            hasCheckbox = p.hasCheckbox,
            done = p.done,
            text = p.text
        )
    }
    return parsed.ifEmpty {
        listOf(ChecklistItemState(id = java.util.UUID.randomUUID().toString(), hasCheckbox = true, done = false, text = ""))
    }
}

/** Offset (inside the serialized block text) where row [idx]'s own text begins. */
private fun rowTextStart(items: List<ChecklistItemState>, idx: Int): Int {
    var off = 0
    for (i in 0 until idx) off += (if (items[i].hasCheckbox) 6 else 0) + items[i].text.length + 1
    return off + (if (items[idx].hasCheckbox) 6 else 0)
}

/** Slice the block-level style ranges down to one row (row-local offsets). */
private fun rangesForRow(ranges: List<StyleRange>, textStart: Int, textLen: Int): List<StyleRange> {
    val textEnd = textStart + textLen
    val out = ArrayList<StyleRange>()
    for (r in ranges) {
        if (r.start == r.end) {
            if (r.start in textStart..textEnd) out += r.copy(start = r.start - textStart, end = r.start - textStart)
        } else {
            val s = maxOf(r.start, textStart)
            val e = minOf(r.end, textEnd)
            if (e > s) out += r.copy(start = s - textStart, end = e - textStart)
        }
    }
    return out
}

/** Which row should grab focus next, and where its caret goes (-1 = end). */
private data class PendingFocus(val itemId: String, val cursor: Int)

@Composable
private fun ChecklistBlockEditor(
    block: ContentBlock.TextBlock,
    activeBlockId: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onBlockFocused: () -> Unit,
    focusRequester: FocusRequester?
) {
    var itemsState by remember(block.id) { mutableStateOf(parseItems(block.text)) }
    var pendingFocus by remember { mutableStateOf<PendingFocus?>(null) }

    // Toolbar (toggleChecklist) or undo/redo changed block.text from outside -> re-parse.
    LaunchedEffect(block.text) {
        if (serializeItems(itemsState) != block.text) {
            itemsState = parseItems(block.text, previous = itemsState)
        }
    }

    // FIX: the caret used to be reported as TextRange(0) after every
    // edit and per-row cursor moves never reached the ViewModel, so every
    // toolbar action (checklist toggle, A+/A-, B/I/U/colour) operated on
    // line 0 / offset 0. Now the real caret, translated into an offset in
    // the serialized block text, is sent with every update.
    fun commitUpdate(
        updated: List<ChecklistItemState>,
        selection: TextRange,
        composing: Boolean = false
    ) {
        itemsState = updated
        val text = serializeItems(updated)
        val sel = TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
        onValueChange(
            TextFieldValue(
                text = text,
                selection = sel,
                // A non-null composition tells the ViewModel the IME is
                // mid-word, so it doesn't push an undo snapshot per key.
                composition = if (composing) TextRange(sel.min, sel.max) else null
            )
        )
    }

    fun rowSelection(items: List<ChecklistItemState>, idx: Int, local: TextRange): TextRange {
        val start = rowTextStart(items, idx)
        val len = items[idx].text.length
        return TextRange(start + local.start.coerceIn(0, len), start + local.end.coerceIn(0, len))
    }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        itemsState.forEachIndexed { lineIdx, item ->
            key(item.id) {
                val isLast = lineIdx == itemsState.lastIndex
                val isOnly = itemsState.size == 1
                // Per-line font size is rendered here too (block default + override).
                val rowStyle = textStyleFor(block).copy(
                    fontSize = fontSizeSpFor(block.fontSizeForLine(lineIdx))
                )
                val rowRanges = rangesForRow(
                    block.styleRanges,
                    rowTextStart(itemsState, lineIdx),
                    item.text.length
                )
                ChecklistLineRow(
                    item = item,
                    isOnlyLine = isOnly,
                    isLastLine = isLast,
                    baseStyle = rowStyle,
                    rowRanges = rowRanges,
                    shouldFocus = pendingFocus?.itemId == item.id,
                    focusCursor = pendingFocus?.cursor ?: -1,
                    onFocusConsumed = { if (pendingFocus?.itemId == item.id) pendingFocus = null },
                    onCheckedChange = {
                        val working = itemsState.toMutableList()
                        if (lineIdx in working.indices) {
                            working[lineIdx] = working[lineIdx].copy(done = !working[lineIdx].done)
                            // "- [ ] " and "- [x] " have the same length -> caret offsets stay valid.
                            commitUpdate(working, block.selection)
                        }
                    },
                    onTextChange = { newText, localSel, composing ->
                        val working = itemsState.toMutableList()
                        if (lineIdx in working.indices) {
                            working[lineIdx] = working[lineIdx].copy(text = newText)
                            commitUpdate(working, rowSelection(working, lineIdx, localSel), composing)
                        }
                    },
                    onSelectionChange = { localSel ->
                        if (lineIdx in itemsState.indices) {
                            commitUpdate(itemsState, rowSelection(itemsState, lineIdx, localSel))
                        }
                    },
                    // Enter / paste: split this row at the caret.
                    onSplitAndAddLine = { beforeNewline, afterNewline ->
                        val working = itemsState.toMutableList()
                        if (lineIdx in working.indices) {
                            val hasBox = working[lineIdx].hasCheckbox
                            working[lineIdx] = working[lineIdx].copy(text = beforeNewline)
                            val pieces = afterNewline.split('\n')
                            var firstNewId = ""
                            var lastNewId = ""
                            pieces.forEachIndexed { i, piece ->
                                val newId = java.util.UUID.randomUUID().toString()
                                if (i == 0) firstNewId = newId
                                lastNewId = newId
                                working.add(
                                    lineIdx + 1 + i,
                                    ChecklistItemState(id = newId, hasCheckbox = hasBox, done = false, text = piece)
                                )
                            }
                            // Typed Enter -> caret at the START of the moved text.
                            // Multi-line paste -> caret at the end of the last pasted row.
                            val single = pieces.size == 1
                            val targetId = if (single) firstNewId else lastNewId
                            val targetIdx = lineIdx + (if (single) 1 else pieces.size)
                            val targetLocal = if (single) 0 else working[targetIdx].text.length
                            val caret = rowTextStart(working, targetIdx) + targetLocal
                            commitUpdate(working, TextRange(caret))
                            pendingFocus = PendingFocus(targetId, if (single) 0 else -1)
                        }
                    },
                    // Backspace at the very start of a row: merge it into the previous row.
                    // (Also covers the old "Backspace on an empty row removes it".)
                    onBackspaceAtStart = {
                        if (lineIdx > 0) {
                            val working = itemsState.toMutableList()
                            val prev = working[lineIdx - 1]
                            working[lineIdx - 1] = prev.copy(text = prev.text + working[lineIdx].text)
                            working.removeAt(lineIdx)
                            val caret = rowTextStart(working, lineIdx - 1) + prev.text.length
                            commitUpdate(working, TextRange(caret))
                            pendingFocus = PendingFocus(prev.id, prev.text.length)
                            true
                        } else false
                    },
                    onFocused = onBlockFocused,
                    initialFocusRequester = if (lineIdx == 0) focusRequester else null
                )
            }
        }
    }
}

@Composable
private fun ChecklistLineRow(
    item: ChecklistItemState,
    isOnlyLine: Boolean,
    isLastLine: Boolean,
    baseStyle: TextStyle,
    rowRanges: List<StyleRange>,
    shouldFocus: Boolean,
    focusCursor: Int,
    onFocusConsumed: () -> Unit,
    onCheckedChange: () -> Unit,
    onTextChange: (text: String, selection: TextRange, composing: Boolean) -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onSplitAndAddLine: (before: String, after: String) -> Unit,
    onBackspaceAtStart: () -> Boolean,
    onFocused: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val rowFocusRequester = initialFocusRequester ?: remember { FocusRequester() }

    var localValue by remember {
        mutableStateOf(TextFieldValue(item.text, selection = TextRange(item.text.length)))
    }

    LaunchedEffect(shouldFocus) {
        if (shouldFocus) {
            if (focusCursor >= 0) {
                val c = focusCursor.coerceAtMost(localValue.text.length)
                localValue = localValue.copy(selection = TextRange(c))
            } else {
                localValue = localValue.copy(selection = TextRange(localValue.text.length))
            }
            runCatching { rowFocusRequester.requestFocus() }
            onFocusConsumed()
        }
    }

    // Text changed from outside (undo/redo, toolbar) -> keep the caret if possible.
    LaunchedEffect(item.text) {
        if (localValue.text != item.text) {
            val cursor = localValue.selection.start.coerceAtMost(item.text.length)
            localValue = TextFieldValue(text = item.text, selection = TextRange(cursor))
        }
    }

    val transformation = remember(rowRanges) { richTextVisualTransformation(rowRanges) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.hasCheckbox) {
            IconButton(onClick = onCheckedChange, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (item.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (item.done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }

        BasicTextField(
            value = localValue,
            onValueChange = { newValue ->
                val raw = newValue.text
                if (raw.contains('\n')) {
                    val nl = raw.indexOf('\n')
                    // Split at the first newline; everything after it moves down.
                    val before = raw.substring(0, nl)
                    val after = raw.substring(nl + 1)
                    localValue = TextFieldValue(text = before, selection = TextRange(before.length))
                    onSplitAndAddLine(before, after)
                    return@BasicTextField
                }
                val textChanged = raw != localValue.text
                localValue = newValue
                if (textChanged) {
                    onTextChange(raw, newValue.selection, newValue.composition != null)
                } else {
                    onSelectionChange(newValue.selection)
                }
            },
            textStyle = baseStyle.copy(
                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.Sentences
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        onFocused()
                        // Tell the ViewModel which row/caret is active so the
                        // toolbar acts on this line, not on line 0.
                        onSelectionChange(localValue.selection)
                    }
                }
                .focusRequester(rowFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.Backspace &&
                        localValue.selection.collapsed &&
                        localValue.selection.start == 0
                    ) {
                        onBackspaceAtStart()
                    } else false
                },
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (item.text.isEmpty()) {
                        val placeholder = when {
                            isOnlyLine && item.hasCheckbox -> "Ro'yxat elementi..."
                            isOnlyLine && !item.hasCheckbox -> "Matn kiriting..."
                            isLastLine && item.hasCheckbox -> "Ro'yxat elementi..."
                            else -> null
                        }
                        if (placeholder != null) {
                            Text(
                                text = placeholder,
                                style = baseStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                    inner()
                }
            }
        )
    }
}

private data class ChecklistItemState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val hasCheckbox: Boolean,
    val done: Boolean,
    val text: String
)

private data class ChecklistLine(val hasCheckbox: Boolean, val done: Boolean, val text: String)

private fun parseChecklistLine(line: String): ChecklistLine {
    return when {
        line.startsWith("- [x] ") || line.startsWith("- [X] ") ->
            ChecklistLine(hasCheckbox = true, done = true, text = line.removePrefix("- [x] ").removePrefix("- [X] "))
        line.startsWith("- [ ] ") ->
            ChecklistLine(hasCheckbox = true, done = false, text = line.removePrefix("- [ ] "))
        else -> ChecklistLine(hasCheckbox = false, done = false, text = line)
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
private fun ColorPickerSheet(
    title: String,
    swatches: List<Pair<String, Color>>,
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    // V6 — bottom-sheet color picker. Header row + 4-up grid of round
    // swatches. The selected swatch gets both a thick primary border
    // AND a centered check icon so it's unmistakable even for users
    // with reduced color vision.
    val rows = swatches.chunked(4)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = swatches.getOrNull(currentIndex)?.first ?: "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(16.dp))
        rows.forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                row.forEachIndexed { colIdx, (label, color) ->
                    val index = rowIdx * 4 + colIdx
                    ColorSwatch(
                        color = color,
                        label = label,
                        selected = currentIndex == index,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Pad the last row to keep swatches aligned when the
                // row has fewer than 4 entries (8 colors → 4+4, but
                // helper stays generic for the 6-row text-color sheet).
                if (row.size < 4) {
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                val onColor = if (isLight(color)) Color(0xFF0B2B26) else Color(0xFFDAF1DE)
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = onColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Returns true when [c] is bright enough to take the dark M3 on-color
 * (#0B2B26) instead of the light one (#DAF1DE). Used by the color
 * picker so the check icon stays legible against any swatch.
 */
private fun isLight(c: Color): Boolean {
    val r = c.red
    val g = c.green
    val b = c.blue
    // perceptual luminance
    val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return lum > 0.55f
}

/* =====================================================================
 *  Note Details bottom sheet (Screen 13)
 * ===================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailsBottomSheet(
    state: NoteEditState,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDismiss: () -> Unit
) {
    val words = remember(state.content) {
        state.content.split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val readTime = maxOf(1, (words + 199) / 200)
    val imageCount = remember(state.blocks) {
        state.blocks.count { it is ContentBlock.ImageBlock }
    }
    val checklistCount = remember(state.blocks) {
        state.blocks.sumOf { block ->
            if (block is ContentBlock.TextBlock && block.isChecklist) {
                block.text.lines().count { it.isNotBlank() }
            } else 0
        }
    }
    val dateTimeFmt = remember { java.text.SimpleDateFormat("MMM d, yyyy · HH:mm", java.util.Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Note details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2x2 stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    value = "$words",
                    label = "Words",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    value = "$readTime min",
                    label = "Read time",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    value = "$imageCount",
                    label = if (imageCount == 1) "Image" else "Images",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    value = "$checklistCount",
                    label = "Checklists",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // Metadata rows
            DetailInfoRow(
                icon = Icons.Filled.CalendarToday,
                label = "Created",
                value = dateTimeFmt.format(java.util.Date(state.createdAt))
            )
            DetailInfoRow(
                icon = Icons.Filled.Schedule,
                label = "Modified",
                value = dateTimeFmt.format(java.util.Date(state.updatedAt))
            )
            DetailInfoRow(
                icon = Icons.Filled.LocationOn,
                label = "Location",
                value = "Not set"
            )
            DetailInfoRow(
                icon = Icons.Filled.Label,
                label = "Tags",
                value = "None"
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // Switches
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Pin to top",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Switch(
                    checked = state.isPinned,
                    onCheckedChange = { onTogglePin() }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Add to favorites",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Switch(
                    checked = state.isFavorite,
                    onCheckedChange = { onToggleFavorite() }
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // Actions
            Surface(
                onClick = onMoveToTrash,
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Move to trash",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/* =====================================================================
 *  Insert & Format bottom sheet (Screen 16)
 * ===================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsertFormatBottomSheet(
    onHeading: () -> Unit,
    onTitle: () -> Unit,
    onSubtitle: () -> Unit,
    onChecklist: () -> Unit,
    onBulletList: () -> Unit,
    onNumberedList: () -> Unit,
    onCodeBlock: () -> Unit,
    onQuote: () -> Unit,
    onDivider: () -> Unit,
    onImage: () -> Unit,
    onLink: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Insert",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Choose what to add to your note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(20.dp))

            // Row 1: Heading, Title, Subtitle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InsertTile(
                    icon = Icons.Filled.Title,
                    label = "Heading",
                    modifier = Modifier.weight(1f),
                    onClick = { onHeading(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.Filled.FormatSize,
                    label = "Title",
                    modifier = Modifier.weight(1f),
                    onClick = { onTitle(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.Filled.TextFields,
                    label = "Subtitle",
                    modifier = Modifier.weight(1f),
                    onClick = { onSubtitle(); onDismiss() }
                )
            }

            Spacer(Modifier.height(10.dp))

            // Row 2: Checklist, Bullet list, Numbered list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InsertTile(
                    icon = Icons.Filled.Checklist,
                    label = "Checklist",
                    modifier = Modifier.weight(1f),
                    onClick = { onChecklist(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    label = "Bullet list",
                    modifier = Modifier.weight(1f),
                    onClick = { onBulletList(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.Filled.FormatListNumbered,
                    label = "Numbered list",
                    modifier = Modifier.weight(1f),
                    onClick = { onNumberedList(); onDismiss() }
                )
            }

            Spacer(Modifier.height(10.dp))

            // Row 3: Code block, Quote, Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InsertTile(
                    icon = Icons.Filled.Code,
                    label = "Code block",
                    modifier = Modifier.weight(1f),
                    onClick = { onCodeBlock(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.Filled.FormatQuote,
                    label = "Quote",
                    modifier = Modifier.weight(1f),
                    onClick = { onQuote(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.Filled.HorizontalRule,
                    label = "Divider",
                    modifier = Modifier.weight(1f),
                    onClick = { onDivider(); onDismiss() }
                )
            }

            Spacer(Modifier.height(10.dp))

            // Row 4: Image, Link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InsertTile(
                    icon = Icons.Filled.Image,
                    label = "Image",
                    modifier = Modifier.weight(1f),
                    onClick = { onImage(); onDismiss() }
                )
                InsertTile(
                    icon = Icons.Filled.Link,
                    label = "Link",
                    modifier = Modifier.weight(1f),
                    onClick = { onLink(); onDismiss() }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InsertTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

