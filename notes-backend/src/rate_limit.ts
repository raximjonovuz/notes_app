import { Env } from './types';

/**
 * Per-key, per-minute fixed-window counter stored in D1.
 *
 * Why D1 and not KV: KV is an extra binding to provision; the counter
 * footprint is tiny (one row per minute per bucket), D1 already covers
 * the same write-heavy work elsewhere, and a scheduled task can GC the
 * `rate_limits` table by `window_start`. This keeps the Wrangler config
 * dependency-free.
 *
 * Window = epoch-minute (UTC). At the boundary the counter resets
 * automatically — there is no transactional rollover to coordinate.
 */
const DEFAULT_LIMIT = 10;

/**
 * Increment and check the per-key counter for this minute window.
 *
 * Returns `{ allowed, count, limit }`. The increment is *unconditional*
 * so a flood of attempts in the same window keeps growing the count
 * (the {limit+1}th, {limit+2}th, … attempts all see `allowed: false`).
 */
export async function checkRateLimit(
    env: Env,
    key: string,
    limit: number = DEFAULT_LIMIT
): Promise<{ allowed: boolean; count: number; limit: number }> {
    const windowStart = Math.floor(Date.now() / 60_000);

    await env.DB.batch([
        env.DB
            .prepare(
                'INSERT OR IGNORE INTO rate_limits(key, count, window_start) VALUES (?, 0, ?)'
            )
            .bind(key, windowStart),
        env.DB
            .prepare(
                'UPDATE rate_limits SET count = count + 1 WHERE key = ? AND window_start = ?'
            )
            .bind(key, windowStart),
    ]);

    const row = await env.DB
        .prepare(
            'SELECT count FROM rate_limits WHERE key = ? AND window_start = ?'
        )
        .bind(key, windowStart)
        .first<{ count: number }>();

    const count = row?.count ?? 0;
    return { allowed: count <= limit, count, limit };
}
