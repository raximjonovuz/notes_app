import { Env } from '../types';
import { base64ToUint8Array, errorResponse, getBaseUrl, jsonResponse } from '../utils';

/**
 * POST /upload
 * Auth: required (router passes userId).
 * Body (JSON): { filename: string, content_type: string, data: string (base64) }
 * Returns: { id, url, r2_key, content_type, size }
 */
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

    // Validate content type — only image/* for now.
    if (!contentType.startsWith('image/')) {
        return errorResponse('Only image/* content types are allowed', 400);
    }

    let bytes: Uint8Array;
    try {
        bytes = base64ToUint8Array(data);
    } catch (e) {
        return errorResponse('Invalid base64 data', 400);
    }

    // Sanitize filename: strip path separators and keep it short.
    const safeFilename = filename.replace(/[^\w.\-]/g, '_').slice(0, 100) || 'image';

    const id = crypto.randomUUID();
    const r2Key = `uploads/${userId}/${id}/${safeFilename}`;

    await env.IMAGES.put(r2Key, bytes, {
        httpMetadata: { contentType },
    });

    const url = `${getBaseUrl(request)}/files/${r2Key}`;

    return jsonResponse({
        id,
        url,
        r2_key: r2Key,
        content_type: contentType,
        size: bytes.length,
    });
}

/**
 * GET /files/<key>
 * Public — serves a previously-uploaded image straight out of the R2 bucket.
 */
export async function serveFile(path: string, env: Env): Promise<Response> {
    // path begins with "/files/"; strip it.
    const key = path.startsWith('/files/') ? path.substring('/files/'.length) : '';
    if (!key) return errorResponse('Missing file key', 400);

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