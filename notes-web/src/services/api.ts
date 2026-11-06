import type { Note, User } from '../types/note';
import {
  getStoredToken,
  setStoredToken,
  getStoredUser,
  setStoredUser,
  clearAuthSession,
  updateLastActive,
} from './storage';

export { getStoredToken, setStoredToken, getStoredUser, setStoredUser };

// Determine base URL:
// In dev: Vite proxies /auth, /notes, /upload to https://notes-backend.raximjonozod1727.workers.dev
// In production or custom domain:
const DEFAULT_PROD_API = 'https://notes-backend.raximjonozod1727.workers.dev';
const API_BASE: string = (() => {
  if (typeof window === 'undefined') return '';
  if (import.meta.env.VITE_API_BASE_URL) {
    return import.meta.env.VITE_API_BASE_URL;
  }
  const hostname = window.location.hostname;
  // If running on custom domain notes.raximjonov.uz or workers.dev, routes are same-origin
  if (hostname === 'notes.raximjonov.uz' || hostname.endsWith('.workers.dev')) {
    return '';
  }
  // If running on localhost in dev, Vite proxy handles relative paths
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return '';
  }
  // Otherwise default to the live worker
  return DEFAULT_PROD_API;
})();

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getStoredToken();
  const headers = new Headers(options.headers || {});

  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const fullUrl = API_BASE ? `${API_BASE}${path}` : path;

  const res = await fetch(fullUrl, {
    ...options,
    headers,
  });

  if (res.status === 401) {
    clearAuthSession();
    window.dispatchEvent(new Event('notes:unauthorized'));
    throw new Error('Sessiya muddati tugadi. Qaytadan kiring.');
  }

  if (!res.ok) {
    let msg = `So'rov xatosi (${res.status})`;
    try {
      const errData = await res.json();
      if (errData && errData.error) msg = errData.error;
      else if (errData && errData.message) msg = errData.message;
    } catch {
      // not json
    }
    throw new Error(msg);
  }

  // Update session active timestamp on any successful authenticated request
  if (token) {
    updateLastActive();
  }

  return res.json() as Promise<T>;
}

export const api = {
  // Google Auth (Mobile 1:1)
  async loginGoogle(idToken: string): Promise<{ token: string; user: User }> {
    const data = await request<{ token: string; user: User }>('/auth/google', {
      method: 'POST',
      body: JSON.stringify({ id_token: idToken }),
    });
    setStoredToken(data.token);
    setStoredUser(data.user);
    updateLastActive();
    return data;
  },

  // Get current user profile
  async getMe(): Promise<User> {
    const data = await request<{ user: User }>('/auth/me');
    setStoredUser(data.user);
    updateLastActive();
    return data.user;
  },

  // Logout
  async logout(): Promise<void> {
    try {
      await request('/auth/logout', { method: 'POST' });
    } catch {
      // ignore
    } finally {
      clearAuthSession();
    }
  },

  // Notes
  async getNotes(since?: number): Promise<{
    notes: Note[];
    sync_cursor: number;
    sync_timestamp: number;
    has_more: boolean;
  }> {
    const url = since !== undefined ? `/notes?since=${since}` : '/notes';
    return request(url);
  },

  async saveNote(note: Partial<Note> & { id: string }): Promise<{ note: Note }> {
    const now = Date.now();
    return request('/notes', {
      method: 'POST',
      body: JSON.stringify({
        id: note.id,
        title: note.title || '',
        content: note.content || '',
        is_favorite: note.is_favorite ? 1 : 0,
        is_trashed: note.is_trashed ? 1 : 0,
        background_color: note.background_color ?? 0,
        background_color_key: note.background_color_key || 'default',
        updated_at: note.updated_at || now,
        created_at: note.created_at || now,
      }),
    });
  },

  async deleteNote(id: string): Promise<{ deleted: string; change_id: number }> {
    return request(`/notes/${id}`, {
      method: 'DELETE',
    });
  },

  // File Upload
  async uploadFile(file: File): Promise<{ r2_key: string; url: string }> {
    const formData = new FormData();
    formData.append('file', file);
    return request('/upload', {
      method: 'POST',
      body: formData,
    });
  },
};
