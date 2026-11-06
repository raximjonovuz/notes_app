import type { Note, User } from '../types/note';

const TOKEN_KEY = 'notes_auth_token';
const USER_KEY = 'notes_auth_user';
const LAST_ACTIVE_KEY = 'notes_last_active_at';
const NOTES_CACHE_KEY = 'notes_cached_notes';
const GUEST_NOTES_KEY = 'notes_guest_notes';
const PINNED_STORAGE_KEY = 'notes_pinned_ids';
const ONBOARDED_KEY = 'notes_onboarded_guest';

// 3 days in milliseconds (3 * 24 * 60 * 60 * 1000)
export const SESSION_INACTIVITY_LIMIT_MS = 3 * 24 * 60 * 60 * 1000;

export function updateLastActive(): void {
  localStorage.setItem(LAST_ACTIVE_KEY, Date.now().toString());
}

export function isSessionExpired(): boolean {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) return false;

  const raw = localStorage.getItem(LAST_ACTIVE_KEY);
  if (!raw) {
    // If token exists but no last active timestamp recorded, initialize it
    updateLastActive();
    return false;
  }

  const lastActive = parseInt(raw, 10);
  if (isNaN(lastActive)) return true;

  return Date.now() - lastActive > SESSION_INACTIVITY_LIMIT_MS;
}

export function getStoredToken(): string | null {
  if (isSessionExpired()) {
    clearAuthSession();
    return null;
  }
  updateLastActive();
  return localStorage.getItem(TOKEN_KEY);
}

export function setStoredToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
    updateLastActive();
  } else {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(LAST_ACTIVE_KEY);
  }
}

export function getStoredUser(): User | null {
  if (isSessionExpired()) {
    clearAuthSession();
    return null;
  }
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function setStoredUser(user: User | null): void {
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  } else {
    localStorage.removeItem(USER_KEY);
  }
}

export function clearAuthSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(LAST_ACTIVE_KEY);
  localStorage.removeItem(NOTES_CACHE_KEY);
}

// Guest mode tracking
export function isGuestOnboarded(): boolean {
  return localStorage.getItem(ONBOARDED_KEY) === 'true';
}

export function setGuestOnboarded(value: boolean): void {
  if (value) {
    localStorage.setItem(ONBOARDED_KEY, 'true');
  } else {
    localStorage.removeItem(ONBOARDED_KEY);
  }
}

// Cached Notes (for authenticated users)
export function getCachedNotes(): Note[] {
  try {
    const raw = localStorage.getItem(NOTES_CACHE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function setCachedNotes(notes: Note[]): void {
  try {
    localStorage.setItem(NOTES_CACHE_KEY, JSON.stringify(notes));
  } catch (e) {
    console.warn('Failed to cache notes in localStorage:', e);
  }
}

// Guest Notes (for unauthenticated local storage)
export function getGuestNotes(): Note[] {
  try {
    const raw = localStorage.getItem(GUEST_NOTES_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export function setGuestNotes(notes: Note[]): void {
  try {
    localStorage.setItem(GUEST_NOTES_KEY, JSON.stringify(notes));
  } catch (e) {
    console.warn('Failed to save guest notes:', e);
  }
}

export function clearGuestNotes(): void {
  localStorage.removeItem(GUEST_NOTES_KEY);
}

// Pinned Note IDs
export function getPinnedIds(): Set<string> {
  try {
    const raw = localStorage.getItem(PINNED_STORAGE_KEY);
    return raw ? new Set(JSON.parse(raw)) : new Set();
  } catch {
    return new Set();
  }
}

export function setPinnedIds(set: Set<string>): void {
  try {
    localStorage.setItem(PINNED_STORAGE_KEY, JSON.stringify(Array.from(set)));
  } catch {
    // ignore
  }
}
