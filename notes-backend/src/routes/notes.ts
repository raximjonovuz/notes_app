import { Env, Note } from '../types';
import { errorResponse, jsonResponse } from '../utils';
import { CACHE_TTL, cacheGet, cacheSet, currentCacheVersion, invalidateUserCache, noteListKey, noteSingleKey } from '../cache';

/**
 * Coerce a value to a number, with a fallback. Used for query / body parsing.
 */
function toInt(v: any, fallback: number): number {
    const n = typeof v === 'string' ? parseInt(v, 10) : typeof v === 'number' ? v : NaN;
    return Number.isFinite(n) ? n : fallback;
}

/**
 * Coerce a value to 0 or 1.
 */
function toBoolInt(v: any): 0 | 1 {
    return v ? 1 : 0;
}

/**
 * Map a D1 row to a Note object. server_updated_at =
 * client_updated_at (we keep the client's clock) — BUG 5 fix.
 * deleted_at may be non-null (BUG 2 soft-delete).
 *
 * A4: created_at should come from client_created_at when present.
 * The pre-A4 INSERTs wrote `created_at = clientUpdated` (wrong — that
 * is the modification time, not the creation time). The migration
 * 0004 back-fills client_created_at for those rows; here we just
 * prefer the client value when it is non-zero.
 *
 * A10: `user_id` is intentionally OMITTED from the returned object —
 * the client never needs its own user id echoed back, and stripping it
 * at the mapping boundary means no handler can accidentally leak a
 * cross-user `user_id` if a future refactor returns the raw row.
 */
function rowToNote(row: any): Note {
    const clientCreated = row.client_created_at ?? 0;
    return {
        id: row.id,
        title: row.title ?? '',
        content: row.content ?? '',
        created_at: clientCreated > 0 ? clientCreated : (row.created_at ?? 0),
        updated_at: row.client_updated_at || row.updated_at || 0,
        client_updated_at: row.client_updated_at || 0,
        change_id: row.change_id || 0,
        deleted_at: row.deleted_at || null,
        is_favorite: row.is_favorite ? 1 : 0,
        is_trashed: row.is_trashed ? 1 : 0,
        background_color: row.background_color ?? 0,
    };
}

/* ------------------------------------------------------------------ */
/* A10 — input validation for POST /notes                              */
/* ------------------------------------------------------------------ */

const NOTE_TITLE_MAX = 500;
const NOTE_CONTENT_MAX_BYTES = 1_048_576;          // 1 MB UTF-8
const NOTE_BG_COLOR_MIN = 0;
const NOTE_BG_COLOR_MAX = 7;
const NOTE_FUTURE_TS_TOLERANCE_MS = 24 * 60 * 60 * 1000;

export interface ValidatedNoteBody {
    title: string;
    content: string;
    background_color: number;
    /** client-supplied updated_at, clamped to (now + 24h) if too far in the future */
    updated_at: number;
}

/**
 * A10: validate (and clamp) a POST /notes body before it reaches the DB.
 *
 * Hard 400 errors:
 *   - title longer than 500 chars (UTF-16 length; the spec talks in chars,
 *     not bytes — the size of the underlying storage is already bounded
 *     by content ≤ 1 MB anyway).
 *   - content UTF-8 byte length > 1 MB.
 *   - background_color outside the 0..7 range the client palette supports.
 *
 * Soft clamps (no 400):
 *   - `updated_at` more than 24h in the future is treated as a clock-skew
 *     artefact and clamped to Date.now(). Past timestamps are kept as-is
 *     — the existing monotonic-per-row logic in upsertNote already raises
 *     them to `prevTs + 1` so a regressed clock can't undo a newer row.
 *
 * Returns `{ok: true, ...validated}` on success, `{ok: false, message}`
 * on a hard validation error.
 */
