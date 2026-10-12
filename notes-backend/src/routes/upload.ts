import { Env } from '../types';
import { base64ToUint8Array, errorResponse, getBaseUrl, jsonResponse } from '../utils';

/**
 * POST /upload
 * Auth: required (router passes userId).
 * Body (JSON): { filename: string, content_type: string, data: string (base64) }
 * Returns: { id, url, r2_key, content_type, size, user_id }
 *
 * V5.3 — bounds the payload size to keep the Worker under its 128 MB
 * memory cap. The previous version read everything into RAM via
 * Base64.encodeToString, which OOM'd on multi-MB phone photos. We now
 * refuse anything bigger than 8 MB pre-encoding and recommend the
 * client resize before calling.
 */
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;

export async function handleUpload(request: Request, env: Env, userId: number): Promise<Response> {
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
    const contentType = typeof body.content_type === 'string' && body.content_type.length > 0
        ? body.content_type
        : 'application/octet-stream';
    const data = typeof body.data === 'string' ? body.data : '';

    if (!data) return errorResponse('Missing field "data" (base64 encoded)', 400);

    if (!contentType.startsWith('image/')) {
        return errorResponse('Only image/* content types are allowed', 400);
    }

    let bytes: Uint8Array;
    try {
        bytes = base64ToUint8Array(data);
    } catch (e) {
        return errorResponse('Invalid base64 data', 400);
    }

    if (bytes.byteLength > MAX_IMAGE_BYTES) {
        return errorResponse(
            `Image too large (${bytes.byteLength} > ${MAX_IMAGE_BYTES}); resize before uploading`,
            413
        );
    }

    const safeFilename = filename.replace(/[^\w.\-]/g, '_').slice(0, 100) || 'image';

    const id = crypto.randomUUID();
    const r2Key = `uploads/${userId}/${id}/${safeFilename}`;

    await env.IMAGES.put(r2Key, bytes, {
        httpMetadata: { contentType },
    });

    // Record the upload so /files can authorize by user_id later.
    await env.DB
        .prepare(
            'INSERT INTO images (id, user_id, r2_key, content_type, size_bytes) VALUES (?, ?, ?, ?, ?)'
        )
        .bind(id, userId, r2Key, contentType, bytes.byteLength)
        .run();

    const url = `${getBaseUrl(request)}/files/${r2Key}`;

    return jsonResponse({
        id,
        url,
        r2_key: r2Key,
        content_type: contentType,
        size: bytes.byteLength,
        user_id: userId,
    });
}

/**
 * GET /files/<key>
 * V5.3 — auth required. Author must be the user that originally uploaded
 * the file. We do a single D1 lookup keyed on (r2_key, user_id); if no
 * match, 404. This prevents cross-user leaks of R2 URLs while keeping
 * the cache-friendly public CDN-friendly response (we still allow
 * immutable long-cache on the response because the key itself contains
 * a 128-bit UUID that's never reused).
 */
export async function serveFile(path: string, userId: number, env: Env): Promise<Response> {
    const key = path.startsWith('/files/') ? path.substring('/files/'.length) : '';
    if (!key) return errorResponse('Missing file key', 400);

    const row = await env.DB
        .prepare('SELECT r2_key FROM images WHERE r2_key = ? AND user_id = ? LIMIT 1')
        .bind(key, userId)
        .first<{ r2_key: string }>();
    if (!row) return errorResponse('File not found', 404);

    const obj = await env.IMAGES.get(key);
    if (!obj) return errorResponse('File not found', 404);

    const headers: Record<string, string> = {
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'public, max-age=31536000, immutable',
    };
    if (obj.httpMetadata && obj.httpMetadata.contentType) {
        headers['Content-Type'] = obj.httpMetadata.contentType;
    } else {
        headers['Content-Type'] = 'application/octet-stream';
    }

    return new Response(obj.body, { headers });
}
