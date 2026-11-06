import React, { createContext, useContext, useEffect, useState, useCallback, useMemo } from 'react';
import type { Note, NoteColorKey } from '../types/note';
import { api } from '../services/api';
import { useAuth } from './AuthContext';
import {
  getCachedNotes,
  setCachedNotes,
  getGuestNotes,
  setGuestNotes,
  clearGuestNotes,
  getPinnedIds,
  setPinnedIds,
} from '../services/storage';

export type NotesFilter = 'all' | 'favorites' | 'trash';
export type ViewMode = 'grid' | 'linear';

interface NotesContextType {
  notes: Note[];
  filteredNotes: Note[];
  pinnedNotes: Note[];
  otherNotes: Note[];
  activeFilter: NotesFilter;
  setActiveFilter: (filter: NotesFilter) => void;
  searchQuery: string;
  setSearchQuery: (query: string) => void;
  viewMode: ViewMode;
  setViewMode: (mode: ViewMode) => void;
  isSyncing: boolean;
  lastSyncTime: Date | null;
  syncError: string | null;
  addNote: (data: {
    title: string;
    content: string;
    colorKey?: NoteColorKey;
    isFavorite?: boolean;
    isPinned?: boolean;
  }) => Promise<Note>;
  editNote: (id: string, updates: Partial<Note> & { colorKey?: NoteColorKey }) => Promise<void>;
  trashNote: (id: string) => Promise<void>;
  restoreNote: (id: string) => Promise<void>;
  deletePermanent: (id: string) => Promise<void>;
  togglePin: (id: string) => void;
  toggleFavorite: (id: string) => Promise<void>;
  changeColor: (id: string, colorKey: NoteColorKey) => Promise<void>;
  syncNow: () => Promise<void>;
}

const NotesContext = createContext<NotesContextType | undefined>(undefined);

