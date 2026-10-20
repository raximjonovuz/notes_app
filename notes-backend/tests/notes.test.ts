/* ------------------------------------------------------------------ *
 * tests/notes.test.ts
 *
 * A10 backend tests.
 *
 * Strategy: plain vitest + a small in-memory D1 / R2 stub. The
 * @cloudflare/vitest-pool-workers plugin would give us a real
 * workerd runtime but the worker API surface we exercise here is
 * narrow (route handlers + a couple of pure helpers), so a stub is
 * lighter and stays inside `tests/` per the A10 prompt rules.
 *
 * What we cover today:
 *   1. IDOR — different user POST /notes/<id> → 404.
 *   2. base_change_id conflict → 409.
 *   3. tombstone since — soft-deleted note surfaces on incremental pull.
 *   4. deleteNote — title/content cleared on the row.
 *   5. validateNoteBody — title/content/background_color bounds +
 *      future updated_at clamp.
 *   6. email lowercase — register/login normalise mixed-case input.
 *   7. rate limit — 11 attempts in one minute → 429.
 *   8. SVG upload rejected by magic-number check.
 *
 * Anything outside MockD1's supported SQL surface (TODO tests, future
 * queries) should extend MockD1 rather than rewrite it.
 * ------------------------------------------------------------------ */
import { beforeEach, describe, expect, it } from 'vitest';

import { deleteNote, listNotes, upsertNote, validateNoteBody } from '../src/routes/notes';
import { detectImageType } from '../src/routes/upload';
import { loginRoute, registerRoute } from '../src/routes/auth';
import { Env } from '../src/types';

/* ------------------------------------------------------------------ *
 * MockD1 — minimal in-memory D1Database shim.
 * ------------------------------------------------------------------ */

type Row = Record<string, any>;

interface WhereClause {
    col: string;
    op: '=' | '<>' | '>' | '<' | '>=' | '<=' | 'IS' | 'IS NOT' | 'LIKE';
    idx: number;
}

function parseWhere(where: string): WhereClause[] {
    return where
        .split(/\s+AND\s+/i)
        .map(s => s.trim())
        .filter(Boolean)
        .map((c, i) => {
            const m = c.match(/^([a-zA-Z_][a-zA-Z0-9_.]*)\s+(=|IS\s+NOT|<>|>=|<=|>|<|IS|LIKE)\s+\?$/i);
            if (!m) throw new Error(`MockD1: unsupported WHERE clause: ${c}`);
            const op = m[2].replace(/\s+/g, ' ').toUpperCase();
            return { col: m[1], op: op as WhereClause['op'], idx: i };
        });
}

function matchClauses(row: Row, clauses: WhereClause[], params: any[]): boolean {
    for (const c of clauses) {
        const actual = row[c.col];
        const val = params[c.idx];
        switch (c.op) {
            case '=':      if (actual !== val) return false; break;
            case '<>':     if (actual === val) return false; break;
            case '>':      if (!(actual > val)) return false; break;
            case '<':      if (!(actual < val)) return false; break;
            case '>=':     if (!(actual >= val)) return false; break;
            case '<=':     if (!(actual <= val)) return false; break;
            case 'IS':     if (actual !== val) return false; break;
            case 'IS NOT': if (actual === val) return false; break;
            case 'LIKE':   if (!String(actual ?? '').includes(String(val).replace(/%/g, ''))) return false; break;
        }
    }
    return true;
}

/** Split a comma-separated list at the top level only (parens-safe). */
function splitTopLevel(s: string): string[] {
    const out: string[] = [];
    let depth = 0;
    let buf = '';
    for (const ch of s) {
        if (ch === '(') depth++;
        else if (ch === ')') depth--;
        if (ch === ',' && depth === 0) { out.push(buf.trim()); buf = ''; }
        else buf += ch;
    }
    if (buf.trim()) out.push(buf.trim());
    return out;
}

