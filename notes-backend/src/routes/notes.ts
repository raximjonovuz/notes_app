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
 * Map a D1 row to a Note object. updated_at takes the max of client-supplied
 * value and server-side now, so client-side edits are preserved across sync.
 */
function rowToNote(row: any): Note {
    return {
        id: row.id,
        user_id: row.user_id,
        title: row.title ?? '',
        content: row.content ?? '',
        created_at: row.created_at,
        updated_at: row.updated_at,
        is_favorite: row.is_favorite ? 1 : 0,
        is_trashed: row.is_trashed ? 1 : 0,
        background_color: row.background_color ?? 0,
    };
}

/**
 * GET /notes?since=<timestamp_ms>
 * Returns: { notes: [...], sync_timestamp: <now_ms> }
 */
export async function listNotes(request: Request, userId: number, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const sinceParam = url.searchParams.get('since');
    let rows: any[];
    if (sinceParam !== null) {
        const since = toInt(sinceParam, 0);
        const result = await env.DB
            .prepare(
                'SELECT id, user_id, title, content, created_at, updated_at, is_favorite, is_trashed, background_color ' +
                'FROM notes WHERE user_id = ? AND updated_at > ? ORDER BY updated_at DESC'
            )
            .bind(userId, since)
            .all<Note>();
        rows = result.results as any[];
    } else {
        const result = await env.DB
            .prepare(
                'SELECT id, user_id, title, content, created_at, updated_at, is_favorite, is_trashed, background_color ' +
                'FROM notes WHERE user_id = ? ORDER BY updated_at DESC'
            )
            .bind(userId)
            .all<Note>();
        rows = result.results as any[];
    }
    const notes = rows.map(rowToNote);
    return jsonResponse({
        notes,
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
            'SELECT id, user_id, title, content, created_at, updated_at, is_favorite, is_trashed, background_color ' +
            'FROM notes WHERE id = ? AND user_id = ?'
        )
        .bind(id, userId)
        .first<Note>();
    if (!row) return errorResponse('Note not found', 404);
    return jsonResponse({ note: rowToNote(row) });
}

/**
 * POST /notes
 * Body: { id, title, content, is_favorite?, is_trashed?, background_color?, created_at?, updated_at? }
 * Creates or upserts a note. Returns the saved note.
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

    // Basic UUID format check (8-4-4-4-12). Optional but cheap.
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
        return errorResponse('Invalid note id (must be UUID)', 400);
    }

    const title = typeof body.title === 'string' ? body.title : '';
    const content = typeof body.content === 'string' ? body.content : '';
    const isFavorite = toBoolInt(body.is_favorite);
    const isTrashed = toBoolInt(body.is_trashed);
    const backgroundColor = toInt(body.background_color, 0);
    const now = Date.now();

    // Client-supplied timestamps take priority; fall back to server now.
    const clientCreated = toInt(body.created_at, now);
    const clientUpdated = toInt(body.updated_at, now);
    const createdAt = Math.max(0, clientCreated);
    const updatedAt = Math.max(clientCreated, clientUpdated, now);

    // Upsert: keep the larger updated_at so concurrent writes don't regress.
    await env.DB
        .prepare(
            `INSERT INTO notes (id, user_id, title, content, created_at, updated_at, is_favorite, is_trashed, background_color)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                content = excluded.content,
                updated_at = MAX(notes.updated_at, excluded.updated_at),
                is_favorite = excluded.is_favorite,
                is_trashed = excluded.is_trashed,
                background_color = excluded.background_color`
        )
        .bind(id, userId, title, content, createdAt, updatedAt, isFavorite, isTrashed, backgroundColor)
        .run();

    const saved = await env.DB
        .prepare(
            'SELECT id, user_id, title, content, created_at, updated_at, is_favorite, is_trashed, background_color ' +
            'FROM notes WHERE id = ? AND user_id = ?'
        )
        .bind(id, userId)
        .first<Note>();
    if (!saved) return errorResponse('Failed to save note', 500);

    return jsonResponse({ note: rowToNote(saved) });
}

/**
 * DELETE /notes/<id>
 */
export async function deleteNote(path: string, userId: number, env: Env): Promise<Response> {
    const id = extractIdFromPath(path);
    if (!id) return errorResponse('Missing note id', 400);

    // Verify the note belongs to this user.
    const existing = await env.DB
        .prepare('SELECT id FROM notes WHERE id = ? AND user_id = ?')
        .bind(id, userId)
        .first<{ id: string }>();
    if (!existing) return errorResponse('Note not found', 404);

    await env.DB
        .prepare('DELETE FROM notes WHERE id = ? AND user_id = ?')
        .bind(id, userId)
        .run();

    return jsonResponse({ deleted: id });
}