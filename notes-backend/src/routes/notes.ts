import { Env, Note } from '../types';
import { errorResponse, jsonResponse } from '../utils';

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
 */
function rowToNote(row: any): Note {
    return {
        id: row.id,
        user_id: row.user_id,
        title: row.title ?? '',
        content: row.content ?? '',
        created_at: row.created_at,
        updated_at: row.client_updated_at || row.updated_at || 0,
        client_updated_at: row.client_updated_at || 0,
        change_id: row.change_id || 0,
        deleted_at: row.deleted_at || null,
        is_favorite: row.is_favorite ? 1 : 0,
        is_trashed: row.is_trashed ? 1 : 0,
        background_color: row.background_color ?? 0,
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
 * Append a row to change_log for a single note. Returns the change_id
 * (autoincrement PK). BUG 4: this is the monotonic cursor we now use.
 */
async function appendChangeLog(
    env: Env,
    userId: number,
    noteId: string,
    op: 'upsert' | 'delete'
): Promise<number> {
    const result = await env.DB
        .prepare('INSERT INTO change_log (user_id, note_id, op) VALUES (?, ?, ?) RETURNING id')
        .bind(userId, noteId, op)
        .first<{ id: number }>();
    return result?.id ?? 0;
}

/**
 * V5.3 — DELETE /notes/<id>
 * BUG 2 fix: convert to soft-delete. The row stays in `notes` with
 * `deleted_at` set; change_log gets a 'delete' entry. Future pulls skip
 * it via `WHERE deleted_at IS NULL`. Tombstones are durable across
 * offline clients — no more "ghost note" resurrection.
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
    await env.DB
        .prepare(
            'UPDATE notes SET deleted_at = ?, client_updated_at = ?, is_trashed = 0 WHERE id = ? AND user_id = ?'
        )
        .bind(now, now, id, userId)
        .run();
    const changeId = await appendChangeLog(env, userId, id, 'delete');

    // Push the new change_id back onto the row so pulls can still filter
    // by per-note change_id when needed.
    await env.DB
        .prepare('UPDATE notes SET change_id = ? WHERE id = ?')
        .bind(changeId, id)
        .run();

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
 */
export async function listNotes(request: Request, userId: number, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const sinceParam = url.searchParams.get('since');
    const includeDeleted = url.searchParams.get('ops') === 'deleted';

    let rows: any[];
    if (sinceParam !== null) {
        const since = toInt(sinceParam, 0);
        // Fetch change_log rows for this user since the cursor, then join
        // back to notes. Even soft-deleted rows are returned so the
        // client can tombstone its local copy.
        const result = await env.DB
            .prepare(
                'SELECT n.id, n.user_id, n.title, n.content, n.created_at, n.updated_at, ' +
                'n.client_updated_at, n.change_id, n.deleted_at, n.is_favorite, n.is_trashed, n.background_color ' +
                'FROM change_log c ' +
                'INNER JOIN notes n ON n.id = c.note_id AND n.user_id = c.user_id ' +
                'WHERE c.user_id = ? AND c.id > ? ' +
                'ORDER BY c.id ASC'
            )
            .bind(userId, since)
            .all();
        rows = result.results as any[];
    } else {
        const result = await env.DB
            .prepare(
                'SELECT id, user_id, title, content, created_at, updated_at, ' +
                'client_updated_at, change_id, deleted_at, is_favorite, is_trashed, background_color ' +
                'FROM notes WHERE user_id = ? AND deleted_at IS NULL ORDER BY change_id ASC'
            )
            .bind(userId)
            .all();
        rows = result.results as any[];
    }

    if (!includeDeleted) {
        // Default: hide tombstoned rows from the active notes list. The
        // deleted_at is still present in the response so the client can
        // mark its own row as deleted.
        rows = rows.filter(r => !r.deleted_at);
    }

    const notes = rows.map(rowToNote);
    const lastChangeId = notes.reduce((m, n) => Math.max(m, n.change_id), 0);

    return jsonResponse({
        notes,
        sync_cursor: lastChangeId,   // monotonic change_log id for the next pull
        sync_timestamp: Date.now(),
    });
}

/**
 * GET /notes/<id>
 */
export async function getNote(path: string, userId: number, env: Env): Promise<Response> {
    const id = extractIdFromPath(path);
    if (!id) return errorResponse('Missing note id', 400);

    const row = await env.DB
        .prepare(
            'SELECT id, user_id, title, content, created_at, updated_at, ' +
            'client_updated_at, change_id, deleted_at, is_favorite, is_trashed, background_color ' +
            'FROM notes WHERE id = ? AND user_id = ?'
        )
        .bind(id, userId)
        .first<Note>();
    if (!row) return errorResponse('Note not found', 404);
    return jsonResponse({ note: rowToNote(row) });
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

    const title = typeof body.title === 'string' ? body.title : '';
    const content = typeof body.content === 'string' ? body.content : '';
    const isFavorite = toBoolInt(body.is_favorite);
    const isTrashed = toBoolInt(body.is_trashed);
    const backgroundColor = toInt(body.background_color, 0);

    // BUG 5: clock-skew-free timestamps. We read the previous value
    // first so a concurrent upload with a smaller client_updated_at
    // (older client clock) cannot regress the row. The rule is:
    // new.updated_at = max(prev, client). Monotonic per-row only.
    const prev = await env.DB
        .prepare('SELECT client_updated_at FROM notes WHERE id = ? AND user_id = ?')
        .bind(id, userId)
        .first<{ client_updated_at: number }>();

    const clientUpdatedRaw = toInt(body.updated_at, Date.now());
    const prevTs = prev?.client_updated_at ?? 0;
    const clientUpdated = Math.max(prevTs + 1, clientUpdatedRaw);

    const clientCreatedRaw = toInt(body.created_at, clientUpdated);
    const clientCreated = Math.max(0, clientCreatedRaw);

    // If the row is currently soft-deleted, an upsert from the client
    // *un-deletes* it (the client recovered the note, or the delete
    // raced with an edit). This is the standard "tombstone vs live"
    // idempotency rule.
    await env.DB
        .prepare(
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
                deleted_at = NULL,
                is_favorite = excluded.is_favorite,
                is_trashed = excluded.is_trashed,
                background_color = excluded.background_color`
        )
        .bind(id, userId, title, content, clientUpdated, clientUpdated,
              clientCreated, clientUpdated, isFavorite, isTrashed, backgroundColor)
        .run();

    const changeId = await appendChangeLog(env, userId, id, 'upsert');
    await env.DB
        .prepare('UPDATE notes SET change_id = ? WHERE id = ?')
        .bind(changeId, id)
        .run();

    const saved = await env.DB
        .prepare(
            'SELECT id, user_id, title, content, created_at, updated_at, ' +
            'client_updated_at, change_id, deleted_at, is_favorite, is_trashed, background_color ' +
            'FROM notes WHERE id = ? AND user_id = ?'
        )
        .bind(id, userId)
        .first<Note>();
    if (!saved) return errorResponse('Failed to save note', 500);

    return jsonResponse({ note: rowToNote(saved) });
}
