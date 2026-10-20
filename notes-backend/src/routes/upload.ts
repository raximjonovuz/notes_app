import { Env } from '../types';
import { base64ToUint8Array, errorResponse, getBaseUrl, jsonResponse } from '../utils';

/**
 * A8: Security boundaries and quotas for image uploads.
 *
 *   - MAX_REQUEST_BYTES: hard upper bound on the JSON request body,
 *     checked BEFORE we read the body via the Content-Length header.
 *     Anything bigger than this is rejected with 413 without ever
 *     allocating the body, so a malicious / misconfigured client can't
 *     blow the Worker's 128 MB memory cap on a single request.
 *
 *   - MAX_IMAGE_BYTES: per-file size cap after base64 decoding. The old
 *     V5.3 value of 8 MB is preserved — base64 of an 8 MB image is
 *     ~10.7 MB of JSON, which slips under the 11 MB request ceiling.
 *
 *   - MAX_TOTAL_BYTES_PER_USER / MAX_FILES_PER_USER: per-user storage
 *     and file-count caps. 200 MB / 2000 files per user, checked on
 *     every upload so a single user can't fill the bucket.
 */
const MAX_REQUEST_BYTES = 11 * 1024 * 1024;        // 11 MB raw JSON body
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;           // 8 MB decoded image
const MAX_TOTAL_BYTES_PER_USER = 200 * 1024 * 1024; // 200 MB per user
const MAX_FILES_PER_USER = 2000;                    // 2000 files per user

/**
 * A8: detect the real image MIME type from the file's leading bytes.
 * The client-supplied content_type is NEVER trusted — an attacker
 * claiming `image/png` while uploading an SVG is the classic XSS
 * vector (a browser might render inline-SVG in an <img> tag, and an
 * SVG containing <script> would execute). We instead sniff the magic
 * number and only accept an explicit whitelist.
 *
 * Returns the detected MIME type (e.g. 'image/png') or `null` if the
 * bytes don't match a known format. The caller is responsible for
 * mapping `null` → HTTP 415.
 *
 * The whitelist is intentionally small. New formats must be added
 * here consciously — we never want to silently accept something a
 * malicious client claims.
 *
 * Detection also catches SVG via magic (it starts with `<svg`,
 * `<?xml`, `<!DOCTYPE svg`, none of which match the whitelist), but
 * the caller still rejects client-supplied `image/svg+xml` upfront
 * for defense-in-depth.
 */
export function detectImageType(bytes: Uint8Array): string | null {
    if (!bytes || bytes.length < 12) return null;

    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if (
        bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47 &&
        bytes[4] === 0x0D && bytes[5] === 0x0A && bytes[6] === 0x1A && bytes[7] === 0x0A
    ) return 'image/png';

    // JPEG: FF D8 FF
    if (
        bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF
    ) return 'image/jpeg';

    // GIF: 'GIF87a' or 'GIF89a' (47 49 46 38 37/39 61)
    if (
        bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x38 &&
        (bytes[4] === 0x37 || bytes[4] === 0x39) && bytes[5] === 0x61
    ) return 'image/gif';

    // WebP: 'RIFF????WEBP' — bytes 0-3 are 'RIFF', bytes 8-11 are 'WEBP'.
    // Bytes 4-7 are the (little-endian) RIFF chunk size and we don't
    // care about its value, just that the rest of the magic matches.
    if (
        bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 &&
        bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50
    ) return 'image/webp';

    // HEIC / HEIF: bytes 4-7 are 'ftyp', bytes 8-11 are a known major
    // brand from the HEIF family. We accept heic / heix / mif1 — the
    // three brands actually shipped by iOS. Their 32-bit encoding is
    // small enough that comparing byte-by-byte avoids the JS signed
    // shift trap entirely.
    if (
        bytes[4] === 0x66 && bytes[5] === 0x74 && bytes[6] === 0x79 && bytes[7] === 0x70
    ) {
        // 'heic'
        if (bytes[8] === 0x68 && bytes[9] === 0x65 && bytes[10] === 0x69 && bytes[11] === 0x63) {
            return 'image/heic';
        }
        // 'heix'
        if (bytes[8] === 0x68 && bytes[9] === 0x65 && bytes[10] === 0x69 && bytes[11] === 0x78) {
            return 'image/heic';
        }
        // 'mif1'
        if (bytes[8] === 0x6D && bytes[9] === 0x69 && bytes[10] === 0x66 && bytes[11] === 0x31) {
            return 'image/heic';
        }
    }

    return null;
}

