import { Env } from './types';
import { corsHeaders, errorResponse, getUserId } from './utils';
import { googleAuthRoute, loginRoute, logoutRoute, meRoute, registerRoute } from './routes/auth';
import { deleteNote, getNote, listNotes, upsertNote, runRetention } from './routes/notes';
import { cleanupOrphanImages, deleteFile, handleUpload, serveFile } from './routes/upload';

export default {
    async fetch(
        request: Request,
        env: Env,
        _ctx: ExecutionContext
    ): Promise<Response> {
        const url = new URL(request.url);
        const path = url.pathname;
        const method = request.method;

        // Single outer try/catch so server-side misconfiguration (missing
        // JWT_SECRET, etc.) translates to a 500 rather than a 5xx-shaped
        // crash. Route handlers stay throw-clean.
        try {
            // CORS preflight
            if (method === 'OPTIONS') {
                return new Response(null, { status: 204, headers: corsHeaders() });
            }

            // Health / root
            if (path === '/' && method === 'GET') {
                return new Response('Notes Backend API — v5.3', {
                    headers: { 'Content-Type': 'text/plain', ...corsHeaders() },
                });
            }

            // Public auth routes
            if (path === '/auth/register' && method === 'POST') {
                return registerRoute(request, env);
            }
            if (path === '/auth/login' && method === 'POST') {
                return loginRoute(request, env);
            }
            if (path === '/auth/google' && method === 'POST') {
                return googleAuthRoute(request, env);
            }

            // Authenticated routes — bearer token required.
            // V5.3 (BUG 6 fix): /files/<key> was previously a public route and
            // leaked R2 URLs across users. Now everything below this line is
            // gated by getUserId(); serveFile() checks ownership against the
            // images table.
            const userId = await getUserId(request, env);
            if (!userId) {
                return errorResponse('Unauthorized', 401);
            }

            if (path === '/auth/me' && method === 'GET') {
                return meRoute(userId, env);
            }
            if (path === '/auth/logout' && method === 'POST') {
                return logoutRoute(userId, env);
            }
            if (path === '/notes' && method === 'GET') {
                return listNotes(request, userId, env);
            }
            if (path === '/notes' && method === 'POST') {
                return upsertNote(request, userId, env);
            }
            if (path.startsWith('/notes/') && method === 'GET') {
                return getNote(path, userId, env);
            }
            if (path.startsWith('/notes/') && method === 'DELETE') {
                return deleteNote(path, userId, env);
            }
            if (path === '/upload' && method === 'POST') {
                return handleUpload(request, env, userId);
            }
            if (path.startsWith('/files/') && method === 'GET') {
                return serveFile(path, userId, env);
            }
            // A8 — explicit file delete. Owner-scoped: 404 for both
            // "no such key" and "key belongs to another user" so
            // cross-user existence is never leaked.
            if (path.startsWith('/files/') && method === 'DELETE') {
                return deleteFile(path, userId, env);
            }

            return errorResponse('Not Found', 404);
        } catch (e) {
            // Never log secrets — `e` may include user input or config echoes.
            console.error('Unhandled error:', e instanceof Error ? e.message : String(e));
            return errorResponse('Internal server error', 500);
        }
    },

    // A6: scheduled handler — runs daily at 03:00 UTC (see wrangler.toml
    // `[triggers] crons`). Hard-deletes change_log and soft-deleted notes
    // older than the 90-day retention window.
    //
    // A8: also runs `cleanupOrphanImages` immediately after, so the same
    // cron tick reaps R2 blobs whose `note_id` is NULL and that are
    // older than 7 days. Both tasks share the same `waitUntil` slot;
    // they touch disjoint tables so order doesn't matter.
    //
    // We do not block on the promise; `ctx.waitUntil` lets the Worker
    // keep the isolate alive until both jobs finish without holding
    // the cron response open.
    async scheduled(
        _event: ScheduledController,
        env: Env,
        ctx: ExecutionContext
    ): Promise<void> {
        ctx.waitUntil((async () => {
            await runRetention(env);
            await cleanupOrphanImages(env);
        })());
    },
} satisfies ExportedHandler<Env>;
