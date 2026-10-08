import { Env, User } from '../types';
import { errorResponse, jsonResponse } from '../utils';
import { generateToken, hashPassword, verifyToken, verifyPassword } from '../auth';

/**
 * Validate the basic shape of a registration/login body.
 */
function validateCredentials(body: any): { ok: true; email: string; password: string } | { ok: false; message: string } {
    if (!body || typeof body !== 'object') {
        return { ok: false, message: 'Invalid request body' };
    }
    const email = typeof body.email === 'string' ? body.email.trim() : '';
    const password = typeof body.password === 'string' ? body.password : '';
    if (!email || !email.includes('@')) {
        return { ok: false, message: 'Invalid email address' };
    }
    if (password.length < 8) {
        return { ok: false, message: 'Password must be at least 8 characters' };
    }
    return { ok: true, email, password };
}

/**
 * Convert a D1 user row to a public User object (no password hash).
 */
function publicUser(row: any): User {
    return {
        id: row.id,
        email: row.email,
        created_at: row.created_at,
    };
}

/**
 * POST /auth/register
 * Body: { email, password }
 * Returns: { token, user }
 */
export async function registerRoute(request: Request, env: Env): Promise<Response> {
    let body: any;
    try {
        body = await request.json();
    } catch {
        return errorResponse('Invalid JSON body', 400);
    }

    const v = validateCredentials(body);
    if (!v.ok) return errorResponse(v.message, 400);

    // Check if the email is already taken.
    const existing = await env.DB
        .prepare('SELECT id FROM users WHERE email = ?')
        .bind(v.email)
        .first<{ id: number }>();
    if (existing) {
        return errorResponse('Email already registered', 409);
    }

    const passwordHash = await hashPassword(v.password);

    const result = await env.DB
        .prepare(
            'INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id, email, created_at'
        )
        .bind(v.email, passwordHash)
        .first<User>();

    if (!result) {
        return errorResponse('Failed to create user', 500);
    }

    const token = await generateToken(result.id, env);
    return jsonResponse({ token, user: publicUser(result) });
}

/**
 * POST /auth/login
 * Body: { email, password }
 * Returns: { token, user }
 */
export async function loginRoute(request: Request, env: Env): Promise<Response> {
    let body: any;
    try {
        body = await request.json();
    } catch {
        return errorResponse('Invalid JSON body', 400);
    }

    const v = validateCredentials(body);
    if (!v.ok) return errorResponse(v.message, 400);

    const row = await env.DB
        .prepare(
            'SELECT id, email, password_hash, created_at FROM users WHERE email = ?'
        )
        .bind(v.email)
        .first<{ id: number; email: string; password_hash: string; created_at: number }>();

    if (!row) {
        return errorResponse('Invalid email or password', 401);
    }

    const ok = await verifyPassword(v.password, row.password_hash);
    if (!ok) {
        return errorResponse('Invalid email or password', 401);
    }

    const user: User = {
        id: row.id,
        email: row.email,
        created_at: row.created_at,
    };
    const token = await generateToken(user.id, env);
    return jsonResponse({ token, user });
}

/**
 * POST /auth/google
 * Body: { id_token: string }
 * Returns: { token, user }
 *
 * V4.2 — Google OAuth sign-in. We accept an ID token issued by the Android
 * Google Sign-In SDK (or any compliant client). The token is verified
 * server-side by hitting Google's tokeninfo endpoint, which returns the
 * payload if the token is valid and was issued for our configured client.
 *
 * For higher-security / production, replace tokeninfo with JWKS-based
 * signature verification (https://www.googleapis.com/oauth2/v3/certs).
 * The current approach trusts Google's tokeninfo response, which is
 * acceptable since the request is server-to-server over HTTPS and the
 * endpoint is Google's.
 */
export async function googleAuthRoute(request: Request, env: Env): Promise<Response> {
    if (!env.GOOGLE_CLIENT_ID) {
        return errorResponse('Google sign-in is not configured on this server', 500);
    }

    let body: any;
    try {
        body = await request.json();
    } catch {
        return errorResponse('Invalid JSON body', 400);
    }

    const idToken = typeof body?.id_token === 'string' ? body.id_token : '';
    if (!idToken) {
        return errorResponse('Missing id_token', 400);
    }

    // Verify the token against Google's tokeninfo endpoint. The endpoint
    // returns the JWT claims if the token is valid; otherwise an `error`
    // field. We additionally check that `aud` matches our Web Client ID so
    // a token issued for a different app cannot be replayed against us.
    let info: any;
    try {
        const url = `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`;
        const resp = await fetch(url);
        info = await resp.json() as any;
    } catch (e) {
        return errorResponse('Failed to verify Google token', 502);
    }

    if (info?.error) {
        return errorResponse(`Google rejected the token: ${info.error}`, 401);
    }
    if (info?.aud !== env.GOOGLE_CLIENT_ID) {
        return errorResponse('Google token audience mismatch', 401);
    }
    if (!info?.email || !info?.email_verified) {
        return errorResponse('Google account email is not verified', 401);
    }
    if (info?.exp && Number(info.exp) * 1000 < Date.now()) {
        return errorResponse('Google token expired', 401);
    }

    const email = String(info.email).trim().toLowerCase();
    const nowMs = Date.now();
    const createdAtSec = Math.floor(nowMs / 1000);

    // Upsert the user. Existing users (by email) are reused so the same
    // Google account always lands on the same Notes user_id, which keeps
    // multi-device sync working.
    const existing = await env.DB
        .prepare('SELECT id, email, created_at FROM users WHERE email = ?')
        .bind(email)
        .first<User>();
    let user: User;
    if (existing) {
        user = existing;
    } else {
        const inserted = await env.DB
            .prepare(
                'INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?) RETURNING id, email, created_at'
            )
            // password_hash is NOT NULL but unused for OAuth accounts.
            // Store an empty placeholder so the row can be created.
            .bind(email, '!google-oauth', createdAtSec)
            .first<User>();
        if (!inserted) {
            return errorResponse('Failed to create user from Google account', 500);
        }
        user = inserted;
    }

    const token = await generateToken(user.id, env);
    return jsonResponse({ token, user });
}

/**
 * GET /auth/me
 * Requires Authorization: Bearer <token>.
 * Returns: { user }
 */
export async function meRoute(request: Request, env: Env): Promise<Response> {
    // Re-read the bearer token directly so we don't depend on router-side parsing
    // having happened first.
    const auth = request.headers.get('Authorization');
    if (!auth) return errorResponse('Unauthorized', 401);
    const match = auth.match(/^Bearer\s+(.+)$/i);
    if (!match) return errorResponse('Unauthorized', 401);
    const userId = await verifyToken(match[1].trim(), env);
    if (!userId) return errorResponse('Unauthorized', 401);

    const row = await env.DB
        .prepare('SELECT id, email, created_at FROM users WHERE id = ?')
        .bind(userId)
        .first<User>();
    if (!row) return errorResponse('User not found', 404);
    return jsonResponse({ user: publicUser(row) });
}