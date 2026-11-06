import { useState } from 'react';
import { AuthProvider, useAuth } from './context/AuthContext';
import { NotesProvider, useNotes } from './context/NotesContext';
import { ThemeProvider } from './context/ThemeContext';
import { Header } from './components/Header';
import { Sidebar } from './components/Sidebar';
import { CreateNoteBar } from './components/CreateNoteBar';
import { NoteCard } from './components/NoteCard';
import { NoteEditModal } from './components/NoteEditModal';
import { LoginModal } from './components/LoginModal';
import { SettingsModal } from './components/SettingsModal';
import { AuthEntryScreen } from './components/AuthEntryScreen';
import type { Note } from './types/note';
import { Lightbulb, Star, Trash2 } from 'lucide-react';

function NotesDashboard() {
  const { openLoginModal } = useAuth();
  const { filteredNotes, pinnedNotes, otherNotes, activeFilter, viewMode, syncError } = useNotes();

  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [editingNote, setEditingNote] = useState<Note | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  const gridClass =
    viewMode === 'grid'
      ? 'columns-1 sm:columns-2 md:columns-3 lg:columns-4 xl:columns-5 2xl:columns-6 gap-4'
      : 'flex flex-col gap-3 max-w-[600px] mx-auto';

  return (
    <div className="min-h-screen bg-white dark:bg-[#202124] text-[#202124] dark:text-[#e8eaed] flex flex-col transition-colors">
      <Header
        onToggleSidebar={() => setSidebarOpen(!sidebarOpen)}
        onOpenLogin={openLoginModal}
        onOpenSettings={() => setIsSettingsOpen(true)}
      />

      <div className="flex-1 flex w-full">
        <Sidebar
          isOpen={sidebarOpen}
          onCloseMobile={() => setSidebarOpen(false)}
        />

        <main className="flex-1 px-4 sm:px-8 py-2 overflow-x-hidden flex flex-col items-center">
          {/* Create Note Bar (Google Keep style) */}
          {activeFilter === 'all' && <CreateNoteBar />}

          {/* Sync Error Notice if any */}
          {syncError && (
            <div className="w-full max-w-[600px] mb-4 p-2.5 rounded-lg bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-900 text-red-700 dark:text-red-300 text-xs text-center">
              {syncError}
            </div>
          )}

          {/* Trash banner (compact) */}
          {activeFilter === 'trash' && (
            <div className="w-full max-w-[600px] mb-4 py-2 px-4 rounded-lg bg-gray-100 dark:bg-[#303134] text-center text-xs text-[#5f6368] dark:text-[#9aa0a6] select-none">
              Chiqindilar qutisidagi qaydlar qayta tiklanishi yoki butunlay o'chirilishi mumkin.
            </div>
          )}

          {/* Notes Content */}
          <div className="w-full max-w-[1500px] mt-2">
            {filteredNotes.length === 0 ? (
              // Empty State - Google Keep 1:1 style
              <div className="py-28 flex flex-col items-center justify-center text-[#80868b] dark:text-[#9aa0a6] select-none text-center">
                <div className="mb-5 text-[#dadce0] dark:text-[#5f6368]">
                  {activeFilter === 'favorites' ? (
                    <Star className="w-24 h-24 stroke-[1]" />
                  ) : activeFilter === 'trash' ? (
                    <Trash2 className="w-24 h-24 stroke-[1]" />
                  ) : (
                    <Lightbulb className="w-24 h-24 stroke-[1]" />
                  )}
                </div>
                <p className="text-base font-normal tracking-wide text-[#5f6368] dark:text-[#9aa0a6]">
                  {activeFilter === 'favorites'
                    ? "Sevimli qaydlar yo'q"
                    : activeFilter === 'trash'
                    ? "Chiqindilar qutisi bo'sh"
                    : 'Bu yerda qaydlaringiz ko\'rinadi'}
                </p>
              </div>
            ) : (
              <div className="flex flex-col gap-6">
                {/* Pinned notes section */}
                {activeFilter === 'all' && pinnedNotes.length > 0 && (
                  <div>
                    <h4 className="text-[11px] font-medium uppercase tracking-wider text-[#5f6368] dark:text-[#9aa0a6] mb-2 px-3 select-none">
                      QADALGAN
                    </h4>
                    <div className={gridClass}>
                      {pinnedNotes.map((note) => (
                        <NoteCard
                          key={note.id}
                          note={note}
                          onEdit={(n) => setEditingNote(n)}
                        />
                      ))}
                    </div>
                  </div>
                )}

                {/* Other notes section */}
                <div>
                  {activeFilter === 'all' && pinnedNotes.length > 0 && (
                    <h4 className="text-[11px] font-medium uppercase tracking-wider text-[#5f6368] dark:text-[#9aa0a6] mb-2 px-3 select-none">
                      BOSHQALAR
                    </h4>
                  )}
                  <div className={gridClass}>
                    {(activeFilter === 'all' ? otherNotes : filteredNotes).map((note) => (
                      <NoteCard
                        key={note.id}
                        note={note}
                        onEdit={(n) => setEditingNote(n)}
                      />
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </main>
      </div>

      {/* Edit Note Modal */}
      <NoteEditModal
        note={editingNote}
        onClose={() => setEditingNote(null)}
      />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
      />

      {/* Login Modal */}
      <LoginModal />
    </div>
  );
}

function MainRoot() {
  const { isSplashPhase, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen w-full bg-white dark:bg-[#202124] flex items-center justify-center">
        <div className="w-8 h-8 rounded-full border-2 border-[#2D6A4F] border-t-transparent animate-spin" />
      </div>
    );
  }

  if (isSplashPhase) {
    return <AuthEntryScreen />;
  }

  return <NotesDashboard />;
}

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <NotesProvider>
          <MainRoot />
        </NotesProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
