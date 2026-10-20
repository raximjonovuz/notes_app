import { Env, User } from '../types';
import { errorResponse, getClientIp, jsonResponse } from '../utils';
import { generateToken, hashPassword, verifyPassword } from '../auth';
import { CACHE_TTL, cacheDel, cacheGet, cacheSet, meKey } from '../cache';
import { checkRateLimit } from '../rate_limit';

/* ------------------------------------------------------------------ */
/* Rate-limit and password-auth gate helpers                          */
/* ------------------------------------------------------------------ */

const RATE_LIMIT = 10;

/**
 * Apply IP-based rate limit to a public auth route. On `allowed: false`,
 * incrementing has already happened in this minute — the caller may
 * safely return 429 without further bookkeeping.
 */
async function enforceAuthRateLimit(
    request: Request,
    env: Env,
    bucket: string
): Promise<Response | null> {
    const ip = getClientIp(request);
    const { allowed } = await checkRateLimit(env, `auth:${bucket}:${ip}`, RATE_LIMIT);
    if (!allowed) {
        return errorResponse('Too many requests', 429);
    }
    return null;
}

/**
 * Password-based auth is opt-in. When ALLOW_PASSWORD_AUTH !== "true"
 * the route returns 404 so a probe can't tell whether the endpoint
 * exists. Google Sign-In bypasses this gate entirely.
 */
function passwordAuthDisabled(env: Env): boolean {
    return env.ALLOW_PASSWORD_AUTH !== 'true';
}

/* ------------------------------------------------------------------ */
/* Shared helpers                                                     */
/* ------------------------------------------------------------------ */

/**
 * Validate the basic shape of a registration/login body. A7: emails are
 * stored case-insensitively, so we lowercase once at the boundary and
 * never again.
 */
function validateCredentials(body: any): { ok: true; email: string; password: string } | { ok: false; message: string } {
    if (!body || typeof body !== 'object') {
        return { ok: false, message: 'Invalid request body' };
    }
    const rawEmail = typeof body.email === 'string' ? body.email.trim() : '';
    const email = rawEmail.toLowerCase();
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
 * Convert a D1 user row to a public User object (no password hash, no token_version).
 */
function publicUser(row: { id: number; email: string; created_at: number }): User {
    return {
        id: row.id,
        email: row.email,
        created_at: row.created_at,
    };
}

/* ------------------------------------------------------------------ */
/* POST /auth/register                                                */
/* ------------------------------------------------------------------ */

/**
 * POST /auth/register
 * Body: { email, password }
 * Returns: { token, user }
 */
export async function registerRoute(request: Request, env: Env): Promise<Response> {
    // 404 before any side-effect or counter increment — a probe must not
    // learn whether the endpoint is configured.
    if (passwordAuthDisabled(env)) {
        return errorResponse('Not Found', 404);
    }

    const limited = await enforceAuthRateLimit(request, env, 'register');
    if (limited) return limited;

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
            'INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id, email, created_at, token_version'
        )
        .bind(v.email, passwordHash)
        .first<{ id: number; email: string; created_at: number; token_version: number }>();

    if (!result) {
        return errorResponse('Failed to create user', 500);
    }

    const token = await generateToken(result.id, env);
    return jsonResponse({ token, user: publicUser(result) });
}

/* ------------------------------------------------------------------ */
/* POST /auth/login                                                   */
/* ------------------------------------------------------------------ */

/**
 * POST /auth/login
 * Body: { email, password }
 * Returns: { token, user }
 */
export async function loginRoute(request: Request, env: Env): Promise<Response> {
    if (passwordAuthDisabled(env)) {
        return errorResponse('Not Found', 404);
    }

    const limited = await enforceAuthRateLimit(request, env, 'login');
    if (limited) return limited;

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
            'SELECT id, email, password_hash, created_at, token_version FROM users WHERE email = ?'
        )
        .bind(v.email)
        .first<{
            id: number;
            email: string;
            password_hash: string;
            created_at: number;
            token_version: number;
        }>();

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

/* ------------------------------------------------------------------ */
/* POST /auth/google                                                  */
/* ------------------------------------------------------------------ */

/**
 * POST /auth/google
 * Body: { id_token: string }
 * Returns: { token, user }
 *
 * A7: token verification is tightened — `email_verified` (boolean or
 * string), `iss` issuer, audience, and `exp` (now in seconds compared
 * against `Date.now()` in ms).
 *
 * NOTE: We continue to use Google's `tokeninfo` endpoint rather than
 * JWKS / RS256 signature verification. `tokeninfo` runs over HTTPS to
 * a Google-controlled endpoint; the JWT audience binding prevents
 * cross-app replay. Migrating to JWKS would mean pinning Google's key
 * set and verifying the RSA signature locally — left as a future
 * hardening step.
 */
