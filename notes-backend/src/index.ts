import { Env } from './types';
import { corsHeaders, errorResponse, getUserId } from './utils';
import { googleAuthRoute, loginRoute, meRoute, registerRoute } from './routes/auth';
import { deleteNote, getNote, listNotes, upsertNote } from './routes/notes';
import { handleUpload, serveFile } from './routes/upload';

export default {
    async fetch(
        request: Request,
        env: Env,
        _ctx: ExecutionContext
    ): Promise<Response> {
        const url = new URL(request.url);
        const path = url.pathname;
        const method = request.method;

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
            return meRoute(request, env);
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

        return errorResponse('Not Found', 404);
    },
} satisfies ExportedHandler<Env>;