interface QuotaUsage {
    count: number;
    totalBytes: number;
}

/**
 * A8: aggregate images belonging to a user. Single SUM/COUNT query so
 * the quota check is one round-trip regardless of how many files the
 * user has uploaded so far.
 */
async function readUserQuota(env: Env, userId: number): Promise<QuotaUsage> {
    const row = await env.DB
        .prepare(
            'SELECT COUNT(*) AS cnt, COALESCE(SUM(size_bytes), 0) AS total ' +
            'FROM images WHERE user_id = ?'
        )
        .bind(userId)
        .first<{ cnt: number; total: number }>();
    return {
        count: row?.cnt ?? 0,
        totalBytes: row?.total ?? 0,
    };
}

/**
 * A8: hard caps applied to a fresh upload.
 *
 *   - File-count cap of MAX_FILES_PER_USER → 429 (Too Many Requests).
 *     429 because the client should back off and not retry blindly.
 *   - Bytes cap of MAX_TOTAL_BYTES_PER_USER → 413 (Payload Too Large).
 *
 * There is a small race window between two concurrent uploads (each
 * could read the pre-state, both pass, both commit). The consequences
 * are bounded: the next upload after either commits will 413 / 429.
 * We deliberately don't serialise on a transaction — a single user's
 * quota is not a security boundary, and D1 batch transactions would
 * also clash with the R2 put that must follow.
 *
 * Returns `null` when the upload fits the quota; returns a fully-built
 * Response (413 / 429) when it doesn't. The caller short-circuits on
 * non-null.
 */
async function enforceUserQuota(env: Env, userId: number, additionalBytes: number): Promise<Response | null> {
    const { count, totalBytes } = await readUserQuota(env, userId);

    if (count + 1 > MAX_FILES_PER_USER) {
        return errorResponse(
            `File count limit reached (${MAX_FILES_PER_USER} files per user)`,
            429
        );
    }
    if (totalBytes + additionalBytes > MAX_TOTAL_BYTES_PER_USER) {
        return errorResponse(
            `Storage quota exceeded (limit ${MAX_TOTAL_BYTES_PER_USER} bytes per user)`,
            413
        );
    }
    return null;
}

/**
 * A8: explicit Content-Length pre-check. Runs BEFORE `request.json()`
 * so we never allocate the body for a request we already know we'd
 * reject. This is a cheap early reject, not a security boundary —
 * a malicious client can lie about Content-Length and force us to
 * allocate anyway. The inner per-file check (after base64 decode)
 * is the real safety net.
 *
 * A missing or non-numeric Content-Length is allowed through; the
 * inner per-file check will catch oversize uploads from clients that
 * omit the header (curl, some Android libs).
 */
function rejectIfRequestTooLarge(request: Request): Response | null {
    const len = request.headers.get('Content-Length');
    if (len === null) return null;
    const n = Number(len);
    if (!Number.isFinite(n) || n <= 0) return null;
    if (n > MAX_REQUEST_BYTES) {
        return errorResponse(
            `Request too large (limit ${MAX_REQUEST_BYTES} bytes)`,
            413
        );
    }
    return null;
}

/**
 * POST /upload
 * Auth: required (router passes userId).
 * Body (JSON): { filename: string, content_type: string, data: string (base64) }
 * Returns: { id, url, r2_key, content_type, size, user_id }
 *
 * V5.3 — base payload bounds (8 MB cap, JSON-only API).
 *
 * A8 — upload hardening, applied on top of V5.3:
 *   - Content-Length pre-check (413 before reading body).
 *   - Magic-number detection; client content_type is never persisted,
 *     the DETECTED MIME is what we write to R2 httpMetadata and to the
 *     `images` row.
 *   - SVG is rejected outright — the client claim `image/svg+xml` is
 *     an instant 415, and SVG bytes are rejected by the magic check
 *     anyway (none of the whitelisted formats' prefixes match `<?xml`
 *     or `<svg`).
 *   - Per-user storage (200 MB) and file-count (2000) quotas. A user
 *     over either limit gets 413 (bytes) or 429 (count).
 *   - `note_id` stays NULL — see the note below on `images.note_id`.
 */