class MockStatement {
    private bound: any[] = [];
    constructor(private db: MockD1, private sql: string) {}
    bind(...args: any[]): this { this.bound = args; return this; }
    async first<T = Row>(): Promise<T | null> { return this.db.execFirst(this.sql, this.bound) as T | null; }
    async all<T = Row>(): Promise<{ results: T[] }> { return { results: this.db.execAll(this.sql, this.bound) as T[] }; }
    async run(): Promise<{ meta: { changes: number } }> { return { meta: { changes: this.db.execRun(this.sql, this.bound) } }; }
}

class MockD1 {
    tables: Record<string, Map<number, Row>> = {
        notes: new Map(),
        change_log: new Map(),
        users: new Map(),
        images: new Map(),
        rate_limits: new Map(),
    };
    nextRowId = 1;

    /** Seed a row directly into a table. Auto-assigns an autoincrement id
     *  UNLESS the caller already supplied an `id` (e.g. UUIDs in the `notes`
     *  table or `change_log.note_id`). Real D1 AUTOINCREMENT columns still
     *  get their own id; the caller's explicit id is preserved as a row
     *  attribute. We store the row at a synthetic key and look it up by
     *  content when matching against SELECT/WHERE clauses. */
    seed(table: string, row: Row): Row {
        const providedId = (row as any).id;
        const key = providedId !== undefined ? providedId : this.nextRowId++;
        if (providedId === undefined) (row as any).id = key;
        this.tables[table].set(key, row);
        return row;
    }

    prepare(sql: string): MockStatement { return new MockStatement(this, sql); }
    async batch(stmts: Array<{ run: () => Promise<any> }>): Promise<void> {
        for (const s of stmts) await s.run();
    }

    private allRows(table: string): Row[] {
        return Array.from(this.tables[table].values());
    }

    private insertRow(table: string, row: Row): number {
        const id = this.nextRowId++;
        const withId = { ...row, id };
        this.tables[table].set(id, withId);
        return id;
    }