function generateUUID(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export const NotesProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, user } = useAuth();

  // Initialize from cache immediately so UI renders instantly
  const [notes, setNotes] = useState<Note[]>(() => {
    return isAuthenticated ? getCachedNotes() : getGuestNotes();
  });

  const [activeFilter, setActiveFilter] = useState<NotesFilter>('all');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [viewMode, setViewMode] = useState<ViewMode>(() => {
    const saved = localStorage.getItem('notes_view_mode');
    return (saved === 'linear' ? 'linear' : 'grid') as ViewMode;
  });
  const [isSyncing, setIsSyncing] = useState<boolean>(false);
  const [lastSyncTime, setLastSyncTime] = useState<Date | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [pinnedIds, setPinnedState] = useState<Set<string>>(getPinnedIds);

  const setAndSaveViewMode = (mode: ViewMode) => {
    setViewMode(mode);
    localStorage.setItem('notes_view_mode', mode);
  };

  // Sync from server (with guest note migration)
  const syncNow = useCallback(async () => {
    if (!isAuthenticated) {
      const guest = getGuestNotes();
      const pins = getPinnedIds();
      setNotes(guest.map((n) => ({ ...n, isPinned: pins.has(n.id) })));
      return;
    }

    setIsSyncing(true);
    setSyncError(null);

    try {
      // 1. Check if there are anonymous/guest notes to migrate
      const guestNotes = getGuestNotes();
      if (guestNotes.length > 0) {
        for (const gn of guestNotes) {
          try {
            await api.saveNote(gn);
          } catch (err) {
            console.warn('Failed to migrate note:', gn.id, err);
          }
        }
        clearGuestNotes();
      }

      // 2. Fetch server notes
      const res = await api.getNotes();
      const pins = getPinnedIds();
      const serverNotes = res.notes || [];

      const merged = serverNotes.map((n) => ({
        ...n,
        isPinned: pins.has(n.id),
      }));

      setNotes(merged);
      setCachedNotes(merged);
      setLastSyncTime(new Date());
    } catch (e: any) {
      console.error('Notes sync error:', e);
      setSyncError(e.message || 'Sinxronizatsiya xatosi');
    } finally {
      setIsSyncing(false);
    }
  }, [isAuthenticated]);

  // When auth changes, load cache or trigger sync
  useEffect(() => {
    if (isAuthenticated) {
      // First populate from cache
      const cached = getCachedNotes();
      if (cached.length > 0) {
        const pins = getPinnedIds();
        setNotes(cached.map((n) => ({ ...n, isPinned: pins.has(n.id) })));
      }
      // Then sync from remote
      syncNow();
    } else {
      const guest = getGuestNotes();
      const pins = getPinnedIds();
      setNotes(guest.map((n) => ({ ...n, isPinned: pins.has(n.id) })));
    }
  }, [isAuthenticated, user?.id, syncNow]);

  // Persist pinned state
  const togglePin = useCallback((id: string) => {
    setPinnedState((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      setPinnedIds(next);

      // Update notes state
      setNotes((currentNotes) => {
        const updated = currentNotes.map((n) => (n.id === id ? { ...n, isPinned: next.has(id) } : n));
        if (isAuthenticated) {
          setCachedNotes(updated);
        } else {
          setGuestNotes(updated);
        }
        return updated;
      });

      return next;
    });
  }, [isAuthenticated]);

  // Add Note
  const addNote = useCallback(
    async ({
      title,
      content,
      colorKey = 'default',
      isFavorite = false,
      isPinned = false,
    }: {
      title: string;
      content: string;
      colorKey?: NoteColorKey;
      isFavorite?: boolean;
      isPinned?: boolean;
    }): Promise<Note> => {
      const now = Date.now();
      const newId = generateUUID();

      const newNote: Note = {
        id: newId,
        title: title.trim(),
        content: content.trim(),
        created_at: now,
        updated_at: now,
        client_updated_at: now,
        change_id: 0,
        deleted_at: null,
        is_favorite: isFavorite ? 1 : 0,
        is_trashed: 0,
        background_color: 0,
        background_color_key: colorKey,
        isPinned,
      };

      if (isPinned) {
        setPinnedState((prev) => {
          const next = new Set(prev);
          next.add(newId);
          setPinnedIds(next);
          return next;
        });
      }

      // Optimistic update
      setNotes((prev) => {
        const nextNotes = [newNote, ...prev];
        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      if (isAuthenticated) {
        try {
          const res = await api.saveNote(newNote);
          if (res?.note) {
            setNotes((prev) => {
              const updated = prev.map((n) =>
                n.id === newId ? { ...res.note, isPinned: isPinned || false } : n
              );
              setCachedNotes(updated);
              return updated;
            });
          }
        } catch (e: any) {
          console.error('Failed to sync new note:', e);
          setSyncError("Qayd serverga saqlanmadi (oflayn saqlandi)");
        }
      }

      return newNote;
    },
    [isAuthenticated]
  );

  // Edit Note
  const editNote = useCallback(
    async (id: string, updates: Partial<Note> & { colorKey?: NoteColorKey }) => {
      const now = Date.now();
      let updatedNote: Note | undefined;

      setNotes((prev) => {
        const nextNotes = prev.map((n) => {
          if (n.id !== id) return n;
          updatedNote = {
            ...n,
            ...updates,
            background_color_key: updates.colorKey || updates.background_color_key || n.background_color_key,
            updated_at: now,
            client_updated_at: now,
          };
          return updatedNote;
        });

        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      if (isAuthenticated && updatedNote) {
        try {
          await api.saveNote(updatedNote);
        } catch (e) {
          console.error('Failed to sync edited note:', e);
        }
      }
    },
    [isAuthenticated]
  );

  // Trash Note (soft delete)
  const trashNote = useCallback(
    async (id: string) => {
      const now = Date.now();
      let targetNote: Note | undefined;

      setNotes((prev) => {
        const nextNotes = prev.map((n) => {
          if (n.id !== id) return n;
          targetNote = { ...n, is_trashed: 1, updated_at: now };
          return targetNote;
        });

        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      if (isAuthenticated && targetNote) {
        try {
          await api.saveNote(targetNote);
        } catch (e) {
          console.error('Failed to trash note:', e);
        }
      }
    },
    [isAuthenticated]
  );

  // Restore Note
  const restoreNote = useCallback(
    async (id: string) => {
      const now = Date.now();
      let targetNote: Note | undefined;

      setNotes((prev) => {
        const nextNotes = prev.map((n) => {
          if (n.id !== id) return n;
          targetNote = { ...n, is_trashed: 0, updated_at: now };
          return targetNote;
        });

        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      if (isAuthenticated && targetNote) {
        try {
          await api.saveNote(targetNote);
        } catch (e) {
          console.error('Failed to restore note:', e);
        }
      }
    },
    [isAuthenticated]
  );

  // Delete Permanently
  const deletePermanent = useCallback(
    async (id: string) => {
      setNotes((prev) => {
        const nextNotes = prev.filter((n) => n.id !== id);
        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      setPinnedState((prev) => {
        if (!prev.has(id)) return prev;
        const next = new Set(prev);
        next.delete(id);
        setPinnedIds(next);
        return next;
      });

      if (isAuthenticated) {
        try {
          await api.deleteNote(id);
        } catch (e) {
          console.error('Failed to permanently delete note:', e);
        }
      }
    },
    [isAuthenticated]
  );

  // Toggle Favorite
  const toggleFavorite = useCallback(
    async (id: string) => {
      const now = Date.now();
      let targetNote: Note | undefined;

      setNotes((prev) => {
        const nextNotes = prev.map((n) => {
          if (n.id !== id) return n;
          const nextFav = n.is_favorite ? 0 : 1;
          targetNote = { ...n, is_favorite: nextFav, updated_at: now };
          return targetNote;
        });

        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      if (isAuthenticated && targetNote) {
        try {
          await api.saveNote(targetNote);
        } catch (e) {
          console.error('Failed to toggle favorite:', e);
        }
      }
    },
    [isAuthenticated]
  );

  // Change Color
  const changeColor = useCallback(
    async (id: string, colorKey: NoteColorKey) => {
      const now = Date.now();
      let targetNote: Note | undefined;

      setNotes((prev) => {
        const nextNotes = prev.map((n) => {
          if (n.id !== id) return n;
          targetNote = { ...n, background_color_key: colorKey, updated_at: now };
          return targetNote;
        });

        if (isAuthenticated) {
          setCachedNotes(nextNotes);
        } else {
          setGuestNotes(nextNotes);
        }
        return nextNotes;
      });

      if (isAuthenticated && targetNote) {
        try {
          await api.saveNote(targetNote);
        } catch (e) {
          console.error('Failed to change color:', e);
        }
      }
    },
    [isAuthenticated]
  );

  // Filter notes based on active filter and search query
  const filteredNotes = useMemo(() => {
    let result = notes;

    // Filter by tab
    if (activeFilter === 'trash') {
      result = result.filter((n) => n.is_trashed === 1);
    } else {
      result = result.filter((n) => n.is_trashed === 0);
      if (activeFilter === 'favorites') {
        result = result.filter((n) => n.is_favorite === 1);
      }
    }

    // Filter by search query
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase().trim();
      result = result.filter(
        (n) => n.title.toLowerCase().includes(q) || n.content.toLowerCase().includes(q)
      );
    }

    return result;
  }, [notes, activeFilter, searchQuery]);

  // Partition into Pinned and Others
  const { pinnedNotes, otherNotes } = useMemo(() => {
    if (activeFilter === 'trash') {
      return { pinnedNotes: [], otherNotes: filteredNotes };
    }
    const pinned: Note[] = [];
    const others: Note[] = [];
    filteredNotes.forEach((n) => {
      if (pinnedIds.has(n.id)) {
        pinned.push(n);
      } else {
        others.push(n);
      }
    });
    return { pinnedNotes: pinned, otherNotes: others };
  }, [filteredNotes, pinnedIds, activeFilter]);

  return (
    <NotesContext.Provider
      value={{
        notes,
        filteredNotes,
        pinnedNotes,
        otherNotes,
        activeFilter,
        setActiveFilter,
        searchQuery,
        setSearchQuery,
        viewMode,
        setViewMode: setAndSaveViewMode,
        isSyncing,
        lastSyncTime,
        syncError,
        addNote,
        editNote,
        trashNote,
        restoreNote,
        deletePermanent,
        togglePin,
        toggleFavorite,
        changeColor,
        syncNow,
      }}
    >
      {children}
    </NotesContext.Provider>
  );
};

export const useNotes = () => {
  const context = useContext(NotesContext);
  if (!context) {
    throw new Error('useNotes must be used within a NotesProvider');
  }
  return context;
};