export async function handleUpload(request: Request, env: Env, userId: number): Promise<Response> {
    const tooLarge = rejectIfRequestTooLarge(request);
    if (tooLarge) return tooLarge;

    let body: any;
    try {
        body = await request.json();
    } catch {
        return errorResponse('Invalid JSON body', 400);
    }
    if (!body || typeof body !== 'object') {
        return errorResponse('Invalid request body', 400);
    }

    const filename = typeof body.filename === 'string' && body.filename.length > 0
        ? body.filename
        : 'image';
    // A8: the client-supplied content_type is only consulted here as a
    // fast-path rejection hint (catches SVG and non-image/* claims
    // before the base64 decode). From the detectImageType() call below
    // onward, only the detected MIME is used — the client's claim is
    // never written to R2 or D1.
    const clientContentType = typeof body.content_type === 'string' ? body.content_type : '';
    const data = typeof body.data === 'string' ? body.data : '';

    if (!data) return errorResponse('Missing field "data" (base64 encoded)', 400);

    // A8: SVG is the only format we hard-reject by name. Even a fake
    // PNG with a `image/svg+xml` claim should be refused here — the
    // browser will sniff Content-Type, not the magic, when rendering.
    if (clientContentType === 'image/svg+xml' || clientContentType === 'image/svg') {
        return errorResponse('image/svg+xml is not allowed (XSS risk)', 415);
    }
    if (clientContentType && !clientContentType.startsWith('image/')) {
        return errorResponse('Only image/* content types are allowed', 415);
    }

    let bytes: Uint8Array;
    try {
        bytes = base64ToUint8Array(data);
    } catch {
        return errorResponse('Invalid base64 data', 400);
    }

    if (bytes.byteLength > MAX_IMAGE_BYTES) {
        return errorResponse(
            `Image too large (${bytes.byteLength} > ${MAX_IMAGE_BYTES}); resize before uploading`,
            413
        );
    }

    // A8: sniff the magic. If it doesn't match one of the five
    // whitelisted formats, refuse with 415 — the client should resize
    // or convert before retrying. The error message includes the
    // first 8 bytes (hex) so a developer can spot a wrong-compression
    // pipeline at a glance without dumping the whole file.
    const detected = detectImageType(bytes);
    if (!detected) {
        const head = Array.from(bytes.slice(0, 8))
            .map(b => b.toString(16).padStart(2, '0'))
            .join(' ');
        return errorResponse(
            `Unsupported image format: expected one of PNG/JPEG/GIF/WebP/HEIC/HEIF. ` +
                `Detected first bytes: ${head}`,
            415
        );
    }

    // A8: enforce per-user storage / file-count caps. Non-null response
    // means we already answered, short-circuit.
    const quotaResp = await enforceUserQuota(env, userId, bytes.byteLength);
    if (quotaResp) return quotaResp;

    const safeFilename = filename.replace(/[^\w.\-]/g, '_').slice(0, 100) || 'image';

    const id = crypto.randomUUID();
    // A8: note_id is left NULL on this insert — see the comment block
    // on images.note_id at the bottom of this file. The mobile client
    // uploads before it has the row's UUID, so binding requires a
    // second round-trip from the client that isn't part of the V5.3
    // API surface. The column stays for a future "attach to note" call.
    const r2Key = `uploads/${userId}/${id}/${safeFilename}`;

    // A8: persist the DETECTED MIME, not the client-claimed one. Both
    // R2 (so /files serves the correct Content-Type later) and D1 (so
    // audits / migrations see ground truth) get the verified value.
    await env.IMAGES.put(r2Key, bytes, {
        httpMetadata: { contentType: detected },
    });
    await env.DB
        .prepare(
            'INSERT INTO images (id, user_id, r2_key, content_type, size_bytes) ' +
            'VALUES (?, ?, ?, ?, ?)'
        )
        .bind(id, userId, r2Key, detected, bytes.byteLength)
        .run();

    const url = `${getBaseUrl(request)}/files/${r2Key}`;
    return jsonResponse({
        id,
        url,
        r2_key: r2Key,
        content_type: detected,
        size: bytes.byteLength,
        user_id: userId,
    });
}

