import { Env } from './types';
import { verifyToken } from './auth';

/**
 * JSON response with CORS headers.
 */
export function jsonResponse(data: any, status = 200): Response {
    return new Response(JSON.stringify(data), {
        status,
        headers: {
            'Content-Type': 'application/json',
            ...corsHeaders(),
        },
    });
}

/**
 * Error response with CORS headers.
 */
export function errorResponse(message: string, status = 400): Response {
    return new Response(JSON.stringify({ error: message }), {
        status,
        headers: {
            'Content-Type': 'application/json',
            ...corsHeaders(),
        },
    });
}

/**
 * Extract user id from Authorization: Bearer <token> header.
 * Returns null if missing or invalid (including server misconfiguration —
 * in that case verifyToken throws and we let the caller surface a 5xx).
 */
export async function getUserId(request: Request, env: Env): Promise<number | null> {
    const auth = request.headers.get('Authorization');
    if (!auth) return null;
    const match = auth.match(/^Bearer\s+(.+)$/i);
    if (!match) return null;
    const token = match[1].trim();
    return await verifyToken(token, env);
}

/**
 * Best-effort client IP for rate-limiting and audit logs. Cloudflare
 * Workers exposes `CF-Connecting-IP` as the canonical (already-trusted)
 * client address; we fall back to the leftmost X-Forwarded-For entry,
 * then to the literal string `"unknown"`. Never throw.
 */
export function getClientIp(request: Request): string {
    const cf = request.headers.get('CF-Connecting-IP');
    if (cf) return cf.trim();
    const xff = request.headers.get('X-Forwarded-For');
    if (xff) {
        const first = xff.split(',')[0]?.trim();
        if (first) return first;
    }
    return 'unknown';
}

/**
 * CORS headers. Allowing * is acceptable for a public backend
 * that the mobile app calls directly.
 */
export function corsHeaders(): Record<string, string> {
    return {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        'Access-Control-Max-Age': '86400',
    };
}

/**
 * Handle a CORS preflight (OPTIONS) request.
 */
export function handleCors(): Response {
    return new Response(null, {
        status: 204,
        headers: corsHeaders(),
    });
}

/**
 * Base64 (standard or URL-safe) -> Uint8Array. Used by upload routes.
 */
export function base64ToUint8Array(b64: string): Uint8Array {
    // Normalize URL-safe base64 to standard base64
    let normalized = b64.replace(/-/g, '+').replace(/_/g, '/');
    // Pad to a multiple of 4
    while (normalized.length % 4 !== 0) {
        normalized += '=';
    }
    const binary = atob(normalized);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

/**
 * Build a base URL from the incoming request. Used to construct
 * public file URLs returned by /upload.
 */
export function getBaseUrl(request: Request): string {
    const url = new URL(request.url);
    return `${url.protocol}//${url.host}`;
}