import { Env } from './types';

/**
 * Upstash Redis REST cache layer.
 *
 * We deliberately use the HTTP REST API via fetch() instead of the @upstash/redis
 * SDK. Cloudflare Workers doesn't support Node net sockets, but fetch() works
 * everywhere and keeps the bundle small.
 *
 * Docs: https://upstash.com/docs/redis/features/restapi
 *
 * How invalidation works (A9):
 *   - Each user has a monotonic `notes:user:<id>:ver` counter. We INCR it
 *     after every write (upsert/delete/logout).
 *   - Cache keys are namespaced by version: `notes:user:<id>:v<ver>:...`.
 *     Reads at the start of the request capture the current version, so
 *     a write that lands mid-request can never serve a stale value to
 *     a reader that started before the write — the new request will see
 *     a different version and miss.
 *   - Old keys are left to expire via TTL. No SCAN, no explicit DEL.
 *   - This eliminates the read-modify-write race where a write fires
 *     cacheDel after a read has already loaded the (then-stale) body.
 *
 * Command transport:
 *   - Commands are sent as a JSON array in the POST body, NOT encoded
 *     into the URL path. Note content can easily exceed 2 KB and we
 *     don't want URL length limits silently breaking the cache.
 *
 * Cache misses are silent: if Upstash is misconfigured, the request still
 * succeeds by falling back to D1. Cache must never break correctness.
 */

const NOTE_LIST_TTL_SECONDS = 60;
const NOTE_SINGLE_TTL_SECONDS = 120;
const ME_TTL_SECONDS = 300;

interface UpstashCommand {
    command: string[];
}

function isUpstashConfigured(env: Env): boolean {
    return Boolean(env.UPSTASH_REDIS_REST_URL && env.UPSTASH_REDIS_REST_TOKEN);
}

/**
 * Execute a single Upstash pipeline command. Returns `null` on any error so
 * cache never breaks the request. Sends the command as a JSON POST body.
 */
async function exec(env: Env, cmd: UpstashCommand): Promise<unknown> {
    if (!isUpstashConfigured(env)) return null;
    try {
        const resp = await fetch(env.UPSTASH_REDIS_REST_URL!, {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${env.UPSTASH_REDIS_REST_TOKEN}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(cmd.command),
        });
        if (!resp.ok) return null;
        const data = await resp.json() as { result?: unknown };
        return data.result ?? null;
    } catch {
        return null;
    }
}

/**
 * Read the current cache-version counter for a user. Returns 0 if no
 * counter has ever been written (cold start).
 */
async function getCacheVersion(env: Env, userId: number): Promise<number> {
    const result = await exec(env, { command: ['GET', `notes:user:${userId}:ver`] });
    if (typeof result !== 'string') return 0;
    const n = parseInt(result, 10);
    return Number.isFinite(n) ? n : 0;
}

/**
 * Increment the per-user cache-version counter. After this returns,
 * any in-flight reader that captured the previous version will still
 * see its old value (and let it expire via TTL), but new readers will
 * see the new version and miss the cache.
 */
async function bumpCacheVersion(env: Env, userId: number): Promise<void> {
    await exec(env, { command: ['INCR', `notes:user:${userId}:ver`] });
}

/**
 * GET a JSON value. Returns the parsed object or `null` on miss / error.
 */
export async function cacheGet<T>(env: Env, key: string): Promise<T | null> {
    const result = await exec(env, { command: ['GET', key] });
    if (typeof result !== 'string') return null;
    try {
        return JSON.parse(result) as T;
    } catch {
        return null;
    }
}

/**
 * SET a JSON value with TTL (seconds).
 */
export async function cacheSet(env: Env, key: string, value: unknown, ttlSeconds: number): Promise<void> {
    await exec(env, {
        command: ['SET', key, JSON.stringify(value), 'EX', String(ttlSeconds)],
    });
}

/**
 * DEL one or more keys. Still useful for the /auth/me cache where we
 * know exactly which key to drop on logout (no version indirection
 * needed since the user id is unique).
 */
export async function cacheDel(env: Env, ...keys: string[]): Promise<void> {
    if (keys.length === 0) return;
    await exec(env, { command: ['DEL', ...keys] });
}

// --- Versioned cache key builders ----------------------------------------

/**
 * Build a versioned cache key. The caller passes the version it
 * captured at the start of the request — keeping the version out of
 * the helper makes the read-write ordering explicit at the call site.
 */
function withVersion(userId: number, ver: number, scope: string): string {
    return `notes:user:${userId}:v${ver}:${scope}`;
}

export function noteListKey(userId: number, ver: number): string {
    return withVersion(userId, ver, 'list:full');
}

export function noteSingleKey(userId: number, ver: number, noteId: string): string {
    return withVersion(userId, ver, `note:${noteId}`);
}

// /auth/me has no version indirection — it is invalidated explicitly
// by logout via cacheDel, and TTL bounds the damage if a DEL is
// dropped.
export function meKey(userId: number): string {
    return `notes:user:${userId}:me`;
}

// --- Helpers exported for use in route handlers --------------------------

/**
 * Capture the user's current cache version. Call at the start of a
 * read request, then pass the value back into the key builders.
 */
export async function currentCacheVersion(env: Env, userId: number): Promise<number> {
    return getCacheVersion(env, userId);
}

/**
 * Bump the user's cache version. Call at the end of any write that
 * could change cache contents (upsertNote, deleteNote, logout).
 * Old versioned keys will expire on their own.
 */
export async function invalidateUserCache(env: Env, userId: number): Promise<void> {
    await bumpCacheVersion(env, userId);
}

// --- TTL constants exported for use in route handlers --------------------

export const CACHE_TTL = {
    noteList: NOTE_LIST_TTL_SECONDS,
    noteSingle: NOTE_SINGLE_TTL_SECONDS,
    me: ME_TTL_SECONDS,
} as const;
