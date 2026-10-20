package com.example.notes.ui.edit

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.notes.data.local.NoteEntity
import com.example.notes.data.local.AuthStore
import com.example.notes.data.remote.ApiClient
import com.example.notes.data.remote.NotesSyncRepository
import com.example.notes.data.repository.NoteRepository
import com.example.notes.util.Logger
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/* -----------------------------------------------------------------------
 * Image-URI plumbing
 * --------------------------------------------------------------------- */

private const val IMAGE_URI_SEPARATOR = "|||"

/**
 * V5.0.2 — debounce window after the IME composition has ended. Longer
 * than the legacy 300ms because predictive-keyboard acceptance often
 * arrives a couple of frames after the user lifts their finger. 600ms
 * gives the IME time to commit its composition without the auto-save
 * racing it.
 */
private const val POST_COMPOSE_DEBOUNCE_MS = 600L

/**
 * F-05 — how often the editor re-reads the image-upload retry
 * counter while a note is open. The push pass bumps the counter every
 * ~5 s (its own debounce), so 6 s is just above that: we notice a
 * "gave up" transition within one push cycle of it happening.
 */
private const val IMAGE_UPLOAD_POLL_MS = 6_000L

private fun parseImageUris(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split(IMAGE_URI_SEPARATOR).filter { it.isNotBlank() }
}

private fun joinImageUris(list: List<String>): String =
    list.filter { it.isNotBlank() }.joinToString(IMAGE_URI_SEPARATOR)

private fun extractUrisFromContent(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return parseBlocksFromContent(raw)
        .filterIsInstance<ContentBlock.ImageBlock>()
        .map { it.uri }
}

/* -----------------------------------------------------------------------
 * Editor state
 * --------------------------------------------------------------------- */

data class NoteEditState(
    val id: Long = 0L,
    val title: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val activeBlockId: String? = null,
    val isSaved: Boolean = false,
    val isNew: Boolean = true,
    val isDirty: Boolean = false,
    val backgroundColor: Int = 0
) {
    val content: String
        get() = blocksToPlainText(blocks)

    val activeBlock: ContentBlock.TextBlock?
        get() = blocks.firstOrNull { it is ContentBlock.TextBlock && it.id == activeBlockId }
                as? ContentBlock.TextBlock
}

/* -----------------------------------------------------------------------
 * Save lifecycle (V4.3.1 — bug fix)
 *
 * `SaveStatus` is surfaced through [saveStatus] so the editor screen can
 * show feedback (snackbar / "Saved" tick) right after the DB write. This
 * makes the previously-silent save button testable from the UI and
 * rules out "I pressed Save but nothing happened" reports.
 * --------------------------------------------------------------------- */

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Saved(val noteId: Long) : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

