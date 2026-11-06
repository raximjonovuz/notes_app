import React from 'react';
import { StickyNote, Star, Trash2 } from 'lucide-react';
import { useNotes } from '../context/NotesContext';
import type { NotesFilter } from '../context/NotesContext';

interface SidebarProps {
  isOpen: boolean;
  onCloseMobile?: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ isOpen, onCloseMobile }) => {
  const { activeFilter, setActiveFilter, notes } = useNotes();

  const activeNotesCount = notes.filter((n) => n.is_trashed !== 1).length;
  const favNotesCount = notes.filter((n) => n.is_trashed !== 1 && n.is_favorite === 1).length;
  const trashCount = notes.filter((n) => n.is_trashed === 1).length;

  const navItems: Array<{ id: NotesFilter; label: string; icon: any; count: number }> = [
    { id: 'all', label: 'Qaydlar', icon: StickyNote, count: activeNotesCount },
    { id: 'favorites', label: 'Sevimlilar', icon: Star, count: favNotesCount },
    { id: 'trash', label: 'Chiqindilar qutisi', icon: Trash2, count: trashCount },
  ];

  return (
    <>
      {/* Mobile backdrop */}
      {isOpen && (
        <div
          className="fixed inset-0 bg-black/40 z-30 md:hidden backdrop-blur-xs transition-opacity"
          onClick={onCloseMobile}
        />
      )}

      {/* Sidebar container - completely borderless like Google Keep */}
      <aside
        className={`fixed md:sticky top-16 left-0 z-30 h-[calc(100vh-4rem)] bg-white dark:bg-[#202124] transition-all duration-200 ease-in-out flex flex-col justify-between py-2 select-none shrink-0 ${
          isOpen ? 'w-[280px]' : 'w-0 md:w-[72px] overflow-hidden'
        }`}
      >
        <div className="flex flex-col gap-1 w-full">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = activeFilter === item.id;

            return (
              <button
                key={item.id}
                onClick={() => {
                  setActiveFilter(item.id);
                  if (onCloseMobile) onCloseMobile();
                }}
                className={`flex items-center h-12 transition-colors cursor-pointer ${
                  isOpen
                    ? `rounded-r-full pr-6 pl-6 gap-6 ${
                        isActive
                          ? 'bg-[#D8F3DC] dark:bg-[#1B4332] text-[#1B4332] dark:text-[#D8F3DC] font-semibold'
                          : 'text-[#202124] dark:text-[#e8eaed] hover:bg-gray-100 dark:hover:bg-[#28292c]'
                      }`
                    : `justify-center ${
                        isActive
                          ? 'text-[#1B4332] dark:text-[#74C69D]'
                          : 'text-[#5f6368] dark:text-[#9aa0a6] hover:bg-gray-100 dark:hover:bg-[#28292c]'
                      }`
                }`}
                title={item.label}
              >
                <div
                  className={`flex items-center justify-center shrink-0 ${
                    !isOpen && isActive
                      ? 'w-12 h-12 rounded-full bg-[#D8F3DC] dark:bg-[#1B4332]'
                      : 'w-6 h-6'
                  }`}
                >
                  <Icon
                    className={`w-5 h-5 ${
                      isActive
                        ? 'text-[#1B4332] dark:text-[#74C69D]'
                        : 'text-[#5f6368] dark:text-[#9aa0a6]'
                    }`}
                  />
                </div>

                {isOpen && (
                  <div className="flex-1 flex items-center justify-between overflow-hidden">
                    <span className="text-sm tracking-normal truncate">{item.label}</span>
                    {item.count > 0 && (
                      <span className={`text-xs px-2 py-0.5 rounded-full ${
                        isActive
                          ? 'bg-[#1B4332]/10 dark:bg-white/20 text-[#1B4332] dark:text-[#D8F3DC] font-medium'
                          : 'bg-black/5 dark:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] font-normal'
                      }`}>
                        {item.count}
                      </span>
                    )}
                  </div>
                )}
              </button>
            );
          })}
        </div>

        {/* Footer info in sidebar */}
        {isOpen && (
          <div className="px-6 py-4 text-[11px] text-[#80868b] dark:text-[#9aa0a6]">
            <p>Ochiq kodli litsenziyalar</p>
          </div>
        )}
      </aside>
    </>
  );
};