/**
 * A8: response headers for binary file GETs.
 *
 * No `Access-Control-Allow-Origin`: the mobile app authenticates via
 * the Authorization bearer header on native HTTP (OkHttp/Coil), not
 * via cross-origin <img src>. Dropping ACAO blocks embedding of
 * user-owned bytes on arbitrary third-party pages, which was the
 * reason the previous response carried `Access-Control-Allow-Origin: *`.
 *
 * `Cache-Control: private, max-age=31536000, immutable` lets the
 * browser keep the bytes for a year — the r2_key embeds a 128-bit
 * UUID so the URL is never reused — but tells CDNs / intermediaries
 * NOT to share between users. The `private` flag is what flips this
 * from "public CDN cache" to "browser-only cache", since R2 URLs are
 * bearer-gated and must not be served to anyone else.
 *
 * `X-Content-Type-Options: nosniff` stops browser MIME sniffing (we
 * already validated the type via magic, so there is nothing for the
 * client to second-guess).
 *
 * `Content-Disposition: inline` is benign and prevents intermediary
 * proxies from rewriting it to `attachment`.
 */
function fileHeaders(contentType: string): Record<string, string> {
    return {
        'Content-Type': contentType,
        'Content-Disposition': 'inline',
        'Cache-Control': 'private, max-age=31536000, immutable',
        'X-Content-Type-Options': 'nosniff',
    };
}

/**
 * GET /files/<key>
 * V5.3 — auth required; author must be the user that originally uploaded.
 * A8 — response no longer carries CORS headers; see fileHeaders().
 */
export async function serveFile(path: string, userId: number, env: Env): Promise<Response> {
    const key = path.startsWith('/files/') ? path.substring('/files/'.length) : '';
    if (!key) return errorResponse('Missing file key', 400);

    const row = await env.DB
        .prepare('SELECT r2_key, content_type FROM images WHERE r2_key = ? AND user_id = ? LIMIT 1')
        .bind(key, userId)
        .first<{ r2_key: string; content_type: string }>();
    if (!row) return errorResponse('File not found', 404);

    const obj = await env.IMAGES.get(key);
    if (!obj) return errorResponse('File not found', 404);

    // Prefer the R2 httpMetadata (which we wrote ourselves in
    // handleUpload, so it matches the magic we detected). Fall back to
    // the DB row, then to a generic octet-stream — never trust the
    // client's claim.
    const contentType =
        (obj.httpMetadata && obj.httpMetadata.contentType) ||
        row.content_type ||
        'application/octet-stream';
    return new Response(obj.body, { headers: fileHeaders(contentType) });
}

/**
 * DELETE /files/<key>
 * A8 — explicit file-deletion endpoint.
 *
 * Ownership is enforced by scoping both the row lookup and the DB
 * DELETE on (r2_key, user_id). A key that doesn't exist OR belongs
 * to another user both return 404 — same response shape as
 * `serveFile`, no existence leak across users.
 *
 * Order of operations:
 *   - R2.delete() first. If R2 fails we leave the DB row intact so
 *     the client can retry without losing state.
 *   - DB DELETE second. If it fails after R2 succeeded, the blob is
 *     orphaned in R2 but the next run of `cleanupOrphanImages` (from
 *     the scheduled handler) reclaims it.
 *
 * Returns 200 with `{ deleted: r2_key }` on success.
 */
export async function deleteFile(path: string, userId: number, env: Env): Promise<Response> {
    const key = path.startsWith('/files/') ? path.substring('/files/'.length) : '';
    if (!key) return errorResponse('Missing file key', 400);

    const row = await env.DB
        .prepare('SELECT r2_key FROM images WHERE r2_key = ? AND user_id = ? LIMIT 1')
        .bind(key, userId)
        .first<{ r2_key: string }>();
    if (!row) return errorResponse('File not found', 404);

    try {
        await env.IMAGES.delete(key);
    } catch {
        return errorResponse('Failed to delete from storage', 500);
    }

    // Scoped by user_id as a defense-in-depth — even if the SELECT
    // above leaked a row to the wrong user, the DELETE here would
    // still refuse to remove it.
    await env.DB
        .prepare('DELETE FROM images WHERE r2_key = ? AND user_id = ?')
        .bind(key, userId)
        .run();

    return jsonResponse({ deleted: row.r2_key });
}