class NoteEditViewModel(
    private val repository: NoteRepository,
    private val syncRepository: NotesSyncRepository,
    /**
     * Application-scoped CoroutineScope used by save operations so they
     * survive ViewModel.onCleared() and the user's last keystroke is
     * never dropped when they pop the back stack.
     */
    private val applicationScope: CoroutineScope,
    /**
     * Application [Context] used by [addImageUri] to read image bytes
     * for upload. Storing `applicationContext` (not the Activity) so the
     * ViewModel never holds a UI reference.
     */
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(NoteEditState())
    val state: StateFlow<NoteEditState> = _state.asStateFlow()

    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    /**
     * F-05 — `true` when this note's image upload gave up (the
     * background push pass burned its whole retry budget without the
     * R2 upload succeeding). The editor renders a warning badge on
     * the affected image and offers a "retry" action.
     *
     * Kept in a separate [StateFlow] rather than inside
     * [NoteEditState] because it is NOT part of the note's content —
     * it is a transient UI flag, and folding it into the state would
     * make the undo/redo snapshots carry it around.
     */
    private val _imageUploadFailed = MutableStateFlow(false)
    val imageUploadFailed: StateFlow<Boolean> = _imageUploadFailed.asStateFlow()

    /**
     * F-05 — the local `file://` URIs of the images that failed to
     * upload. The editor uses this to put the warning badge on the
     * right image block and to know which file to re-upload when the
     * user taps "retry".
     */
    private val _failedImageUris = MutableStateFlow<Set<String>>(emptySet())
    val failedImageUris: StateFlow<Set<String>> = _failedImageUris.asStateFlow()

    /**
     * V5.1 — single-shot messages that the editor screen surfaces as a
     * Snackbar. Used when the user does something we can't silently fail
     * (toolbar action with no active block, image upload that fell back
     * to a local URI, etc). Backed by a [MutableSharedFlow] with a
     * small replay buffer so messages emitted while the screen is
     * being recomposed aren't lost.
     */
    private val _transientMessage = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transientMessage: SharedFlow<String> = _transientMessage.asSharedFlow()

    private fun emitMessage(msg: String) {
        _transientMessage.tryEmit(msg)
    }

    /**
     * V4.5 — undo/redo stacks. Each entry is the entire [NoteEditState]
     * snapshot at the moment *before* a user-driven mutation was applied,
     * so undo can replace the live state in one shot. The stacks have a
     * hard cap (50) so a long editing session doesn't leak memory; once
     * the cap is hit the oldest entry is dropped on the next push.
     */
    private val undoStack: ArrayDeque<NoteEditState> = ArrayDeque()
    private val redoStack: ArrayDeque<NoteEditState> = ArrayDeque()
    private val maxUndo: Int = 50

    /** True when at least one user-driven mutation can be undone. */
    val canUndo: StateFlow<Boolean> = MutableStateFlow(false)
    /** True when an undo has happened and a redo is available. */
    val canRedo: StateFlow<Boolean> = MutableStateFlow(false)

    private var isInitialized = false
    private var originalTitle: String = ""
    private var originalContent: String = ""
    private var originalColor: Int = 0

    private fun pushUndoSnapshot() {
        if (undoStack.size >= maxUndo) undoStack.removeFirst()
        undoStack.addLast(_state.value)
        (canUndo as MutableStateFlow).value = true
        // Any new edit invalidates the redo branch.
        if (redoStack.isNotEmpty()) {
            redoStack.clear()
            (canRedo as MutableStateFlow).value = false
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_state.value)
        if (redoStack.size > maxUndo) redoStack.removeFirst()
        _state.value = undoStack.removeLast()
        (canUndo as MutableStateFlow).value = undoStack.isNotEmpty()
        (canRedo as MutableStateFlow).value = true
        // Persist the rolled-back state immediately so the user can't
        // navigate away and lose the undo.
        saveNow()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_state.value)
        if (undoStack.size > maxUndo) undoStack.removeFirst()
        _state.value = redoStack.removeLast()
        (canUndo as MutableStateFlow).value = true
        (canRedo as MutableStateFlow).value = redoStack.isNotEmpty()
        saveNow()
    }

    // V4.3.1 — bug fix: collapse rapid onChange events into a single DB
    // write and serialise every write through this mutex. Previously the
    // autosave used `GlobalScope` and only updated `_state.id` AFTER the
    // insert completed, so two rapid keystrokes each saw `id=0` and
    // produced two duplicate rows. Worse, because the writes ran on
    // GlobalScope, navigating away mid-save could race the UI read.
    //
    // V5.1 — saveNow() / flushPendingPush() now run on [applicationScope]
    // (Application lifecycle) instead of viewModelScope. The previous
    // viewModelScope version was cancelled the moment the user popped
    // the back stack, which made the "always persist on back" guarantee
    // racy: a fast back press right after a keystroke could drop the
    // final edit. Application-scoped jobs survive ViewModel destruction
    // and complete naturally.
    private val saveMutex = Mutex()
    private var pendingSaveJob: Job? = null
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun loadNote(noteId: Long?) {
        if (isInitialized) return

        if (noteId == null || noteId == 0L) {
            // V5.1.2 — empty activeBlockId here so the screen's first
            // tap on the auto-created TextBlock sets it. The "Compose"
            // FAB on the list already routes the user to a fresh note.
            _state.value = NoteEditState(isNew = true)
            originalTitle = ""
            originalContent = ""
            originalColor = 0
            isInitialized = true
            return
        }

        viewModelScope.launch {
            val note = repository.getNoteById(noteId)
            if (note != null) {
                val parsedBlocks = parseBlocksFromContent(note.content)
                val legacyUris = parseImageUris(note.imageUris)
                val blocks = if (parsedBlocks.isNotEmpty()) {
                    parsedBlocks
                } else if (legacyUris.isNotEmpty()) {
                    legacyUris.map { ContentBlock.ImageBlock(uri = it) }
                } else if (note.content.isNotEmpty()) {
                    listOf<ContentBlock>(ContentBlock.TextBlock(text = note.content))
                } else {
                    emptyList()
                }
                // V5.1.2 — auto-activate the FIRST text block on load so
                // the toolbar's per-block ops (font size, alignment,
                // list type, checklist, background) work without forcing
                // the user to tap into a block first. This was a major
                // UX trap in V5.1; every toolbar tap returned a snack
                // "Tap into a text block first" until the user clicked.
                val firstTextId = blocks.firstOrNull { it is ContentBlock.TextBlock }?.id
                _state.value = NoteEditState(
                    id = note.id,
                    title = note.title,
                    blocks = blocks,
                    isSaved = true,
                    isNew = false,
                    backgroundColor = note.backgroundColor,
                    activeBlockId = firstTextId
                )
                originalTitle = note.title
                originalContent = serializeBlocksToContent(blocks)
                originalColor = note.backgroundColor
            } else {
                _state.value = NoteEditState(isNew = true)
                originalTitle = ""
                originalContent = ""
                originalColor = 0
            }
            isInitialized = true
        }

        // F-05 — watch the image-upload retry counter while the note
        // is open so the user learns that their image never made it
        // to the server, instead of silently wondering why the image
        // is missing on their other device.
        //
        // The counter lives in [com.example.notes.util.ImageRetryStore]
        // and is bumped by the background push pass every ~5 s while
        // the note is deferred. We poll it every 6 s (just above the
        // push debounce) and fire a one-shot snackbar the first time
        // we observe the "gave up" transition.
        viewModelScope.launch {
            var announced = false
            while (isActive) {
                delay(IMAGE_UPLOAD_POLL_MS)
                val current = _state.value
                if (current.id == 0L) continue
                val localUris = current.blocks
                    .filterIsInstance<ContentBlock.ImageBlock>()
                    .map { it.uri }
                    .filter { com.example.notes.util.LocalImageUri.isLocalImageUri(it, appContext) }
                if (localUris.isEmpty()) {
                    _imageUploadFailed.value = false
                    _failedImageUris.value = emptySet()
                    continue
                }
                val gaveUp = com.example.notes.util.ImageRetryStore
                    .noteGaveUpImage(appContext, current.id)
                _imageUploadFailed.value = gaveUp
                _failedImageUris.value = if (gaveUp) localUris.toSet() else emptySet()
                if (gaveUp && !announced) {
                    announced = true
                    emitMessage(
                        "Rasm yuklanmadi, qayta urinib ko'ring"
                    )
                }
            }
        }
    }

    /**
     * F-05 — re-upload a single image that previously failed, and
     * reset its retry budget so the push pass stops deferring the
     * note.
     *
     * The local file is still on disk (the upload failed, it wasn't
     * deleted), so we can hand the very same `file://` URI back to
     * [ApiClient.uploadImage]. On success the block is rewritten to
     * the public R2 URL, the note is saved (which bumps
     * `dirtyRevision`), and the next push ships the real URL to the
     * server — replacing the `pending-image:` marker.
     */
    fun retryImageUpload(localUri: String) {
        val current = _state.value
        if (current.id == 0L) return
        // Clear the failed flag immediately so the badge
        // disappears while the retry is in flight.
        _imageUploadFailed.value = false
        _failedImageUris.value = _failedImageUris.value - localUri
        applicationScope.launch {
            try {
                val result = ApiClient.uploadImage(localUri.toUri(), appContext)
                result.fold(
                    onSuccess = { response ->
                        Logger.d("NoteEditVM",
                            "retryImageUpload: OK for $localUri -> ${response.url}")
                        val fresh = _state.value
                        val rewritten = fresh.blocks.map { block ->
                            if (block is ContentBlock.ImageBlock &&
                                block.uri == localUri
                            ) {
                                block.copy(uri = response.url)
                            } else block
                        }
                        if (rewritten != fresh.blocks) {
                            _state.value = fresh.copy(blocks = rewritten)
                            // The saved content now carries the public
                            // URL, so the next push replaces the
                            // `pending-image:` marker with it.
                            saveNow()
                        }
                        // The image is on the server now — clear the
                        // retry budget so the note isn't deferred
                        // any more.
                        com.example.notes.util.ImageRetryStore
                            .reset(appContext, fresh.id)
                        emitMessage("Rasm yuklandi")
                    },
                    onFailure = { e ->
                        Logger.w("NoteEditVM",
                            "retryImageUpload FAILED for $localUri", e)
                        // Put the flag back so the badge reappears.
                        _imageUploadFailed.value = true
                        _failedImageUris.value = _failedImageUris.value + localUri
                        emitMessage("Rasm yuklanmadi, qayta urinib ko'ring")
                    }
                )
            } catch (t: Throwable) {
                Logger.w("NoteEditVM", "retryImageUpload crashed", t)
                _imageUploadFailed.value = true
                _failedImageUris.value = _failedImageUris.value + localUri
                emitMessage("Rasm yuklanmadi, qayta urinib ko'ring")
            }
        }
    }

    /* ---------- Title ---------- */

    /**
     * V5.0.2 — title changes now arrive as [TextFieldValue] so the
     * IME composition range is visible. We skip auto-save while the
     * composition is still active — predictive keyboards insert text
     * mid-composition and would otherwise save half-formed variants
     * ("test" → "Test matn" → "Test " before the user accepted one).
     * The save is rescheduled the moment composition ends (see
     * [scheduleAutosaveIfReady]).
     */
    fun onTitleChange(newValue: TextFieldValue) {
        val title = newValue.text
        if (_state.value.title != title) pushUndoSnapshot()
        _state.value = _state.value.copy(title = title)
        scheduleAutosaveIfReady(composing = newValue.composition != null)
    }

    /** Backwards-compat overload for callers that still pass a raw String. */
    fun onTitleChange(title: String) {
        onTitleChange(TextFieldValue(title, TextRange(title.length)))
    }

    /* ---------- Block editing ---------- */

    fun onBlockValueChange(blockId: String, newValue: TextFieldValue) {
        val current = _state.value
        val targetBlock = current.blocks.firstOrNull { it.id == blockId } as? ContentBlock.TextBlock
        // Only push an undo snapshot when the *visible* text actually
        // changes — pure selection/cursor moves shouldn't pollute the stack.
        // Also skip while the IME composition is active — the user is still
        // mid-edit and a snapshot here would lock in partial text.
        if (targetBlock != null && targetBlock.text != newValue.text &&
            newValue.composition == null
        ) {
            pushUndoSnapshot()
        }
        val newBlocks = current.blocks.map { block ->
            if (block is ContentBlock.TextBlock && block.id == blockId) {
                val aligned = StyleRanges.shiftOnTextChange(
                    block.styleRanges, block.text, newValue.text
                )
                block.copy(
                    text = newValue.text,
                    selection = newValue.selection,
                    styleRanges = aligned
                )
            } else block
        }
        _state.value = current.copy(
            blocks = newBlocks,
            activeBlockId = blockId
        )
        scheduleAutosaveIfReady(composing = newValue.composition != null)
    }

    fun onBlockFocused(blockId: String) {
        val current = _state.value
        if (current.activeBlockId == blockId) return
        _state.value = current.copy(activeBlockId = blockId)
    }

    /* ---------- Block-level mutations: images, empty-block seed ---------- */

    /**
     * V5.1 — image pipeline rewrite. Previously the function just stored
     * the picker's [Uri] string (a `content://` URI) as the block's
     * image source. That URI is device-local and breaks the moment the
     * note syncs to another device — `content://` references don't
     * resolve there, so the image appears as a grey placeholder.
     *
     * The fix is to **upload first**, then store the *public* URL the
     * backend returns. We still insert the local URI block right away so
     * the user sees their selection immediately; once the upload
     * completes the block is rewritten with the canonical URL and a
     * second save is fired so the synced note carries the public URL.
     *
     * The upload runs on [applicationScope] (not viewModelScope) so
     * navigating away doesn't cancel the network round-trip.
     */
    fun addImageUri(uri: String) {
        val current = _state.value
        if (uri.isBlank()) {
            Logger.w("NoteEditVM", "addImageUri: empty URI ignored")
            return
        }

        Logger.d("NoteEditVM", "addImageUri: starting with uri=$uri")

        // Idempotency: don't append a duplicate of the same image the
        // user just picked (the picker occasionally re-fires its callback).
        if (current.blocks.lastOrNull() is ContentBlock.ImageBlock &&
            (current.blocks.last() as ContentBlock.ImageBlock).uri == uri
        ) {
            Logger.d("NoteEditVM", "addImageUri: duplicate $uri — skipping")
            return
        }

        // V5.2.1 — copy the picked content:// URI into the app's private
        // files directory before doing anything else. content:// grants
        // expire when the picking activity goes away, so the renderer
        // would show a "broken image" placeholder once the user reopens
        // the note from the list. A file:// URI under [Context.filesDir]
        // survives process death and even app uninstall via auto-backup.
        val pickedUri = uri.toUri()
        val localFile: java.io.File? = try {
            appContext.contentResolver.openInputStream(pickedUri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) return@use null
                // Use the byte-sniffer so PNG / WebP / GIF / HEIC all land
                // with the right extension on disk (see ImageSniffer). The
                // previous PNG/JPG-only check mis-classified every other
                // format as `.img` and produced confusing "img_xxx.img"
                // names that no other app could open.
                //
                // D3 — use a UUID for the filename instead of
                // `System.currentTimeMillis()`. Two images added in the
                // same millisecond (rare but real — IME suggestions can
                // accept two picks in one frame) would otherwise collide
                // and the second `writeBytes` would silently truncate
                // the first.
                //
                // F-28 — write into `filesDir/images/` (not the root of
                // `filesDir`) so the entire directory can be excluded
                // from cloud backup / device transfer via the Android
                // backup XML rules. The prefix (which images use in
                // their URI) is `file://<filesDir>/images/img_<UUID>.<ext>`
                // and matches the `localFileUriPrefix` used by
                // [NotesSyncRepository] to defer pushes while a local
                // image is in flight.
                val ext = com.example.notes.util.ImageSniffer
                    .detect(bytes)?.ext ?: "img"
                val imagesDir = java.io.File(appContext.filesDir, "images")
                    .apply { if (!exists()) mkdirs() }
                val out = java.io.File(
                    imagesDir,
                    "img_${java.util.UUID.randomUUID()}.$ext"
                )
                out.writeBytes(bytes)
                out
            }
        } catch (t: Throwable) {
            Logger.w("NoteEditVM", "addImageUri: copy to local failed for $uri", t)
            null
        }
        if (localFile == null) {
            emitMessage("Couldn't read the selected image — please try a different one.")
            return
        }
        val localUri = Uri.fromFile(localFile).toString()

        Logger.d("NoteEditVM", "addImageUri: copied to $localUri (${localFile.length()} bytes)")

        val list = current.blocks.toMutableList()
        val last = list.lastOrNull()
        if (last is ContentBlock.TextBlock && last.text.isEmpty()) {
            list.removeAt(list.lastIndex)
        }
        list.add(ContentBlock.ImageBlock(uri = localUri))
        list.add(ContentBlock.TextBlock())
        _state.value = current.copy(blocks = list)
        saveNow()

        // V5.1.4 — capture the URI for the upload coroutine so the
        // onSuccess rewrite matches the exact string the user picked
        // (Uri.toString() vs the human-facing Uri representation can
        // differ on some OEMs).
        val localUriString = localUri
        applicationScope.launch {
            try {
                Logger.d(
                    "NoteEditVM",
                    "addImageUri: launching R2 upload for $localUriString"
                )
                AuthStore.getActive(appContext)
                val result = ApiClient.uploadImage(localUriString.toUri(), appContext)
                result.fold(
                    onSuccess = { response ->
                        Logger.d(
                            "NoteEditVM",
                            "addImageUri: upload OK local=$localUriString -> url=${response.url}"
                        )
                        val fresh = _state.value
                        val rewritten = fresh.blocks.map { block ->
                            if (block is ContentBlock.ImageBlock && block.uri == localUriString) {
                                block.copy(uri = response.url)
                            } else block
                        }
                        if (rewritten != fresh.blocks) {
                            _state.value = fresh.copy(blocks = rewritten)
                            // saveNow() funnels through
                            // performSaveLocked which ends with
                            // pushLocalChangesDebounced() — that
                            // already covers the new dirty bit, so we
                            // don't need a separate push trigger here.
                            saveNow()
                        }
                    },
                    onFailure = { e ->
                        // Log.e (not Logger.w) so the failure shows up
                        // even when log filters are set to ERROR-only.
                        // The throwable's `message` is the masked
                        // IOException body the server returned, so we
                        // already get the HTTP status + body preview
                        // without the token being in the URL.
                        android.util.Log.e(
                            "Upload",
                            "addImageUri: upload FAILED for $localUriString; " +
                                "cause: ${e.message ?: e.javaClass.simpleName}",
                            e
                        )
                        emitMessage(
                            "Image upload failed — kept on this device only. " +
                                "It won't appear on other devices until you reconnect."
                        )
                    }
                )
            } catch (t: Throwable) {
                Logger.w(
                    "NoteEditVM",
                    "addImageUri: upload crashed for $localUriString",
                    t
                )
                emitMessage("Image upload crashed — kept on this device only.")
            }
        }
    }

    fun addEmptyTextBlockAtEnd() {
        val current = _state.value
        if (current.blocks.isEmpty() ||
            current.blocks.lastOrNull() is ContentBlock.ImageBlock
        ) {
            val newBlocks = current.blocks + ContentBlock.TextBlock()
            _state.value = current.copy(blocks = newBlocks)
            saveNow()
        }
    }

    fun removeImageBlock(blockId: String) {
        val current = _state.value
        val newBlocks = current.blocks.filterNot {
            it is ContentBlock.ImageBlock && it.id == blockId
        }
        if (newBlocks.size == current.blocks.size) return
        _state.value = current.copy(blocks = newBlocks)
        saveNow()
    }

    /* ---------- Selection-aware formatting (V2.6) ---------- */

    private inline fun mutateActiveBlock(
        transform: (ContentBlock.TextBlock) -> ContentBlock.TextBlock
    ): List<ContentBlock> {
        val current = _state.value
        val activeId = current.activeBlockId ?: return current.blocks
        return current.blocks.map { block ->
            if (block is ContentBlock.TextBlock && block.id == activeId) {
                transform(block)
            } else block
        }
    }

    /**
     * V5.1.2 — split into two helpers:
     *
     *  - [commitActiveBlock] is used by **per-character** operations
     *    (Bold, Italic, Underline, Strikethrough, text color). These
     *    need an explicit cursor position; if [activeBlockId] is null
     *    the only sensible fallback is the first text block in the
     *    document. We still emit a soft snackbar so the user knows
     *    "the toolbar is targeting the first block, not yours".
     *
     *  - [commitPerBlock] is used by **block-level** operations (font
     *    size, alignment, list type, checklist toggle, background
     *    color of a single block). These don't depend on cursor
     *    position — they apply to the active block as a whole, with a
     *    first-text-block fallback so the buttons are immediately
     *    usable after opening a note.
     */
    private fun commitActiveBlock(transform: (ContentBlock.TextBlock) -> ContentBlock.TextBlock) {
        val current = _state.value
        val (activeId, setActive) = resolveTargetBlock(
            current = current,
            snackOnFallback = true
        ) ?: return
        val newBlocks = current.blocks.map { block ->
            if (block is ContentBlock.TextBlock && block.id == activeId) {
                transform(block)
            } else block
        }
        _state.value = current.copy(
            blocks = newBlocks,
            activeBlockId = if (setActive) activeId else current.activeBlockId
        )
        pushUndoSnapshot()
        scheduleAutosave()
    }

    /**
     * Block-level ops don't need a cursor position. Use the active
     * block, or fall back to the first text block (auto-activating it).
     */
    private fun commitPerBlock(transform: (ContentBlock.TextBlock) -> ContentBlock.TextBlock) {
        val current = _state.value
        val (activeId, setActive) = resolveTargetBlock(
            current = current,
            snackOnFallback = false
        ) ?: return
        val newBlocks = current.blocks.map { block ->
            if (block is ContentBlock.TextBlock && block.id == activeId) {
                transform(block)
            } else block
        }
        _state.value = current.copy(
            blocks = newBlocks,
            activeBlockId = if (setActive) activeId else current.activeBlockId
        )
        pushUndoSnapshot()
        scheduleAutosave()
    }

    /**
     * @return Pair(target block id, whether to update activeBlockId)
     *         or `null` if there is no text block to target.
     */
    private fun resolveTargetBlock(
        current: NoteEditState,
        snackOnFallback: Boolean
    ): Pair<String, Boolean>? {
        val explicit = current.activeBlockId
        val explicitStillExists = explicit != null && current.blocks.any {
            it is ContentBlock.TextBlock && it.id == explicit
        }
        if (explicitStillExists) return explicit to false

        val firstText = current.blocks.firstOrNull { it is ContentBlock.TextBlock }
            ?: run {
                Logger.w(
                    "NoteEditVM",
                    "resolveTargetBlock: document has no text blocks at all"
                )
                if (snackOnFallback) {
                    emitMessage("Add some text before using the toolbar.")
                }
                return null
            }
        if (snackOnFallback) {
            // Per-character ops usually need the cursor; if there's no
            // cursor yet we target the first block but tell the user.
            emitMessage(
                "Tap into a text block to position the toolbar there."
            )
        }
        return firstText.id to true
    }

    /**
     * V5.1.3 — re-introduce the V2.6 "type-mode" anchor toggle that
     * matches the user's mental model: tap the button *before*
     * writing to arm the style, the next typed characters pick it
     * up, tap again to disarm. Already-typed text keeps its style
     * because each char's style is locked at composition time.
     *
     * Concretely:
     *  - **Selection** → toggle across the highlighted range (unchanged).
     *  - **Collapsed cursor** → flip the pending anchor's flag for
     *    [attribute]. The block.text+styleRanges serialised by
     *    [dropAnchors] on save, but during editing the anchor at
     *    `cursor.start` carries the "what does my next keystroke
     *    look like?" state. [StyleRanges.shiftOnTextChange] grows
     *    the anchor the moment a character is inserted at its
     *    position, then the next inserted character lands past
     *    the anchor (no styling).
     *
     * Result on screen:
     *   - "Tap B + type hello + tap B + type world" →
     *     "**hello** world" (cancelling the "armed" style does
     *     NOT erase text that's already been typed).
     */
    fun toggleBold() {
        commitActiveBlock { block ->
            block.copy(styleRanges = togglePending(block, StyleAttribute.BOLD))
        }
    }

    fun toggleItalic() {
        commitActiveBlock { block ->
            block.copy(styleRanges = togglePending(block, StyleAttribute.ITALIC))
        }
    }

    fun toggleUnderline() {
        commitActiveBlock { block ->
            block.copy(styleRanges = togglePending(block, StyleAttribute.UNDERLINE))
        }
    }

    fun toggleStrikethrough() {
        commitActiveBlock { block ->
            block.copy(styleRanges = togglePending(block, StyleAttribute.STRIKETHROUGH))
        }
    }

    /**
     * V5.1.3 — pure "type-mode" toggle for collapsed selections. The
     * pending anchor at [ContentBlock.TextBlock.selection].start holds
     * the armed attributes; flipping one flag turns it on/off without
     * touching any text already on screen. Tooltips / button-active
     * state in the toolbar read this same position so the UI always
     * reflects "what typing will produce".
     */
    private fun togglePending(
        block: ContentBlock.TextBlock,
        attribute: StyleAttribute
    ): List<StyleRange> {
        val sel = block.selection
        if (!sel.collapsed) {
            // Selection: explicit toggle across the highlighted range.
            return StyleRanges.toggleAttribute(block.styleRanges, sel, attribute)
        }
        val current = StyleRanges.attributeAt(block.styleRanges, sel.start)
        val turnOn = when (attribute) {
            StyleAttribute.BOLD -> !current.bold
            StyleAttribute.ITALIC -> !current.italic
            StyleAttribute.UNDERLINE -> !current.underline
            StyleAttribute.STRIKETHROUGH -> !current.strikethrough
            else -> false
        }
        val pending = when (attribute) {
            StyleAttribute.BOLD -> current.toggled(attribute, on = turnOn)
            StyleAttribute.ITALIC -> current.toggled(attribute, on = turnOn)
            StyleAttribute.UNDERLINE -> current.toggled(attribute, on = turnOn)
            StyleAttribute.STRIKETHROUGH -> current.toggled(attribute, on = turnOn)
            else -> current
        }
        return StyleRanges.anchorAtCursor(block.styleRanges, sel.start, pending)
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_'

    /**
     * V5.1.3 — apply color in "type-mode" (same UX as Bold/Italic):
     * tap a color → next typed chars use it; tap **the same color**
     * again → off (back to Auto). Picking a different color while one
     * is already armed switches to the new color. Already-typed text
     * keeps its own color.
     */
    fun setTextColorIndex(index: Int) {
        val clamped = index.coerceIn(0, 5)
        commitActiveBlock { block ->
            val sel = block.selection
            val ranges = if (sel.collapsed) {
                val current = StyleRanges.attributeAt(block.styleRanges, sel.start)
                // type-mode toggle: clicking the active color -> off
                val targetColor = if (current.colorIndex == clamped) 0 else clamped
                StyleRanges.anchorAtCursor(
                    block.styleRanges,
                    sel.start,
                    current.withColor(targetColor)
                )
            } else {
                StyleRanges.setColor(block.styleRanges, sel, clamped)
            }
            block.copy(styleRanges = ranges)
        }
    }

    fun setTextAlign(align: Int) {
        val clamped = align.coerceIn(0, 2)
        // V5.1.2 — block-level op; works without an explicit cursor.
        commitPerBlock { it.copy(textAlign = clamped) }
    }

    /**
     * V5.1.7 — apply list type to the line containing the caret. Falls
     * back to the block default when [block] is single-line so the
     * single-line UX matches V5.1 behaviour. Mirrors the WYSIWYG
     * "relevant part" semantics of B/I/U.
     */
    fun setListType(type: Int) {
        val clamped = type.coerceIn(0, 2)
        val current = _state.value
        val block = current.blocks.firstOrNull { it is ContentBlock.TextBlock }
            as? ContentBlock.TextBlock ?: return
        // V5.2.2 — bullet, numbered and checklist are mutually
        // exclusive. Toggling list on clears any checklist flag on the
        // same block so the renderer only has to look at one switch.
        applyBlockUpdate { target ->
            if (target.id != block.id) return@applyBlockUpdate target
            if (block.text.count { it == '\n' } == 0) {
                target.copy(
                    listType = clamped,
                    isChecklist = false
                )
            } else {
                val lineIdx = cursorLineIndex(block)
                val newMap = target.listTypesByLine.toMutableMap()
                val cur = newMap[lineIdx] ?: target.listType
                if (cur == clamped && !target.isChecklist) {
                    newMap.remove(lineIdx)
                } else {
                    newMap[lineIdx] = clamped
                }
                target.copy(
                    listTypesByLine = newMap,
                    isChecklist = false
                )
            }
        }
    }

    /**
     * V5.2.2 — toggle the checklist flag. We do NOT touch listType
     * here so a user can have a numbered list whose rows happen to be
     * checkable. Toggling the checklist on also drops any per-line
     * list override for the active line so the renderer only has to
     * look at one switch at a time.
     */
    fun toggleChecklist() {
        applyBlockUpdate { target ->
            if (target.id != currentTextBlock()?.id) return@applyBlockUpdate target
            target.copy(isChecklist = !target.isChecklist)
        }
    }

    fun toggleChecklistLine(blockId: String, lineIndex: Int) {
        val current = _state.value
        val newBlocks = current.blocks.map { block ->
            if (block is ContentBlock.TextBlock && block.id == blockId) {
                val lines = block.text.split('\n')
                if (lineIndex !in lines.indices) return@map block
                val updated = lines.toMutableList()
                val line = updated[lineIndex]
                updated[lineIndex] = when {
                    line.startsWith("- [x] ") || line.startsWith("- [X] ") ->
                        "- [ ] " + line.removePrefix("- [x] ").removePrefix("- [X] ")
                    line.startsWith("- [ ] ") ->
                        "- [x] " + line.removePrefix("- [ ] ")
                    else -> "- [x] $line"
                }
                val newText = updated.joinToString("\n")
                val aligned = StyleRanges.shiftOnTextChange(block.styleRanges, block.text, newText)
                block.copy(text = newText, styleRanges = aligned)
            } else block
        }
        _state.value = current.copy(blocks = newBlocks)
        saveNow()
    }

    /* ---------- Text size (V5.1.7: per-line override) ---------- */

    /**
     * Compute the index of the line that contains the caret in the
     * block. Lines are separated by '\n'. Empty blocks return 0.
     */
    private fun cursorLineIndex(block: ContentBlock.TextBlock): Int {
        if (block.text.isEmpty()) return 0
        val cursor = block.selection.start.coerceIn(0, block.text.length)
        var idx = 0
        for (i in 0 until cursor) if (block.text[i] == '\n') idx++
        return idx
    }

    fun decreaseTextSize() {
        val block = currentTextBlock() ?: return
        applyFontSize(block, block.fontSize - 1)
    }

    fun increaseTextSize() {
        val block = currentTextBlock() ?: return
        applyFontSize(block, block.fontSize + 1)
    }

    fun setTextSizeIndex(index: Int) {
        val clamped = index.coerceIn(0, TEXT_SIZE_VALUES.lastIndex)
        val block = currentTextBlock() ?: return
        applyFontSize(block, clamped)
    }

    /**
     * V5.1.7 — apply a font-size to the line the caret is on. Single-
     * line blocks map the change to the block default (so the value
     * stays visible at the top of [TextBlockEditor]); multi-line blocks
     * route the change through [fontSizesByLine] so only the active
     * line is affected. Renderer support for the per-line override is
     * deliberately deferred to V5.2 — for now the value is saved but
     * the active line uses the block default; cursor movement onto an
     * overridden line will set the toolbar chip accordingly.
     */
    private fun applyFontSize(block: ContentBlock.TextBlock, newVal: Int) {
        val clamped = newVal.coerceIn(0, TEXT_SIZE_VALUES.lastIndex)
        val lineCount = block.text.count { it == '\n' } + 1
        val lineIdx = cursorLineIndex(block)
        applyBlockUpdate { target ->
            if (target.id != block.id) return@applyBlockUpdate target
            if (lineCount == 1) {
                target.copy(fontSize = clamped)
            } else {
                val newMap = target.fontSizesByLine.toMutableMap()
                if (clamped == target.fontSize) newMap.remove(lineIdx)
                else newMap[lineIdx] = clamped
                target.copy(fontSizesByLine = newMap)
            }
        }
    }

    /** Resolve the first text block (the toolbar targets it when no
     *  cursor is active). Used as the "where do I apply a block-level
     *  op?" helper for A−/A+. */
    private fun currentTextBlock(): ContentBlock.TextBlock? =
        _state.value.blocks.firstOrNull { it is ContentBlock.TextBlock } as? ContentBlock.TextBlock

    /**
     * Apply [transform] to the block the toolbar targets. Skips the
     * activeBlockId check that [commitPerBlock] enforces — these
     * toolbar actions do not need a cursor.
     */
    private fun applyBlockUpdate(transform: (ContentBlock.TextBlock) -> ContentBlock) {
        val current = _state.value
        val block = current.blocks.firstOrNull { it is ContentBlock.TextBlock } as? ContentBlock.TextBlock
            ?: return
        _state.value = current.copy(
            blocks = current.blocks.map { b ->
                if (b is ContentBlock.TextBlock && b.id == block.id) transform(b) else b
            }
        )
        pushUndoSnapshot()
        saveNow()
    }

    /* ---------- Background color ---------- */

    fun setBackgroundColorIndex(index: Int) {
        val clamped = index.coerceIn(0, 7)
        val current = _state.value
        if (current.backgroundColor == clamped) return
        pushUndoSnapshot()
        _state.value = current.copy(backgroundColor = clamped, isDirty = true)
        saveNow()
    }

    /* ---------- Persistence (V4.3.1) ----------
     *
     * Two entry points:
     *   - scheduleAutosave() — debounced 300ms after any change.
     *   - saveNow()          — flush immediately (Save button, back press).
     *
     * Both funnel through [performSaveLocked] which is wrapped in a
     * Mutex so concurrent calls are serialised and the state.id is
     * always updated *before* the next read.
     */

    /**
     * V5.0.2 — IME-composition-aware autosave.
     *
     * @param composing true if the IME is still mid-composition (the
     *                 suggestion strip is open and the keyboard may still
     *                 replace the composition range with a different
     *                 string). We must NOT save in that state — the
     *                 "committed" text the user types is not yet final,
     *                 and saving on every prediction update creates
     *                 duplicate notes that only differ by the
     *                 half-formed IME state (see the "Test matn" /
     *                 "Te" / "Test " bug).
     *
     *                 When the user accepts a suggestion / types space
     *                 / moves focus, `composing` becomes false on the
     *                 next onChange — that's when we re-arm the
     *                 debounced save.
     *
     * If [composing] is true, this is a no-op; the next non-composing
     * onChange will trigger the save.
     */
    private fun scheduleAutosaveIfReady(
        composing: Boolean,
        debounceMs: Long = POST_COMPOSE_DEBOUNCE_MS
    ) {
        if (composing) return
        pendingSaveJob?.cancel()
        // V5.1 — use applicationScope, not viewModelScope. Keystrokes
        // late in the debounce window often land just as the user pops
        // the back stack — viewModelScope gets cancelled and the edit
        // is dropped. applicationScope survives.
        pendingSaveJob = applicationScope.launch {
            delay(debounceMs)
            performSaveLocked()
        }
    }

    private fun scheduleAutosave() {
        // Backwards-compat entry point used by callers that don't pass
        // composition info. Falls back to the post-composition debounce
        // so the user-visible UX is preserved.
        scheduleAutosaveIfReady(composing = false, debounceMs = POST_COMPOSE_DEBOUNCE_MS)
    }

    fun saveNow() {
        pendingSaveJob?.cancel()
        // V5.1 — see scheduleAutosaveIfReady for the rationale.
        pendingSaveJob = applicationScope.launch {
            performSaveLocked()
        }
    }

    fun consumeSaveStatus() {
        if (_saveStatus.value is SaveStatus.Saved) {
            _saveStatus.value = SaveStatus.Idle
        }
    }

    private suspend fun performSaveLocked() {
        saveMutex.withLock {
            val current = _state.value
            val hasAnyImage = current.blocks.any { it is ContentBlock.ImageBlock }
            val hasAnyText = current.blocks.any {
                it is ContentBlock.TextBlock && it.text.isNotEmpty()
            }
            // B10 — empty-note save policy:
            //   - Brand-new note (id == 0L) with no title, no text and
            //     no image: skip. There's nothing to save and we'd
            //     otherwise create a row of just `deletedAt = NULL,
            //     title = ''` that pollutes the list.
            //   - Existing note (id != 0L) with no title, no text and
            //     no image: SAVE anyway. The user explicitly cleared
            //     it; the next push will mirror the empty state to the
            //     server, and an accidental back-tap won't silently
            //     restore the previous content.
            val isCompletelyEmpty = current.title.isBlank() && !hasAnyImage && !hasAnyText
            if (isCompletelyEmpty && current.id == 0L) {
                return@withLock
            }

            _saveStatus.value = SaveStatus.Saving

            val now = System.currentTimeMillis()
            val cleanedBlocks = current.blocks.map { b ->
                if (b is ContentBlock.TextBlock) b.copy(styleRanges = StyleRanges.dropAnchors(b.styleRanges))
                else b
            }
            val serializedContent = serializeBlocksToContent(cleanedBlocks)
            val uris = extractUrisFromContent(serializedContent)
            val lastTextBlock = cleanedBlocks.lastOrNull { it is ContentBlock.TextBlock }
                as? ContentBlock.TextBlock

            val finalId: Long
            try {
                finalId = if (current.id == 0L) {
                    // INSERT — userId is stamped by the repository based on
                    // the active session, serverId stays null, isFavorite /
                    // isTrashed / isPinned default to false (a brand new
                    // note has none of these flags set).
                    val toInsert = NoteEntity(
                        id = 0L,
                        title = current.title,
                        content = serializedContent,
                        createdAt = now,
                        updatedAt = now,
                        isFavorite = false,
                        isTrashed = false,
                        backgroundColor = current.backgroundColor,
                        fontWeight = if (lastTextBlock?.styleRanges?.any { it.bold } == true) 1 else 0,
                        textSize = lastTextBlock?.fontSize ?: 1,
                        imageUris = joinImageUris(uris),
                        serverId = null,
                        userId = 0,
                        isPinned = false
                    )
                    val id = repository.insertNote(toInsert)
                    Logger.d(
                        "NoteEditVM",
                        "performSaveLocked: inserted id=$id activeUserId=${repository.activeUserId()}"
                    )
                    id
                } else {
                    // UPDATE — load the previous row so we preserve
                    // serverId, userId, isFavorite, isPinned, createdAt.
                    // Re-creating NoteEntity with just the editor fields
                    // would reset every one of those to its default,
                    // dropping the user from their account and losing the
                    // synced-serverId.
                    val existing = repository.getNoteById(current.id)
                    val merged = (existing
                        ?: NoteEntity(
                            id = current.id,
                            title = "",
                            content = "",
                            createdAt = now,
                            updatedAt = now
                        )
                        ).copy(
                        title = current.title,
                        content = serializedContent,
                        updatedAt = now,
                        backgroundColor = current.backgroundColor,
                        fontWeight = if (lastTextBlock?.styleRanges?.any { it.bold } == true) 1 else 0,
                        textSize = lastTextBlock?.fontSize ?: 1,
                        imageUris = joinImageUris(uris)
                    )
                    repository.updateNote(merged)
                    Logger.d(
                        "NoteEditVM",
                        "performSaveLocked: updated id=${merged.id} activeUserId=${repository.activeUserId()} userId=${merged.userId} serverId=${merged.serverId}"
                    )
                    merged.id
                }
            } catch (t: Throwable) {
                Logger.w("NoteEditVM", "performSaveLocked: save failed", t)
                _saveStatus.value = SaveStatus.Failed(t.message ?: "Save failed")
                return@withLock
            }

            // Promote state.id and originalContent BEFORE any other code
            // (or the next onChange) can read them — this is the core of
            // the V4.3.1 race-condition fix.
            _state.value = _state.value.copy(
                id = finalId,
                isNew = false,
                isSaved = true,
                isDirty = false
            )
            originalTitle = current.title
            originalContent = serializedContent
            originalColor = current.backgroundColor

            _saveStatus.value = SaveStatus.Saved(finalId)

            // V5.0.2 — debounced push coalesces auto-save bursts into a
            // single network round-trip. The editor's onBack() also calls
            // flushPendingPush() so the user doesn't wait 5s for the final
            // edit to reach the server when they leave the screen.
            syncRepository.pushLocalChangesDebounced()
        }
    }

    fun flushPendingStyles() {
        pendingSaveJob?.cancel()
        // V5.1 — applicationScope so an immediate save survives a
        // navigation race where the user pops the back stack right after.
        pendingSaveJob = applicationScope.launch {
            performSaveLocked()
        }
    }

    /**
     * V5.0.2 — flush any pending debounced push immediately. Called from
     * the editor's onBack() so the user doesn't have to wait the full
     * 5-second debounce window for their last edit to reach the server.
     *
     * V5.1 — runs on applicationScope so the network round-trip is not
     * cancelled when the editor ViewModel is cleared by navController.popBackStack().
     */
    fun flushPendingPush() {
        applicationScope.launch {
            try {
                val pushed = syncRepository.flushPendingPush()
                if (pushed > 0) {
                    Logger.d("NoteEditVM", "flushPendingPush: $pushed note(s)")
                }
            } catch (t: Throwable) {
                Logger.w("NoteEditVM", "flushPendingPush failed", t)
            }
        }
    }

    /**
     * V4.4 — soft-delete the current note (move to trash). The local row
     * is updated immediately, then we attempt a server push so other
     * devices see the note disappear. A failed push is logged but not
     * surfaced to the user — the next manual Sync will retry.
     */
    fun moveToTrash() {
        val current = _state.value
        if (current.id == 0L) return
        viewModelScope.launch {
            try {
                // Load the existing row so we preserve userId, serverId,
                // isFavorite, isPinned and createdAt — see performSaveLocked
                // for the same pattern.
                val existing = repository.getNoteById(current.id)
                if (existing == null) return@launch
                val serializedContent = serializeBlocksToContent(
                    current.blocks.map { b ->
                        if (b is ContentBlock.TextBlock) {
                            b.copy(styleRanges = StyleRanges.dropAnchors(b.styleRanges))
                        } else b
                    }
                )
                repository.moveToTrash(
                    existing.copy(
                        title = current.title,
                        content = serializedContent,
                        updatedAt = System.currentTimeMillis(),
                        backgroundColor = current.backgroundColor
                    )
                )
                // Trash is a server-visible state — flush immediately so
                // other devices see it within the same session.
                try {
                    val pushed = syncRepository.flushPendingPush()
                    if (pushed > 0) {
                        Logger.d("NoteEditVM", "moveToTrash: pushed $pushed note(s)")
                    }
                } catch (t: Throwable) {
                    Logger.w("NoteEditVM", "moveToTrash: push failed", t)
                }
            } catch (t: Throwable) {
                Logger.w("NoteEditVM", "moveToTrash: local move failed", t)
                _saveStatus.value = SaveStatus.Failed(t.message ?: "Delete failed")
            }
        }
    }

    class Factory(
        private val repository: NoteRepository,
        private val syncRepository: NotesSyncRepository,
        private val applicationScope: CoroutineScope,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NoteEditViewModel::class.java)) {
                return NoteEditViewModel(
                    repository = repository,
                    syncRepository = syncRepository,
                    applicationScope = applicationScope,
                    appContext = appContext
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