export function validateNoteBody(body: any): { ok: true; value: ValidatedNoteBody } | { ok: false; message: string } {
    const title = typeof body?.title === 'string' ? body.title : '';
    const content = typeof body?.content === 'string' ? body.content : '';
    const background_color = typeof body?.background_color === 'number' && Number.isFinite(body.background_color)
        ? body.background_color
        : (typeof body?.background_color === 'string' ? parseInt(body.background_color, 10) : 0);

    if (title.length > NOTE_TITLE_MAX) {
        return { ok: false, message: `title too long (max ${NOTE_TITLE_MAX} characters)` };
    }
    if (new TextEncoder().encode(content).length > NOTE_CONTENT_MAX_BYTES) {
        return { ok: false, message: `content too large (max ${NOTE_CONTENT_MAX_BYTES} bytes UTF-8)` };
    }
    if (background_color < NOTE_BG_COLOR_MIN || background_color > NOTE_BG_COLOR_MAX) {
        return { ok: false, message: `background_color must be between ${NOTE_BG_COLOR_MIN} and ${NOTE_BG_COLOR_MAX}` };
    }

    // Clamp future-dated updated_at to the wall clock (with a 24h
    // tolerance for legitimate clock drift). Past timestamps are kept.
    const rawUpdatedAt = typeof body?.updated_at === 'number' && Number.isFinite(body.updated_at)
        ? body.updated_at
        : (typeof body?.updated_at === 'string' ? parseInt(body.updated_at, 10) : NaN);
    const safeUpdatedAt = Number.isFinite(rawUpdatedAt) ? rawUpdatedAt : Date.now();
    const ceiling = Date.now() + NOTE_FUTURE_TS_TOLERANCE_MS;
    const updated_at = safeUpdatedAt > ceiling ? Date.now() : safeUpdatedAt;

    return {
        ok: true,
        value: {
            title,
            content,
            background_color,
            updated_at,
        },
    };
}

/**
 * Extract the note id from a path like "/notes/<id>".
 */
function extractIdFromPath(path: string): string | null {
    const prefix = '/notes/';
    if (!path.startsWith(prefix)) return null;
    const id = path.substring(prefix.length).trim();
    if (!id) return null;
    return id;
}

/**
 * V5.3 — DELETE /notes/<id>
 * BUG 2 fix: convert to soft-delete. The row stays in `notes` with
 * `deleted_at` set; change_log gets a 'delete' entry. Future pulls skip
 * it via `WHERE deleted_at IS NULL`. Tombstones are durable across
 * offline clients — no more "ghost note" resurrection.
 *
 * A1: ownership check is done via the user-scoped SELECT below, so
 * a different user cannot learn whether the UUID exists (we 404 in
 * both cases). The trailing `change_id` UPDATE is also user-scoped
 * to avoid a second IDOR vector.
 */