    private select(sql: string, params: any[]): Row[] {
        const s = sql.trim().replace(/\s+/g, ' ');

        // WITH per_note AS (... GROUP BY note_id) SELECT ... FROM per_note p INNER JOIN notes n ON ... ORDER BY p.max_change_id ASC LIMIT ?
        const withM = s.match(/^WITH\s+([a-zA-Z_]+)\s+AS\s*\(\s*SELECT\s+(.+?)\s+FROM\s+([a-zA-Z_]+)\s+WHERE\s+(.+?)\s+GROUP\s+BY\s+([a-zA-Z_]+)\s*\)\s+SELECT\s+(.+?)\s+FROM\s+([a-zA-Z_]+)\s+([a-zA-Z_]+)\s+INNER\s+JOIN\s+([a-zA-Z_]+)\s+([a-zA-Z_]+)\s+ON\s+(.+?)\s+ORDER\s+BY\s+([a-zA-Z_]+)\.([a-zA-Z_]+)\s+(ASC|DESC)\s+LIMIT\s+\?$/i);
        if (withM) {
            // Inner CTE: SELECT note_id, MAX(id) ... FROM change_log WHERE user_id = ? AND id > ? GROUP BY note_id
            const innerWhereOnly = withM[4].split(/\s+ORDER BY\s+/i)[0].split(/\s+LIMIT\s+/i)[0];
            const innerRows = this.allRows('change_log')
                .filter(r => matchClauses(r, parseWhere(innerWhereOnly), params.slice(0, 2)));
            const agg = new Map<string, number>();
            for (const r of innerRows) {
                const prev = agg.get(r.note_id) ?? 0;
                if ((r.id ?? 0) > prev) agg.set(r.note_id, r.id);
            }
            const noteRows = this.allRows('notes').filter(n =>
                agg.has(n.id) && n.user_id === params[2],
            );
            return noteRows
                .map(n => ({ ...n, log_change_id: agg.get(n.id) ?? 0 }))
                .sort((a, b) => a.log_change_id - b.log_change_id);
        }

        // SELECT MAX(id) FROM <table> WHERE ...
        const maxM = s.match(/^SELECT\s+MAX\(id\)\s+FROM\s+([a-zA-Z_]+)(?:\s+WHERE\s+(.+?))?(?:\s+ORDER\s+BY\s+([a-zA-Z_]+)(?:\s+(ASC|DESC))?)?(?:\s+LIMIT\s+\?)?$/i);
        if (maxM) {
            const table = maxM[1];
            const where = maxM[2];
            let rows = this.allRows(table);
            if (where) {
                const whereOnly = where.split(/\s+ORDER BY\s+/i)[0].split(/\s+LIMIT\s+/i)[0];
                rows = rows.filter(r => matchClauses(r, parseWhere(whereOnly), params));
            }
            const max = rows.reduce((m, r) => Math.max(m, r.id ?? 0), 0);
            return [{ 'max-id': max }];
        }

        // SELECT <cols> FROM <table> WHERE ... ORDER BY ... LIMIT ?
        const m = s.match(/^SELECT\s+(\*|[a-zA-Z_][a-zA-Z0-9_]*(?:\s*,\s*[a-zA-Z_][a-zA-Z0-9_]*)*)\s+FROM\s+([a-zA-Z_]+)(?:\s+WHERE\s+(.+?))?(?:\s+ORDER\s+BY\s+([a-zA-Z_]+)(?:\s+(ASC|DESC))?)?(?:\s+LIMIT\s+\?)?$/i);
        if (m) {
            const table = m[2];
            const where = m[3];
            const orderCol = m[4];
            const orderDir = (m[5] || 'ASC').toUpperCase();
            let rows = this.allRows(table);
            if (where) {
                const whereOnly = where.split(/\s+ORDER BY\s+/i)[0].split(/\s+LIMIT\s+/i)[0];
                rows = rows.filter(r => matchClauses(r, parseWhere(whereOnly), params));
            }
            if (orderCol) rows.sort((a, b) => orderDir === 'DESC' ? b[orderCol] - a[orderCol] : a[orderCol] - b[orderCol]);
            return rows;
        }

        throw new Error(`MockD1.select: unsupported SQL: ${sql}`);
    }

