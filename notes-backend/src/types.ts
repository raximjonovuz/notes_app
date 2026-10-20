export interface User {
    id: number;
    email: string;
    created_at: number;
}

/**
 * V5.3 schema — adds `client_updated_at`, `change_id`, `deleted_at`.
 * `updated_at` is still returned for backward compat but it now equals
 * `client_updated_at` so the client can use them interchangeably.
 *
 * A10: `user_id` is intentionally OMITTED from the public `Note` shape —
 * the client already knows its own user id and leaking it back serves no
 * purpose, and stripping it from the response prevents accidental
 * cross-user leak if a handler ever returns a Note row directly.
 * Server-side DB queries still bind `user_id` (the column is required
 * by the IDOR guard and the per-user cache key); only the
 * `rowToNote(...)` mapping drops it.
 */
export interface Note {
    id: string;
    title: string;
    content: string;
    created_at: number;
    /** @deprecated use client_updated_at */
    updated_at: number;
    client_updated_at: number;
    change_id: number;
    deleted_at: number | null;
    is_favorite: number;
    is_trashed: number;
    background_color: number;
}

export interface Image {
    id: string;
    user_id: number;
    note_id: string | null;
    r2_key: string;
    content_type: string;
    size_bytes: number;
    created_at: number;
}

// Bindings from wrangler.toml
export interface Env {
    DB: D1Database;
    IMAGES: R2Bucket;
    JWT_SECRET: string;
    ENV: string;
    GOOGLE_CLIENT_ID?: string;
    GOOGLE_CLIENT_SECRET?: string;

    /** Upstash Redis REST endpoint. Set via `wrangler secret put UPSTASH_REDIS_REST_URL`. */
    UPSTASH_REDIS_REST_URL?: string;
    /** Upstash Redis REST token. Set via `wrangler secret put UPSTASH_REDIS_REST_TOKEN`. */
    UPSTASH_REDIS_REST_TOKEN?: string;

    /**
     * A7: password-based auth ni yoqish. Default "false" (wrangler.toml).
     * "true" bo'lgandagina /auth/register va /auth/login endpointlari ishlaydi;
     * aks holda 404 qaytaradi (mavjud emas deb). Google Sign-In bundan mustaqil.
     */
    ALLOW_PASSWORD_AUTH?: string;
}