export async function deleteNote(path: string, userId: number, env: Env): Promise<Response> {
    const id = extractIdFromPath(path);
    if (!id) return errorResponse('Missing note id', 400);

    const existing = await env.DB
        .prepare('SELECT id, deleted_at FROM notes WHERE id = ? AND user_id = ?')
        .bind(id, userId)
        .first<{ id: string; deleted_at: number | null }>();
    if (!existing) return errorResponse('Note not found', 404);
    if (existing.deleted_at) {
        // Idempotent: already deleted on the server.
        return jsonResponse({ deleted: id, already: true });
    }

    const now = Date.now();
    // A2: one atomic batch — soft-delete the row, record the change,
    // and back-fill the row's change_id from the change_log row we just
    // inserted. The subquery in the third statement reads the change_log
    // row from the second statement (sequential within a D1 batch).
    await env.DB.batch([
        env.DB.prepare(
            // A6: wipe title/content so the tombstone carries no PII.
            // The row survives only as a deletion marker; client already
            // has the real text locally and retention will hard-delete
            // the row after 90 days.
            'UPDATE notes SET deleted_at = ?, client_updated_at = ?, is_trashed = 0, ' +
            'title = ?, content = ? ' +
            'WHERE id = ? AND user_id = ?'
        ).bind(now, now, '', '', id, userId),
        env.DB.prepare(
            'INSERT INTO change_log (user_id, note_id, op) VALUES (?, ?, ?)'
        ).bind(userId, id, 'delete'),
        env.DB.prepare(
            'UPDATE notes SET change_id = (SELECT MAX(id) FROM change_log WHERE user_id = ? AND note_id = ?) WHERE id = ? AND user_id = ?'
        ).bind(userId, id, id, userId),
    ]);

    // Read the change_id back for the response. The subquery above wrote
    // the same value into the row, so this is just a confirmation read.
    const changeIdRow = await env.DB
        .prepare('SELECT change_id FROM notes WHERE id = ? AND user_id = ?')
        .bind(id, userId)
        .first<{ change_id: number }>();
    const changeId = changeIdRow?.change_id ?? 0;

    // A9: bump the per-user cache version. Old versioned keys are left
    // to expire on their own — readers that captured the previous
    // version will let their cached body finish its TTL.
    await invalidateUserCache(env, userId);

    return jsonResponse({ deleted: id, change_id: changeId });
}

/**
 * V5.3 — GET /notes?since=<changeId>
 * BUG 4 fix: cursor is a monotonic change_log.id, not a millisecond
 * timestamp. Misses nothing (no same-ms drop) and ignores clock skew.
 *
 * If `since` is omitted, returns the full non-deleted table for this user
 * (used on first sync). `ops=deleted` filters tombstones — the client
 * uses this to learn about deletes even after the soft-deleted row has
 * been GC'd by retention.
 *
 * A5 changes:
 *   - `since` requests now use a per-note aggregation so a note touched
 *     multiple times since the cursor is returned exactly once with its
 *     current state (no more duplicates from the change_log JOIN).
 *   - Tombstones are returned for `since` requests even without the
 *     `ops=deleted` flag — the client needs them to know about deletes.
 *     Only the full (`since`-less) sync hides tombstones.
 *   - `sync_cursor` is the MAX(change_log.id) of rows that matched
 *     BEFORE tombstone filtering; on an empty response we echo the
 *     client-supplied `since` so the next call resumes correctly.
 *   - `limit` (default 200, max 500) caps the response size; clients
 *     keep pulling while `has_more` is true.
 *
 * Caching:
 *   - `since` requests always hit D1 (incremental, never repeated).
 *   - Full-list requests with `ops=deleted` are bypassed (rare, expensive).
 *   - Full-list without `ops=deleted` is cached in Redis (60s TTL).
 *     Invalidation happens in upsertNote / deleteNote on the affected
 *     user-scoped list key.
 */
const LIST_DEFAULT_LIMIT = 200;
const LIST_MAX_LIMIT = 500;

// A6: retention window for change_log and soft-deleted notes. After this
// many days the rows are hard-deleted by `runRetention` (driven by the
// cron trigger in wrangler.toml). Used both by the deletion job and by
// the 410 Gone check in listNotes to detect a stale `since` cursor.
const RETENTION_MS = 1000 * 60 * 60 * 24 * 90;