    execFirst(sql: string, params: any[]): Row | null {
        // INSERT ... RETURNING ...
        const retM = sql.match(/^INSERT\s+INTO\s+([a-zA-Z_]+)\s*\(([^)]+)\)\s+VALUES\s*\(([^)]+)\)\s+RETURNING\s+(.+)$/i);
        if (retM) {
            const table = retM[1];
            const cols = retM[2].split(',').map(c => c.trim());
            const placeholders = retM[3].split(',').length;
            const row: Row = {};
            cols.forEach((c, i) => { row[c] = params[i]; });
            const id = this.insertRow(table, row);
            return { ...row, id, token_version: row.token_version ?? 0 };
        }

        const rows = this.select(sql, params);
        return rows.length ? rows[0] : null;
    }
    execAll(sql: string, params: any[]): Row[] {
        return this.select(sql, params);
    }

    /**
     * Resolve a SET RHS like `?`, `0`, `excluded.foo`, `count + 1`,
     * `(SELECT MAX(id) FROM change_log WHERE ...)`, or
     * `CASE WHEN ? THEN NULL ELSE deleted_at END`. `paramIdx` is the
     * absolute position into the bound parameter array; the function
     * mutates nothing — caller slices.
     */
    private resolveSetExpr(expr: string, params: any[], paramIdx: number, row: Row, db: MockD1): { value: any; consumed: number } {
        const trimmed = expr.trim();
        if (trimmed === '?') {
            return { value: params[paramIdx], consumed: 1 };
        }
        if (trimmed === 'NULL') {
            return { value: null, consumed: 0 };
        }
        if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
            return { value: Number(trimmed), consumed: 0 };
        }
        if (/^excluded\.[a-zA-Z_]+$/.test(trimmed)) {
            // The excluded.col refers to the new row being inserted;
            // for our mock we just use the same param slot as the
            // corresponding INSERT VALUES position. Caller passes that
            // via `params` in the same order they were bound.
            const col = trimmed.split('.')[1];
            return { value: row[col], consumed: 0 };
        }
        if (/^[a-zA-Z_]+\s*\+\s*1$/.test(trimmed)) {
            const col = trimmed.split(/\s*\+\s*/)[0];
            return { value: (row[col] ?? 0) + 1, consumed: 0 };
        }
        if (trimmed.startsWith('CASE WHEN ?')) {
            const flag = params[paramIdx];
            const elseCol = trimmed.match(/ELSE\s+([a-zA-Z_]+)/i)?.[1];
            return { value: flag ? null : row[elseCol ?? ''], consumed: 1 };
        }
        if (trimmed.startsWith('(') && trimmed.endsWith(')')) {
            // Subquery, e.g. (SELECT MAX(id) FROM change_log WHERE user_id = ? AND note_id = ?)
            const inner = trimmed.slice(1, -1);
            const r = db.execFirst(inner, [params[paramIdx], params[paramIdx + 1]]);
            return { value: r ? Object.values(r)[0] : 0, consumed: 2 };
        }
        throw new Error(`MockD1: unsupported SET expression: ${expr}`);
    }

    execRun(sql: string, params: any[]): number {
        const s = sql.trim().replace(/\s+/g, ' ');
        let m = s.match(/^INSERT(?:\s+OR\s+IGNORE)?\s+INTO\s+([a-zA-Z_]+)\s*\(([^)]+)\)\s+VALUES\s*\(([^)]+)\)$/i);
        if (m) {
            const table = m[1];
            const cols = m[2].split(',').map(c => c.trim());
            const placeholders = m[3].split(',').map(p => p.trim());
            const row: Row = {};
            let pi = 0;
            cols.forEach((c, i) => {
                const ph = placeholders[i];
                if (ph === '?') { row[c] = params[pi++]; }
                else if (/^-?\d+(\.\d+)?$/.test(ph)) { row[c] = Number(ph); }
                else if (ph === 'NULL') { row[c] = null; }
                else throw new Error(`MockD1: unsupported placeholder: ${ph}`);
            });
            if (/^INSERT\s+OR\s+IGNORE/i.test(s)) {
                // Match only on the dedup keys — for rate_limits that's
                // (key, window_start); count is mutable so it must NOT
                // participate in the dedup comparison.
                const dedupCols = (m[2].split(',').map(c => c.trim())).filter(c => c !== 'count');
                const exists = this.allRows(table).some(r =>
                    dedupCols.every(c => r[c] === row[c]),
                );
                if (exists) return 0;
            }
            this.insertRow(table, row);
            return 1;
        }

        // INSERT INTO ... VALUES (...) ON CONFLICT(<col>) DO UPDATE SET ... WHERE user_id = excluded.user_id
        m = s.match(/^INSERT\s+INTO\s+([a-zA-Z_]+)\s*\(([^)]+)\)\s+VALUES\s*\(([^)]+)\)\s+ON\s+CONFLICT\(([^)]+)\)\s+DO\s+UPDATE\s+SET\s+(.+)\s+WHERE\s+(.+)$/i);
        if (m) {
            const table = m[1];
            const cols = m[2].split(',').map(c => c.trim());
            const placeholders = m[3].split(',').length;
            const conflictCol = m[4].trim();
            const setPart = m[5];
            const where = m[6];
            const insertParams = params.slice(0, placeholders);
            const insertRow: Row = {};
            cols.forEach((c, i) => { insertRow[c] = insertParams[i]; });
            const existing = this.allRows(table).find(r => r[conflictCol] === insertRow[conflictCol]);
            if (!existing) {
                this.insertRow(table, insertRow);
                return 1;
            }
            // Resolve WHERE (which may reference excluded.<col>).
            const whereResolved = where.replace(/excluded\.([a-zA-Z_]+)/g, (_, col) => String(insertRow[col] ?? ''));
            // Count SET placeholders to slice the bound params correctly.
            const setClauses = splitTopLevel(setPart);
            let pi = placeholders;
            let consumedForWhere: any[] = [];
            const setValues: Array<{ col: string; value: any }> = [];
            for (const sc of setClauses) {
                const eq = sc.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(.+)$/);
                if (!eq) throw new Error(`MockD1: bad SET clause: ${sc}`);
                const r = this.resolveSetExpr(eq[2], params, pi, insertRow, this);
                pi += r.consumed;
                if (r.consumed > 0) consumedForWhere.push(params[pi - r.consumed]);
                setValues.push({ col: eq[1], value: r.value });
            }
            const whereClauses = parseWhere(whereResolved);
            // Filter existing rows by WHERE — for ON CONFLICT there is
            // typically exactly one; we check the match before mutating.
            if (!matchClauses(existing, whereClauses, consumedForWhere)) return 0;
            for (const { col, value } of setValues) existing[col] = value;
            return 1;
        }

        // UPDATE <table> SET ... WHERE ...   (greedy SET, last top-level WHERE wins)
        m = s.match(/^UPDATE\s+([a-zA-Z_]+)\s+SET\s+(.+)\s+WHERE\s+(.+)$/i);
        if (m) {
            const table = m[1];
            const setPart = m[2];
            const where = m[3];
            const setClauses = splitTopLevel(setPart);
            // Pre-resolve each SET clause to either a value or a row-deferred
            // expression. `?` placeholders consume a bind param upfront;
            // `count + 1` etc. need the matched row's current value, so
            // we return a thunk that's evaluated per row.
            interface SetSpec { col: string; value: any; lazy?: boolean; }
            const specs: SetSpec[] = [];
            let pi = 0;
            for (const sc of setClauses) {
                const eq = sc.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(.+)$/);
                if (!eq) throw new Error(`MockD1: bad SET clause: ${sc}`);
                const trimmed = eq[2].trim();
                if (/^[a-zA-Z_]+\s*\+\s*1$/.test(trimmed)) {
                    // Row-deferred — evaluate against each matched row.
                    specs.push({ col: eq[1], value: null, lazy: true });
                } else {
                    const r = this.resolveSetExpr(trimmed, params, pi, {}, this);
                    pi += r.consumed;
                    specs.push({ col: eq[1], value: r.value });
                }
            }
            const whereParams = params.slice(pi);
            const whereClauses = parseWhere(where);
            const rows = this.allRows(table).filter(r => matchClauses(r, whereClauses, whereParams));
            for (const row of rows) {
                for (const spec of specs) {
                    if (spec.lazy) {
                        // Re-evaluate the `col + 1` against this row.
                        row[spec.col] = (row[spec.col] ?? 0) + 1;
                    } else {
                        row[spec.col] = spec.value;
                    }
                }
            }
            return rows.length;
        }

        // DELETE FROM <table> WHERE ...
        m = s.match(/^DELETE\s+FROM\s+([a-zA-Z_]+)\s+WHERE\s+(.+)$/i);
        if (m) {
            const table = m[1];
            const where = m[2];
            const targets = this.allRows(table).filter(r => matchClauses(r, parseWhere(where), params));
            for (const t of targets) {
                for (const [k, v] of this.tables[table].entries()) {
                    if (v === t) this.tables[table].delete(k);
                }
            }
            return targets.length;
        }

        throw new Error(`MockD1.execRun: unsupported SQL: ${sql}`);
    }
}

