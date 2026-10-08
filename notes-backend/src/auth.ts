import { Env } from './types';

/* ------------------------------------------------------------------ */
/* Password hashing — PBKDF2 / SHA-256 / 100k iterations              */
/* ------------------------------------------------------------------ */

const PBKDF2_ITERATIONS = 100_000;
const PBKDF2_HASH = 'SHA-256';
const SALT_BYTES = 16;
const KEY_BYTES = 32;

/**
 * Encode a Uint8Array as lowercase hex. Used for storing the salt and hash.
 */
function bytesToHex(bytes: Uint8Array): string {
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
        out += bytes[i].toString(16).padStart(2, '0');
    }
    return out;
}

/**
 * Decode a hex string to a Uint8Array.
 */
function hexToBytes(hex: string): Uint8Array {
    const normalized = hex.length % 2 === 0 ? hex : '0' + hex;
    const bytes = new Uint8Array(normalized.length / 2);
    for (let i = 0; i < bytes.length; i++) {
        bytes[i] = parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
}

/**
 * Derive a key from a password + salt using PBKDF2.
 */
async function pbkdf2(password: string, salt: Uint8Array): Promise<Uint8Array> {
    const enc = new TextEncoder();
    const keyMaterial = await crypto.subtle.importKey(
        'raw',
        enc.encode(password),
        { name: 'PBKDF2' },
        false,
        ['deriveBits']
    );
    const bits = await crypto.subtle.deriveBits(
        {
            name: 'PBKDF2',
            salt: salt as BufferSource,
            iterations: PBKDF2_ITERATIONS,
            hash: PBKDF2_HASH,
        },
        keyMaterial,
        KEY_BYTES * 8
    );
    return new Uint8Array(bits);
}

/**
 * Hash a plaintext password. Returns "salt:hash" where both halves are hex.
 */
export async function hashPassword(password: string): Promise<string> {
    const salt = new Uint8Array(SALT_BYTES);
    crypto.getRandomValues(salt);
    const hash = await pbkdf2(password, salt);
    return `${bytesToHex(salt)}:${bytesToHex(hash)}`;
}

/**
 * Verify a plaintext password against a stored "salt:hash" value.
 */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
    const parts = stored.split(':');
    if (parts.length !== 2) return false;
    const salt = hexToBytes(parts[0]);
    const expected = hexToBytes(parts[1]);
    const actual = await pbkdf2(password, salt);
    if (actual.length !== expected.length) return false;
    // Constant-time comparison.
    let diff = 0;
    for (let i = 0; i < actual.length; i++) {
        diff |= actual[i] ^ expected[i];
    }
    return diff === 0;
}

/* ------------------------------------------------------------------ */
/* JWT (HS256) — header.payload.signature, all base64url encoded      */
/* ------------------------------------------------------------------ */

const JWT_TTL_SECONDS = 60 * 60 * 24 * 30; // 30 days

/**
 * base64url encode (no padding).
 */
function base64UrlEncode(bytes: Uint8Array | string): string {
    let str: string;
    if (typeof bytes === 'string') {
        str = btoa(bytes);
    } else {
        let bin = '';
        for (let i = 0; i < bytes.length; i++) {
            bin += String.fromCharCode(bytes[i]);
        }
        str = btoa(bin);
    }
    return str.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

/**
 * base64url decode (accepts padded or unpadded input).
 */
function base64UrlDecode(input: string): Uint8Array {
    let s = input.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4 !== 0) s += '=';
    const bin = atob(s);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
        bytes[i] = bin.charCodeAt(i);
    }
    return bytes;
}

/**
 * Build a JWT for the given userId, signed with env.JWT_SECRET (HS256).
 */
export async function generateToken(userId: number, env: Env): Promise<string> {
    const header = { alg: 'HS256', typ: 'JWT' };
    const now = Math.floor(Date.now() / 1000);
    const payload = {
        sub: String(userId),
        iat: now,
        exp: now + JWT_TTL_SECONDS,
    };
    const headerB64 = base64UrlEncode(JSON.stringify(header));
    const payloadB64 = base64UrlEncode(JSON.stringify(payload));
    const signingInput = `${headerB64}.${payloadB64}`;

    const key = await crypto.subtle.importKey(
        'raw',
        new TextEncoder().encode(env.JWT_SECRET),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign', 'verify']
    );
    const signature = await crypto.subtle.sign(
        'HMAC',
        key,
        new TextEncoder().encode(signingInput)
    );
    const sigB64 = base64UrlEncode(new Uint8Array(signature));
    return `${signingInput}.${sigB64}`;
}

/**
 * Verify a JWT. Returns the userId (sub) if valid, otherwise null.
 */
export async function verifyToken(token: string, env: Env): Promise<number | null> {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const [headerB64, payloadB64, sigB64] = parts;
    const signingInput = `${headerB64}.${payloadB64}`;

    const key = await crypto.subtle.importKey(
        'raw',
        new TextEncoder().encode(env.JWT_SECRET),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign', 'verify']
    );
    let valid = false;
    try {
        valid = await crypto.subtle.verify(
            'HMAC',
            key,
            base64UrlDecode(sigB64) as BufferSource,
            new TextEncoder().encode(signingInput)
        );
    } catch {
        return null;
    }
    if (!valid) return null;

    let payload: { sub?: string; exp?: number };
    try {
        payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(payloadB64)));
    } catch {
        return null;
    }
    if (!payload.sub) return null;
    if (typeof payload.exp === 'number' && payload.exp < Math.floor(Date.now() / 1000)) {
        return null;
    }
    const userId = Number(payload.sub);
    if (!Number.isFinite(userId)) return null;
    return userId;
}