export async function listNotes(request: Request, userId: number, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const sinceParam = url.searchParams.get('since');
    const includeDeleted = url.searchParams.get('ops') === 'deleted';
    const limitParam = toInt(url.searchParams.get('limit'), LIST_DEFAULT_LIMIT);
    const limit = Math.max(1, Math.min(LIST_MAX_LIMIT, limitParam));

    // Only the "full sync" path is cacheable: incremental pulls and the
    // tombstones-only listing must always see fresh data.
    const cacheable = sinceParam === null && !includeDeleted;
    // A9: capture the version up-front. If a write bumps the version
    // mid-request, the cache GET below will still use the OLD version
    // (we read the body before checking), and the response we cache
    // will land in the OLD namespace — the next request will already
    // see the NEW version and miss cleanly.
    const ver = await currentCacheVersion(env, userId);
    if (cacheable) {
        const cached = await cacheGet<{ body: unknown }>(env, noteListKey(userId, ver));
        if (cached?.body) {
            return jsonResponse(cached.body as any);
        }
    }

    let rows: any[];
    let lastChangeId: number;
    let hasMore: boolean;

    if (sinceParam !== null) {
        const since = toInt(sinceParam, 0);

        // A6: 410 Gone when the client's cursor points into a range that
        // retention has already deleted. We compare `since` to the smallest
        // surviving change_log id for THIS user that is still inside the
        // 90-day retention window. If `since` is below it, the rows it
        // refers to are gone and the client must resync from scratch.
        // `since === 0` is the "first sync" marker and must not 410.
        if (since > 0) {
            const cutoff = Date.now() - RETENTION_MS;
            const oldest = await env.DB
                .prepare(
                    'SELECT id FROM change_log WHERE user_id = ? AND ts > ? ' +
                    'ORDER BY id ASC LIMIT 1'
                )
                .bind(userId, cutoff)
                .first<{ id: number }>();
            if (oldest && since < oldest.id) {
                return jsonResponse({ resync: true }, 410);
            }
        }

        // For each note touched since the cursor, fetch its CURRENT row
        // state (post all the change_log entries) plus the highest
        // change_log id that touched it. One row per note → no duplicates.
        // We pull `limit + 1` so we can detect `has_more` without an
        // extra COUNT round-trip.
        const result = await env.DB
            .prepare(
                'WITH per_note AS ( ' +
                '  SELECT note_id, MAX(id) AS max_change_id ' +
                '  FROM change_log WHERE user_id = ? AND id > ? ' +
                '  GROUP BY note_id ' +
                ') ' +
                'SELECT n.id, n.user_id, n.title, n.content, n.created_at, n.updated_at, ' +
                '       n.client_updated_at, n.client_created_at, n.change_id, n.deleted_at, ' +
                '       n.is_favorite, n.is_trashed, n.background_color, ' +
                '       p.max_change_id AS log_change_id ' +
                'FROM per_note p ' +
                'INNER JOIN notes n ON n.id = p.note_id AND n.user_id = ? ' +
                'ORDER BY p.max_change_id ASC ' +
                'LIMIT ?'
            )
            .bind(userId, since, userId, limit + 1)
            .all();
        const all = result.results as any[];
        hasMore = all.length > limit;
        if (hasMore) all.length = limit;
        rows = all;
        // sync_cursor is the max change_log id we SAW, regardless of
        // tombstone filtering — so the next pull resumes from the right
        // place. If empty, echo the client's `since` (don't reset to 0,
        // that would replay everything).
        lastChangeId = rows.reduce((m, r) => Math.max(m, r.log_change_id ?? 0), since);
    } else {
        // Full sync — only active notes. No tombstones here, no duplicates
        // either (one row per note by definition).
        const result = await env.DB
            .prepare(
                'SELECT id, user_id, title, content, created_at, updated_at, ' +
                'client_updated_at, client_created_at, change_id, deleted_at, ' +
                'is_favorite, is_trashed, background_color ' +
                'FROM notes WHERE user_id = ? AND deleted_at IS NULL ' +
                'ORDER BY change_id ASC LIMIT ?'
            )
            .bind(userId, limit + 1)
            .all();
        const all = result.results as any[];
        hasMore = all.length > limit;
        if (hasMore) all.length = limit;
        rows = all;
        lastChangeId = rows.reduce((m, r) => Math.max(m, r.change_id ?? 0), 0);
    }

    // The full-sync path already excluded tombstones in SQL; the
    // `since` path returns them so the client can mark its local copy
    // deleted (and learns about deletes from other devices). The
    // `ops=deleted` flag on a full sync is preserved for backwards
    // compatibility but no longer changes the behaviour (full sync
    // never returns tombstones).
    if (sinceParam === null) {
        rows = rows.filter(r => !r.deleted_at);
    }

    const notes = rows.map(rowToNote);
    const body: any = {
        notes,
        sync_cursor: lastChangeId,   // monotonic change_log id for the next pull
        sync_timestamp: Date.now(),
        has_more: hasMore,
    };
    if (sinceParam !== null) {
        body.limit = limit;
    }

    if (cacheable) {
        await cacheSet(env, noteListKey(userId, ver), { body }, CACHE_TTL.noteList);
    }

    return jsonResponse(body);
}