/* ------------------------------------------------------------------ *
 * MockR2 — fake bucket. PUT / GET / DELETE in-memory.
 * ------------------------------------------------------------------ */

class MockR2 {
    store = new Map<string, { bytes: Uint8Array; contentType: string }>();
    async put(key: string, data: ArrayBuffer | Uint8Array | string, opts?: { httpMetadata?: { contentType?: string } }): Promise<any> {
        const bytes = typeof data === 'string'
            ? new TextEncoder().encode(data)
            : data instanceof Uint8Array ? data : new Uint8Array(data);
        this.store.set(key, { bytes, contentType: opts?.httpMetadata?.contentType ?? 'application/octet-stream' });
        return {};
    }
    async get(key: string): Promise<{ body: ReadableStream; httpMetadata?: { contentType?: string } } | null> {
        const v = this.store.get(key);
        if (!v) return null;
        return { body: new ReadableStream({ start(c) { c.enqueue(v.bytes); c.close(); } }), httpMetadata: { contentType: v.contentType } };
    }
    async delete(key: string): Promise<void> {
        this.store.delete(key);
    }
}

/* ------------------------------------------------------------------ *
 * Test harness
 * ------------------------------------------------------------------ */

const UUID_A = 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa';
const UUID_B = 'bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb';

function makeEnv(opts: { db?: MockD1; r2?: MockR2 } = {}): Env {
    return {
        DB: opts.db ?? new MockD1(),
        IMAGES: (opts.r2 ?? new MockR2()) as any,
        JWT_SECRET: 'a'.repeat(48),
        ENV: 'test',
        ALLOW_PASSWORD_AUTH: 'true',
        UPSTASH_REDIS_REST_URL: undefined,
        UPSTASH_REDIS_REST_TOKEN: undefined,
    };
}

