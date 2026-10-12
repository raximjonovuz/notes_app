export interface User {
    id: number;
    email: string;
    created_at: number;
}

/**
 * V5.3 schema — adds `client_updated_at`, `change_id`, `deleted_at`.
 * `updated_at` is still returned for backward compat but it now equals
 * `client_updated_at` so the client can use them interchangeably.
 */
export interface Note {
    id: string;
    user_id: number;
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
}