/**
 * GET /notes/<id>
 * Cached in Redis for `CACHE_TTL.noteSingle`. Invalidated by upsertNote /
 * deleteNote on the affected (userId, noteId) key.
 */
export async function getNote(path: string, userId: number, env: Env): Promise<Response> {
    const id = extractIdFromPath(path);
    if (!id) return errorResponse('Missing note id', 400);

    // A9: version-captured read.
    const ver = await currentCacheVersion(env, userId);
    const cached = await cacheGet<{ body: unknown }>(env, noteSingleKey(userId, ver, id));
    if (cached?.body) {
        return jsonResponse(cached.body as any);
    }

    const row = await env.DB
        .prepare(
            'SELECT id, user_id, title, content, created_at, updated_at, ' +
            'client_updated_at, change_id, deleted_at, is_favorite, is_trashed, background_color ' +
            'FROM notes WHERE id = ? AND user_id = ?'
        )
        .bind(id, userId)
        .first<any>();
    if (!row) return errorResponse('Note not found', 404);

    const body = { note: rowToNote(row) };
    await cacheSet(env, noteSingleKey(userId, ver, id), { body }, CACHE_TTL.noteSingle);
    return jsonResponse(body);
}

/**
 * POST /notes
 * BUG 5 fix: client supplies the updated_at via body. We store it as
 * `client_updated_at` and use it for tie-breaks instead of Date.now().
 * BUG 1 fix (server side): the row stays "alive" until the client
 * explicitly DELETEs (soft) — there is no longer a path where a
 * `is_favorite` flip on a previously-synced note can fail to propagate,
 * because push is now keyed on dirtyRevision (>0), not on
 * `serverId IS NULL`.
 *
 * Idempotent on `id` (UUID). Conflict-resolution feedback is the client's
 * job — this endpoint always overwrites with the latest client_updated_at.
 */