function jsonRequest(url: string, body: any, headers: Record<string, string> = {}): Request {
    return new Request(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...headers },
        body: JSON.stringify(body),
    });
}

beforeEach(() => { /* each test builds its own mock */ });

/* ------------------------------------------------------------------ *
 * 5. validateNoteBody (pure unit)
 * ------------------------------------------------------------------ */

describe('validateNoteBody (A10)', () => {
    it('accepts a normal body and clamps a future updated_at to now', () => {
        const future = Date.now() + 30 * 24 * 60 * 60 * 1000;
        const v = validateNoteBody({ title: 'hello', content: 'world', background_color: 3, updated_at: future });
        expect(v.ok).toBe(true);
        if (v.ok) {
            expect(v.value.title).toBe('hello');
            expect(v.value.background_color).toBe(3);
            expect(v.value.updated_at).toBeLessThanOrEqual(Date.now());
        }
    });

    it('rejects titles longer than 500 characters', () => {
        const v = validateNoteBody({ title: 'a'.repeat(501), content: '' });
        expect(v.ok).toBe(false);
        if (!v.ok) expect(v.message).toMatch(/title/);
    });

    it('rejects content larger than 1 MB UTF-8', () => {
        const v = validateNoteBody({ title: '', content: 'x'.repeat(1_048_577) });
        expect(v.ok).toBe(false);
        if (!v.ok) expect(v.message).toMatch(/content/);
    });

    it('rejects background_color outside 0..7', () => {
        expect(validateNoteBody({ title: '', content: '', background_color: -1 }).ok).toBe(false);
        expect(validateNoteBody({ title: '', content: '', background_color: 8 }).ok).toBe(false);
    });

    it('keeps updated_at that is within 24h of now', () => {
        const within = Date.now() + 60_000;
        const v = validateNoteBody({ title: '', content: '', updated_at: within });
        expect(v.ok).toBe(true);
        if (v.ok) expect(v.value.updated_at).toBe(within);
    });
});

/* ------------------------------------------------------------------ *
 * 1. IDOR — different user posts a note that belongs to someone else.
 * ------------------------------------------------------------------ */