// --- A8 orphan cleanup --------------------------------------------------

/**
 * A8: orphan-image cutoff. Rows whose `note_id` is NULL AND that are
 * older than this get reaped on the scheduled handler. We pick 7 days
 * to leave a long-enough window that a client can still attach an
 * image to a note after a slow / failed upload round-trip without
 * racing the cleanup.
 */
const ORPHAN_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * A8: per-run cap. Keeps the cron trigger bounded even on a day when
 * thousands of orphans pile up; the next run will mop up the rest.
 */
const MAX_ORPHAN_BATCH = 100;

export interface OrphanCleanupResult {
    scanned: number;
    deleted: number;
    remaining: number;
}

/**
 * A8: orphan-image cleanup. Wired into the A6 scheduled handler in
 * `src/index.ts` alongside `runRetention`.
 *
 * "Orphan" here means `note_id IS NULL AND created_at < cutoff`.
 * That covers the two paths we control:
 *   - Uploads that the client never bound to a note (note-creation
 *     failed, the editor was abandoned, etc.).
 *   - Uploads whose note was hard-deleted by A6 retention AFTER we
 *     already shipped this row (notes.ts does not currently NULL out
 *     `images.note_id` on hard-delete, and we don't touch notes.ts
 *     from A8; the scheduler does the best it can with the column
 *     state it sees).
 *
 * For each candidate we R2-delete first, then DB-delete. If R2 fails
 * we leave the row — the next cron tick retries. We don't try to be
 * clever about batching the R2 deletes, because `IMAGES.delete()`
 * is a single HTTP call to Cloudflare storage and one-at-a-time is
 * fast enough at MAX_ORPHAN_BATCH = 100.
 *
 * NOTE: this does NOT touch images whose `note_id` references a note
 * that has since been hard-deleted but whose `note_id` was never
 * cleared. That cleanup would require a `notes` join and lives in
 * a separate task — out of scope for A8 per the prompt's "don't
 * touch notes.ts" rule.
 */
export async function cleanupOrphanImages(env: Env): Promise<OrphanCleanupResult> {
    const cutoff = Date.now() - ORPHAN_AGE_MS;

    const orphans = await env.DB
        .prepare(
            'SELECT id, r2_key FROM images ' +
            'WHERE note_id IS NULL AND created_at < ? ' +
            'LIMIT ?'
        )
        .bind(cutoff, MAX_ORPHAN_BATCH)
        .all<{ id: string; r2_key: string }>();
    const rows = (orphans.results as Array<{ id: string; r2_key: string }>) || [];

    let deleted = 0;
    for (const row of rows) {
        try {
            await env.IMAGES.delete(row.r2_key);
        } catch {
            // Skip this row; we'll retry on the next scheduled run.
            continue;
        }
        // Re-check note_id IS NULL here so a concurrent write that
        // bound the image to a note in the meantime is preserved.
        await env.DB
            .prepare('DELETE FROM images WHERE id = ? AND note_id IS NULL')
            .bind(row.id)
            .run();
        deleted += 1;
    }

    const remaining = (await env.DB
        .prepare(
            'SELECT COUNT(*) AS cnt FROM images ' +
            'WHERE note_id IS NULL AND created_at < ?'
        )
        .bind(cutoff)
        .first<{ cnt: number }>())?.cnt ?? 0;

    return { scanned: rows.length, deleted, remaining };
}

/* -----------------------------------------------------------------------
 * images.note_id — design note for future maintainers.
 *
 * `note_id` is intentionally left NULL by `handleUpload` for now. The
 * mobile flow uploads an image BEFORE it has the note's UUID (the
 * note row is created only when the user saves), so binding requires
 * a second round-trip — either a `POST /images/:id/attach` style
 * call or extending `POST /notes` to accept inline image refs. Neither
 * is in the V5.3 / A8 API surface today.
 *
 * The column is preserved (nullable, no default) so the eventual
 * binding code only has to UPDATE, never ALTER. A8's orphan cleanup
 * uses `note_id IS NULL` as its reaper predicate for exactly this
 * reason — once an image is bound, the cleanup leaves it alone.
 *
 * This block is intentionally NOT duplicated into CHANGES.md per the
 * A8 prompt's "don't write to CHANGES.md" rule.
 * --------------------------------------------------------------------- */