export async function upsertNote(request: Request, userId: number, env: Env): Promise<Response> {
    let body: any;
    try {
        body = await request.json();
    } catch {
        return errorResponse('Invalid JSON body', 400);
    }
    if (!body || typeof body !== 'object') {
        return errorResponse('Invalid request body', 400);
    }

    const id = typeof body.id === 'string' ? body.id.trim() : '';
    if (!id) return errorResponse('Missing note id (UUID required)', 400);
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
        return errorResponse('Invalid note id (must be UUID)', 400);
    }

    // A10: validate + clamp the body BEFORE we touch the DB so we never
    // write garbage even when the body would round-trip cleanly through
    // D1's parameter binding (D1 will happily store a 4 MB content blob
    // otherwise, and `background_color: -999` would too).
    const v = validateNoteBody(body);
    if (!v.ok) {
        return errorResponse(v.message, 400);
    }
    const { title, content, background_color: backgroundColor } = v.value;
    const isFavorite = toBoolInt(body.is_favorite);
    const isTrashed = toBoolInt(body.is_trashed);

    // A3: optional conflict-detection inputs. Old clients omit them and
    // keep the legacy last-write-wins behaviour; new clients send
    // `base_change_id` (the last `change_id` they have seen for this
    // note) and may explicitly `undelete: true` to recover a tombstoned
    // note.
    const baseChangeId = typeof body.base_change_id === 'number' && Number.isFinite(body.base_change_id)
        ? body.base_change_id
        : null;
    const undelete = body.undelete === true;

    // A1: IDOR guard. Look up the row by primary key (id is the UUID)
    // and reject early if it belongs to a different user. We deliberately
    // do NOT filter by user_id here so we can distinguish "doesn't exist"
    // from "exists but owned by someone else" — both surface as 404 to
    // the client (no existence leak), but only the latter needs the
    // explicit guard.
    //
    // The same SELECT also returns the prior timestamp (BUG 5), the
    // tombstone state, and the current `change_id` (A3 conflict
    // detection), so we don't need a second round-trip.
    const existing = await env.DB
        .prepare('SELECT id, user_id, title, content, created_at, updated_at, ' +
                 'client_updated_at, client_created_at, change_id, deleted_at, ' +
                 'is_favorite, is_trashed, background_color ' +
                 'FROM notes WHERE id = ?')
        .bind(id)
        .first<any>();

    if (existing && existing.user_id !== userId) {
        return errorResponse('Note not found', 404);
    }

    // A3: if the client sent a base_change_id and the server has moved
    // past it, refuse the write so the client can merge. Returning the
    // current server state lets the client do a 3-way merge.
    if (existing && baseChangeId !== null && existing.change_id > baseChangeId) {
        return jsonResponse({
            error: 'Conflict',
            conflict: true,
            note: rowToNote(existing),
        }, 409);
    }

    // A3: un-delete is allowed only when the client explicitly asks
    // (`undelete: true`) or has no idea the note was deleted
    // (legacy client that omits `base_change_id`). A client that
    // *knows* about the tombstone (sends `base_change_id`) but does
    // not opt in must not silently resurrect the note.
    const shouldUndelete = undelete || baseChangeId === null;

    // BUG 5: clock-skew-free timestamps. We read the previous value
    // first so a concurrent upload with a smaller client_updated_at
    // (older client clock) cannot regress the row. The rule is:
    // new.updated_at = max(prev, client). Monotonic per-row only.
    //
    // A10: `clientUpdatedRaw` is the value validateNoteBody already
    // clamped (any timestamp > now + 24h has been pulled down to now).
    const prevTs = existing?.client_updated_at ?? 0;
    const clientUpdatedRaw = v.value.updated_at;
    const clientUpdated = Math.max(prevTs + 1, clientUpdatedRaw);

    const clientCreatedRaw = toInt(body.created_at, clientUpdated);
    const clientCreated = Math.max(0, clientCreatedRaw);

    // If the row is currently soft-deleted, an upsert from the client
    // *un-deletes* it (the client recovered the note, or the delete
    // raced with an edit). This is the standard "tombstone vs live"
    // idempotency rule.
    //
    // A1: the ON CONFLICT ... DO UPDATE has `WHERE user_id = excluded.user_id`
    // as a defense in depth — if a TOCTOU race lets another user create a
    // row with the same UUID between the SELECT above and the INSERT here,
    // the UPDATE branch will refuse to overwrite the foreign row and the
    // INSERT path will instead fail with a UNIQUE constraint (still safe).
    //
    // A2: all three writes (note upsert, change_log insert, change_id
    // back-fill) are wrapped in a single DB.batch so a partial failure
    // can't leave a change_log row pointing at a stale notes row.
    await env.DB.batch([
        env.DB.prepare(
            `INSERT INTO notes (id, user_id, title, content, created_at, updated_at,
                                client_created_at, client_updated_at, change_id,
                                deleted_at, is_favorite, is_trashed, background_color)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                content = excluded.content,
                client_updated_at = excluded.client_updated_at,
                client_created_at = excluded.client_created_at,
                updated_at = excluded.updated_at,
                created_at = excluded.created_at,
                deleted_at = CASE WHEN ? THEN NULL ELSE deleted_at END,
                is_favorite = excluded.is_favorite,
                is_trashed = excluded.is_trashed,
                background_color = excluded.background_color
             WHERE user_id = excluded.user_id`
        ).bind(id, userId, title, content,
               // A4: created_at must come from clientCreated, not
               // clientUpdated — the latter is the modification time.
               clientCreated, clientUpdated,
               clientCreated, clientUpdated,
               isFavorite, isTrashed, backgroundColor,
               // A3: 1 to un-delete, 0 to keep the tombstone.
               shouldUndelete ? 1 : 0),
        env.DB.prepare(
            'INSERT INTO change_log (user_id, note_id, op) VALUES (?, ?, ?)'
        ).bind(userId, id, 'upsert'),
        env.DB.prepare(
            'UPDATE notes SET change_id = (SELECT MAX(id) FROM change_log WHERE user_id = ? AND note_id = ?) WHERE id = ? AND user_id = ?'
        ).bind(userId, id, id, userId),
    ]);

    const saved = await env.DB
        .prepare(
            'SELECT id, user_id, title, content, created_at, updated_at, ' +
            'client_updated_at, change_id, deleted_at, is_favorite, is_trashed, background_color ' +
            'FROM notes WHERE id = ? AND user_id = ?'
        )
        .bind(id, userId)
        .first<any>();
    if (!saved) return errorResponse('Failed to save note', 500);

    // Bind any uploaded images belonging to this user whose r2_key is
    // referenced in the note content so orphan cleanup knows they are in use.
    if (content.length > 0) {
        const normalized = content.replace(/\\\//g, '/');
        const userImages = await env.DB
            .prepare('SELECT r2_key, note_id FROM images WHERE user_id = ? AND (note_id IS NULL OR note_id != ?)')
            .bind(userId, id)
            .all<{ r2_key: string; note_id: string | null }>();
        const toBind = ((userImages.results as Array<{ r2_key: string; note_id: string | null }>) || [])
            .filter(img => img.r2_key && normalized.includes(img.r2_key));
        for (const img of toBind) {
            await env.DB
                .prepare('UPDATE images SET note_id = ? WHERE user_id = ? AND r2_key = ?')
                .bind(id, userId, img.r2_key)
                .run();
        }
    }

    // A9: bump the per-user cache version. See deleteNote above.
    await invalidateUserCache(env, userId);

    return jsonResponse({ note: rowToNote(saved) });
}