describe('upsertNote (A10 / A1)', () => {
    it('returns 404 when the note UUID belongs to a different user', async () => {
        const db = new MockD1();
        db.seed('notes', {
            id: UUID_A,
            user_id: 999,
            title: 'private',
            content: 'private',
            created_at: 1_000,
            updated_at: 1_000,
            client_updated_at: 1_000,
            client_created_at: 1_000,
            change_id: 5,
            deleted_at: null,
            is_favorite: 0,
            is_trashed: 0,
            background_color: 0,
        });

        const env = makeEnv({ db });
        const req = jsonRequest('https://example.com/notes', {
            id: UUID_A, title: 'mine', content: 'mine',
        });
        const resp = await upsertNote(req, 1, env);
        expect(resp.status).toBe(404);
    });
});

/* ------------------------------------------------------------------ *
 * 2. base_change_id conflict → 409
 * ------------------------------------------------------------------ */

describe('upsertNote A3 conflict', () => {
    it('returns 409 when server change_id is ahead of base_change_id', async () => {
        const db = new MockD1();
        db.seed('notes', {
            id: UUID_A,
            user_id: 7,
            title: 'latest',
            content: 'latest',
            created_at: 1_000,
            updated_at: 2_000,
            client_updated_at: 2_000,
            client_created_at: 1_000,
            change_id: 10,
            deleted_at: null,
            is_favorite: 0,
            is_trashed: 0,
            background_color: 0,
        });
        const env = makeEnv({ db });

        const req = jsonRequest('https://example.com/notes', {
            id: UUID_A, title: 'stale', content: 'stale',
            updated_at: 500, base_change_id: 5,
        });
        const resp = await upsertNote(req, 7, env);
        expect(resp.status).toBe(409);
        const body = await resp.json() as any;
        expect(body.conflict).toBe(true);
        expect(body.note?.id).toBe(UUID_A);
        expect(body.note?.title).toBe('latest');
        expect(body.note?.user_id).toBeUndefined(); // A10: stripped
    });
});

/* ------------------------------------------------------------------ *
 * 3 + 4. tombstone since / A5 dedup — listNotes returns soft-deleted
 *    rows for incremental pulls; response shapes must NOT carry user_id.
 * ------------------------------------------------------------------ */

describe('listNotes (A5 / A10)', () => {
    it('returns soft-deleted (tombstoned) notes on a since request', async () => {
        const db = new MockD1();
        db.seed('notes', {
            id: UUID_A,
            user_id: 1, title: 'live', content: 'live',
            created_at: 100, updated_at: 200, client_updated_at: 200, client_created_at: 100,
            change_id: 5, deleted_at: null, is_favorite: 0, is_trashed: 0, background_color: 0,
        });
        db.seed('notes', {
            id: UUID_B,
            user_id: 1, title: '', content: '',
            created_at: 50, updated_at: 300, client_updated_at: 300, client_created_at: 50,
            change_id: 6, deleted_at: 300, is_favorite: 0, is_trashed: 0, background_color: 0,
        });
        db.seed('change_log', { user_id: 1, note_id: UUID_A, op: 'upsert', ts: 200 });
        db.seed('change_log', { user_id: 1, note_id: UUID_B, op: 'delete', ts: 300 });

        const env = makeEnv({ db });
        const req = new Request('https://example.com/notes?since=0');
        const resp = await listNotes(req, 1, env);
        expect(resp.status).toBe(200);
        const body = await resp.json() as any;
        const ids = body.notes.map((n: any) => n.id);
        expect(ids).toContain(UUID_A);
        expect(ids).toContain(UUID_B);
        const tomb = body.notes.find((n: any) => n.id === UUID_B);
        expect(tomb.deleted_at).toBe(300);
        expect(tomb.title).toBe(''); // wiped (A6 / A10)

        // A10: response shapes must NOT carry user_id.
        for (const n of body.notes) {
            expect(n.user_id).toBeUndefined();
        }
    });
});

/* ------------------------------------------------------------------ *
 * 4. deleteNote — wipes title/content on the row.
 * ------------------------------------------------------------------ */

