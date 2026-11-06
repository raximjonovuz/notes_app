import React, { useState } from 'react';
import { getColorToken } from '../types/note';
import type { Note, NoteColorKey } from '../types/note';
import { useNotes } from '../context/NotesContext';
import { useTheme } from '../context/ThemeContext';
import { parseNoteContent } from '../services/richText';
import { ColorPickerMenu } from './ColorPickerMenu';
import { Pin, Star, Trash2, Undo2, Palette } from 'lucide-react';

interface NoteCardProps {
  note: Note;
  onEdit: (note: Note) => void;
}

export const NoteCard: React.FC<NoteCardProps> = ({ note, onEdit }) => {
  const { togglePin, toggleFavorite, trashNote, restoreNote, deletePermanent, changeColor } = useNotes();
  const { isDark } = useTheme();

  const [showColorPicker, setShowColorPicker] = useState(false);
  const colorToken = getColorToken(note.background_color_key);
  const isDefaultColor = !note.background_color_key || note.background_color_key === 'default';

  const cardBg = isDefaultColor
    ? isDark
      ? '#202124'
      : '#ffffff'
    : isDark
    ? colorToken.dark
    : colorToken.light;

  const cardBorder = isDefaultColor
    ? isDark
      ? '#5f6368'
      : '#e0e0e0'
    : isDark
    ? colorToken.borderDark
    : colorToken.borderLight;

  const parsed = parseNoteContent(note.content);
  const isTrashed = note.is_trashed === 1;

  return (
    <div
      onClick={() => !isTrashed && onEdit(note)}
      style={{
        backgroundColor: cardBg,
        borderColor: cardBorder,
      }}
      className={`group relative rounded-xl sm:rounded-lg border p-3 sm:p-4 transition-all duration-150 hover:shadow-[0_1px_2px_0_rgba(60,64,67,0.3),0_2px_6px_2px_rgba(60,64,67,0.15)] flex flex-col justify-between break-inside-avoid select-none mb-2 sm:mb-4 cursor-pointer text-[#202124] dark:text-[#e8eaed] ${
        isTrashed ? 'opacity-70 cursor-default' : ''
      }`}
    >
      {/* Top Header: Title & Pin/Favorite */}
      <div>
        <div className="flex items-start justify-between gap-1 sm:gap-2 mb-1.5 sm:mb-2">
          {note.title ? (
            <h3 className="font-medium text-sm sm:text-base text-[#202124] dark:text-[#e8eaed] leading-snug line-clamp-2">
              {note.title}
            </h3>
          ) : (
            <div />
          )}

          {!isTrashed && (
            <div className="flex items-center gap-0.5 opacity-70 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity shrink-0">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  toggleFavorite(note.id);
                }}
                title={note.is_favorite ? 'Sevimlilardan chiqarish' : 'Sevimli qilish'}
                className="w-7 h-7 sm:w-8 sm:h-8 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
              >
                <Star
                  className={`w-3.5 h-3.5 sm:w-4 sm:h-4 ${
                    note.is_favorite ? 'fill-amber-400 text-amber-500' : ''
                  }`}
                />
              </button>

              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  togglePin(note.id);
                }}
                title={note.isPinned ? 'Qadashni bekor qilish' : 'Tepaga qadash (Pin)'}
                className={`w-7 h-7 sm:w-8 sm:h-8 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center cursor-pointer ${
                  note.isPinned ? 'text-[#202124] dark:text-[#e8eaed]' : 'text-[#5f6368] dark:text-[#9aa0a6]'
                }`}
              >
                <Pin className={`w-3.5 h-3.5 sm:w-4 sm:h-4 ${note.isPinned ? 'fill-current' : ''}`} />
              </button>
            </div>
          )}
        </div>

        {/* Attached Images */}
        {parsed.images.length > 0 && (
          <div className="mb-2 sm:mb-3 rounded-md overflow-hidden border border-black/5 dark:border-white/5">
            <img
              src={parsed.images[0]}
              alt="attachment"
              className="w-full max-h-36 sm:max-h-48 object-cover"
              loading="lazy"
            />
          </div>
        )}

        {/* Note Body Text */}
        {parsed.displayText && (
          <p className="text-xs sm:text-sm text-[#202124] dark:text-[#e8eaed] whitespace-pre-wrap line-clamp-5 sm:line-clamp-8 leading-relaxed mb-2 sm:mb-3">
            {parsed.displayText}
          </p>
        )}

        {/* Checklists preview */}
        {parsed.checklists.length > 0 && (
          <div className="flex flex-col gap-1.5 my-2">
            {parsed.checklists.slice(0, 6).map((chk) => (
              <div key={chk.id} className="flex items-center gap-2 text-xs text-[#202124] dark:text-[#e8eaed]">
                <input
                  type="checkbox"
                  checked={chk.checked}
                  readOnly
                  className="w-3.5 h-3.5 text-[#5f6368] accent-[#5f6368] rounded pointer-events-none"
                />
                <span className={`truncate ${chk.checked ? 'line-through text-[#80868b] dark:text-[#9aa0a6]' : ''}`}>
                  {chk.text}
                </span>
              </div>
            ))}
            {parsed.checklists.length > 6 && (
              <span className="text-[11px] text-[#80868b] dark:text-[#9aa0a6] italic">
                +{parsed.checklists.length - 6} ta boshqa element...
              </span>
            )}
          </div>
        )}
      </div>

      {/* Card Action Footer */}
      <div className="flex items-center justify-between pt-1 sm:pt-2 mt-1 sm:mt-2 opacity-70 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity">
        {!isTrashed ? (
          <div className="flex items-center gap-1 relative">
            {/* Color Palette */}
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setShowColorPicker(!showColorPicker);
              }}
              title="Rangni o'zgartirish"
              className="w-7 h-7 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
            >
              <Palette className="w-3.5 h-3.5" />
            </button>
            {showColorPicker && (
              <div className="absolute bottom-8 left-0 z-50">
                <ColorPickerMenu
                  currentColorKey={note.background_color_key}
                  onSelectColor={(k: NoteColorKey) => {
                    changeColor(note.id, k);
                    setShowColorPicker(false);
                  }}
                />
              </div>
            )}

            {/* Trash button */}
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                trashNote(note.id);
              }}
              title="Chiqindiga tashlash"
              className="w-7 h-7 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                restoreNote(note.id);
              }}
              title="Qayta tiklash"
              className="px-2 py-1 rounded hover:bg-black/5 dark:hover:bg-white/10 text-green-600 dark:text-green-400 flex items-center gap-1 text-xs cursor-pointer font-medium"
            >
              <Undo2 className="w-3.5 h-3.5" />
              <span>Tiklash</span>
            </button>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                if (confirm("Ushbu eslatma butunlay o'chirilsinmi?")) {
                  deletePermanent(note.id);
                }
              }}
              title="Butunlay o'chirish"
              className="px-2 py-1 rounded hover:bg-black/5 dark:hover:bg-white/10 text-red-600 dark:text-red-400 flex items-center gap-1 text-xs cursor-pointer font-medium"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>O'chirish</span>
            </button>
          </div>
        )}

        <span className="text-[10px] text-[#80868b] dark:text-[#9aa0a6] font-normal">
          {new Date(note.updated_at || note.created_at).toLocaleDateString([], {
            month: 'short',
            day: 'numeric',
          })}
        </span>
      </div>
    </div>
  );
};