/**
 * A6: retention / hard-delete.
 *
 * Called from the scheduled handler in `src/index.ts` (see wrangler.toml
 * `[triggers] crons`). Hard-deletes:
 *   - `change_log` rows whose `ts` is older than RETENTION_MS.
 *     Sync cursors older than this are impossible to serve — listNotes
 *     returns 410 Gone for them and tells the client to resync.
 *   - Soft-deleted `notes` rows whose `deleted_at` is older than
 *     RETENTION_MS. Active (non-tombstoned) notes are never touched.
 *
 * The two DELETE statements run sequentially. Either may be a no-op on
 * a given run; we still return the affected counts so the caller / logs
 * can see what happened. Sequential (not batched) so a long notes
 * cleanup does not block the change_log cleanup report.
 */
export async function runRetention(env: Env): Promise<{ changeLogDeleted: number; notesDeleted: number }> {
    const cutoff = Date.now() - RETENTION_MS;

    const changeLogResult = await env.DB
        .prepare('DELETE FROM change_log WHERE ts < ?')
        .bind(cutoff)
        .run();
    const changeLogDeleted = (changeLogResult.meta?.changes as number) ?? 0;

    const notesResult = await env.DB
        .prepare('DELETE FROM notes WHERE deleted_at IS NOT NULL AND deleted_at < ?')
        .bind(cutoff)
        .run();
    const notesDeleted = (notesResult.meta?.changes as number) ?? 0;

    return { changeLogDeleted, notesDeleted };
}