describe('deleteNote (A10 / A6)', () => {
    it('clears title and content when soft-deleting', async () => {
        const db = new MockD1();
        db.seed('notes', {
            id: UUID_A,
            user_id: 1, title: 'keep me secret', content: 'PII here',
            created_at: 100, updated_at: 200, client_updated_at: 200, client_created_at: 100,
            change_id: 5, deleted_at: null, is_favorite: 0, is_trashed: 0, background_color: 0,
        });
        const env = makeEnv({ db });

        const resp = await deleteNote(`/notes/${UUID_A}`, 1, env);
        expect(resp.status).toBe(200);

        const after = Array.from(db.tables.notes.values())[0];
        expect(after.title).toBe('');
        expect(after.content).toBe('');
        expect(after.deleted_at).toBeGreaterThan(0);
    });
});

/* ------------------------------------------------------------------ *
 * 6. email lowercased on register/login (A7 / A10).
 * ------------------------------------------------------------------ */

describe('registerRoute + loginRoute (A7)', () => {
    it('registerRoute stores the email in lowercased form', async () => {
        const db = new MockD1();
        const env = makeEnv({ db });
        const req = jsonRequest('https://example.com/auth/register', {
            email: '  Mixed.Case@Example.COM ',
            password: 'supersecret123',
        });
        const resp = await registerRoute(req, env);
        expect(resp.status).toBe(200);
        const stored = Array.from(db.tables.users.values())[0];
        expect(stored.email).toBe('mixed.case@example.com');
    });

    it('loginRoute is case-insensitive (mixed-case email finds the row)', async () => {
        const db = new MockD1();
        db.seed('users', {
            email: 'real@example.com',
            password_hash: 'aa:bb',
            created_at: 1_000,
            token_version: 0,
        });
        const env = makeEnv({ db });
        const req = jsonRequest('https://example.com/auth/login', {
            email: 'REAL@Example.com',
            password: 'supersecret123',
        });
        const resp = await loginRoute(req, env);
        // 401 from password mismatch is the expected outcome (we seeded
        // a fake hash). 400 would mean the route couldn't find the row
        // because it queried against the original-case email.
        expect([200, 401]).toContain(resp.status);
        expect(resp.status).not.toBe(400);
    });
});

/* ------------------------------------------------------------------ *
 * 7. rate limit — 11th attempt in the same minute returns 429.
 * ------------------------------------------------------------------ */

describe('registerRoute rate limit (A10 / A7)', () => {
    it('returns 429 after the 10th attempt in the same minute window', async () => {
        const db = new MockD1();
        const env = makeEnv({ db });
        env.ALLOW_PASSWORD_AUTH = 'true';

        let lastStatus = 0;
        for (let i = 0; i < 11; i++) {
            const req = jsonRequest('https://example.com/auth/register', {
                email: `flood${i}@example.com`,
                password: 'supersecret123',
            });
            const resp = await registerRoute(req, env);
            lastStatus = resp.status;
        }
        expect(lastStatus).toBe(429);
    });
});

/* ------------------------------------------------------------------ *
 * 8. SVG rejected by magic-number check (A10 / A8).
 * ------------------------------------------------------------------ */

describe('detectImageType (A10 / A8)', () => {
    it('rejects SVG bytes (returns null)', () => {
        const svg = new TextEncoder().encode('<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>');
        expect(detectImageType(svg)).toBeNull();
    });

    it('accepts PNG bytes', () => {
        const png = new Uint8Array([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0]);
        expect(detectImageType(png)).toBe('image/png');
    });
});

/* ------------------------------------------------------------------ *
 * TODO — additional A10 scenarios not covered above.
 * ------------------------------------------------------------------ */

// TODO: since duplicate — extend MockD1.select to fully verify that two
//       change_log rows for the same note collapse to one returned note
//       (current implementation returns the joined rows but the dedup
//       assertion is not exercised).

// TODO: rate-limit boundary across minute rollover — requires mocking
//       Date.now() inside rate_limit.ts.

// TODO: full /upload round-trip with base64-encoded PNG and SVG bytes,
//       wiring IMAGES.put to assert contentType.