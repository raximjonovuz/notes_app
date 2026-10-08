export interface User {
    id: number;
    email: string;
    created_at: number;
}

export interface Note {
    id: string;                     // UUID
    user_id: number;
    title: string;
    content: string;
    created_at: number;
    updated_at: number;
    is_favorite: number;            // 0 or 1
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
    // V4.2 — Google OAuth (set via `wrangler secret put`)
    GOOGLE_CLIENT_ID?: string;
    GOOGLE_CLIENT_SECRET?: string;
}