export async function googleAuthRoute(request: Request, env: Env): Promise<Response> {
    if (!env.GOOGLE_CLIENT_ID) {
        return errorResponse('Google sign-in is not configured on this server', 500);
    }

    const limited = await enforceAuthRateLimit(request, env, 'google');
    if (limited) return limited;

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

    let info: any;
    try {
        const url = `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`;
        const resp = await fetch(url);
        info = (await resp.json()) as any;
    } catch {
        return errorResponse('Failed to verify Google token', 502);
    }

    if (info?.error) {
        return errorResponse(`Google rejected the token: ${info.error}`, 401);
    }
    if (info?.aud !== env.GOOGLE_CLIENT_ID) {
        return errorResponse('Google token audience mismatch', 401);
    }
    if (info?.iss !== 'accounts.google.com' && info?.iss !== 'https://accounts.google.com') {
        return errorResponse('Google token issuer mismatch', 401);
    }
    // Google's `email_verified` may serialize as boolean or string.
    if (info?.email_verified !== true && info?.email_verified !== 'true') {
        return errorResponse('Google account email is not verified', 401);
    }
    // `exp` arrives in seconds; compare against wall-clock ms.
    if (typeof info?.exp === 'number' && info.exp * 1000 < Date.now()) {
        return errorResponse('Google token expired', 401);
    }
    if (!info?.email || typeof info.email !== 'string') {
        return errorResponse('Google token missing email', 401);
    }

    const email = info.email.trim().toLowerCase();
    // A4: store created_at in milliseconds to match the column's
    // millisecond default (`strftime('%s','now')*1000`).
    const nowMs = Date.now();

    // Upsert the user. Existing users (by email) are reused so the same
    // Google account always lands on the same Notes user_id.
    const existing = await env.DB
        .prepare('SELECT id, email, created_at FROM users WHERE email = ?')
        .bind(email)
        .first<{ id: number; email: string; created_at: number }>();
    let user: User;
    if (existing) {
        user = existing;
    } else {
        const inserted = await env.DB
            .prepare(
                'INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?) RETURNING id, email, created_at, token_version'
            )
            // password_hash is NOT NULL but unused for OAuth accounts.
            .bind(email, '!google-oauth', nowMs)
            .first<{ id: number; email: string; created_at: number; token_version: number }>();
        if (!inserted) {
            return errorResponse('Failed to create user from Google account', 500);
        }
        user = { id: inserted.id, email: inserted.email, created_at: inserted.created_at };
    }

    const token = await generateToken(user.id, env);
    return jsonResponse({ token, user });
}

/* ------------------------------------------------------------------ */
/* POST /auth/logout                                                  */
/* ------------------------------------------------------------------ */

/**
 * POST /auth/logout (authenticated). Bumps the user's token_version
 * so previously-issued JWTs cease to authenticate. Idempotent: calling
 * it twice is harmless (the second bump still revokes the token issued
 * after the first bump).
 */
export async function logoutRoute(userId: number, env: Env): Promise<Response> {
    await env.DB
        .prepare('UPDATE users SET token_version = token_version + 1 WHERE id = ?')
        .bind(userId)
        .run();
    // The cached /auth/me body is keyed by userId and would otherwise
    // survive the bump until TTL.
    await cacheDel(env, meKey(userId));
    return jsonResponse({ ok: true });
}

/* ------------------------------------------------------------------ */
/* GET /auth/me                                                       */
/* ------------------------------------------------------------------ */

/**
 * GET /auth/me (authenticated). The router has already verified the
 * bearer token via `getUserId`, so we trust `userId` here without
 * re-parsing the Authorization header.
 */
export async function meRoute(userId: number, env: Env): Promise<Response> {
    // User profile (id/email/created_at) is immutable after registration,
    // so we can cache it with a generous TTL and skip invalidation entirely
    // (logout invalidates the me cache proactively).
    const cached = await cacheGet<{ body: unknown }>(env, meKey(userId));
    if (cached?.body) {
        return jsonResponse(cached.body as any);
    }

    const row = await env.DB
        .prepare('SELECT id, email, created_at FROM users WHERE id = ?')
        .bind(userId)
        .first<User>();
    if (!row) return errorResponse('User not found', 404);

    const body = { user: publicUser(row) };
    await cacheSet(env, meKey(userId), { body }, CACHE_TTL.me);
    return jsonResponse(body);
}
