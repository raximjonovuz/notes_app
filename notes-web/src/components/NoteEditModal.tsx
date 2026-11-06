import React, { useState, useRef } from 'react';
import { getColorToken } from '../types/note';
import type { Note, NoteColorKey } from '../types/note';
import { useNotes } from '../context/NotesContext';
import { useTheme } from '../context/ThemeContext';
import { parseNoteContent, buildV3Content } from '../services/richText';
import type { ChecklistItem } from '../services/richText';
import { ColorPickerMenu } from './ColorPickerMenu';
import { api } from '../services/api';
import { Pin, Star, Trash2, Palette, CheckSquare, Image as ImageIcon, X } from 'lucide-react';

interface NoteEditModalProps {
  note: Note | null;
  onClose: () => void;
}

export const NoteEditModal: React.FC<NoteEditModalProps> = ({ note, onClose }) => {
  const { editNote, trashNote, togglePin, toggleFavorite } = useNotes();
  const { isDark } = useTheme();

  if (!note) return null;

  const parsed = parseNoteContent(note.content);

  const [title, setTitle] = useState(note.title);
  const [content, setContent] = useState(parsed.displayText);
  const [checklists, setChecklists] = useState<ChecklistItem[]>(parsed.checklists);
  const [newChecklistText, setNewChecklistText] = useState('');
  const [isChecklistMode, setIsChecklistMode] = useState(parsed.checklists.length > 0);
  const [colorKey, setColorKey] = useState<NoteColorKey>(
    (note.background_color_key as NoteColorKey) || 'default'
  );
  const [showColorPicker, setShowColorPicker] = useState(false);
  const [images, setImages] = useState<string[]>(parsed.images);
  const [isUploading, setIsUploading] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleSaveAndClose = async () => {
    let finalContent = content;
    if (isChecklistMode || images.length > 0) {
      finalContent = buildV3Content(content, checklists, images);
    }

    await editNote(note.id, {
      title: title.trim(),
      content: finalContent,
      background_color_key: colorKey,
      colorKey,
    });
    onClose();
  };

  const handleToggleChecklist = (index: number) => {
    setChecklists((prev) =>
      prev.map((item, idx) => (idx === index ? { ...item, checked: !item.checked } : item))
    );
  };

  const handleRemoveChecklist = (index: number) => {
    setChecklists((prev) => prev.filter((_, idx) => idx !== index));
  };

  const handleAddChecklist = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!newChecklistText.trim()) return;
    setChecklists((prev) => [
      ...prev,
      { id: `chk_${Date.now()}`, text: newChecklistText.trim(), checked: false },
    ]);
    setNewChecklistText('');
  };

  const handleImageUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setIsUploading(true);
    try {
      const res = await api.uploadFile(file);
      setImages((prev) => [...prev, res.url]);
    } catch (err: any) {
      alert(`Rasm yuklashda xatolik: ${err.message}`);
    } finally {
      setIsUploading(false);
    }
  };

  const colorToken = getColorToken(colorKey);
  const isDefaultColor = !colorKey || colorKey === 'default';

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

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-0 sm:p-4 bg-black/60 backdrop-blur-2xs select-none animate-in fade-in duration-150">
      <div className="fixed inset-0" onClick={handleSaveAndClose} />

      <div
        style={{
          backgroundColor: cardBg,
          borderColor: cardBorder,
        }}
        className="relative z-10 w-full sm:max-w-2xl h-full sm:h-auto sm:max-h-[90vh] sm:rounded-lg border shadow-2xl flex flex-col overflow-hidden animate-in zoom-in-95 duration-150 text-[#202124] dark:text-[#e8eaed]"
      >
        {/* Header: Title & Pin/Favorite */}
        <div className="p-4 sm:p-5 pb-2 flex items-center justify-between gap-3">
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Sarlavha"
            className="w-full font-medium text-base sm:text-lg bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6]"
          />

          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => toggleFavorite(note.id)}
              title={note.is_favorite ? 'Sevimlilardan chiqarish' : 'Sevimli qilish'}
              className="w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
            >
              <Star
                className={`w-5 h-5 ${note.is_favorite ? 'fill-amber-400 text-amber-500' : ''}`}
              />
            </button>

            <button
              type="button"
              onClick={() => togglePin(note.id)}
              title={note.isPinned ? 'Qadashni bekor qilish' : 'Tepaga qadash (Pin)'}
              className={`w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center cursor-pointer ${
                note.isPinned ? 'text-[#202124] dark:text-[#e8eaed]' : 'text-[#5f6368] dark:text-[#9aa0a6]'
              }`}
            >
              <Pin className={`w-5 h-5 ${note.isPinned ? 'fill-current' : ''}`} />
            </button>
          </div>
        </div>

        {/* Scrollable Body */}
        <div className="flex-1 overflow-y-auto px-4 sm:px-5 py-2 flex flex-col gap-3">
          {/* Images */}
          {images.length > 0 && (
            <div className="flex flex-wrap gap-2 py-1">
              {images.map((img, idx) => (
                <div key={idx} className="relative group w-32 h-32 rounded-lg overflow-hidden border border-black/10 dark:border-white/10">
                  <img src={img} alt="attachment" className="w-full h-full object-cover" />
                  <button
                    type="button"
                    onClick={() => setImages((prev) => prev.filter((_, i) => i !== idx))}
                    className="absolute top-1 right-1 p-1 bg-black/60 rounded-full text-white opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Checklists or Text */}
          {isChecklistMode ? (
            <div className="flex flex-col gap-2.5 my-1">
              {checklists.map((chk, idx) => (
                <div key={chk.id} className="flex items-center gap-2.5 text-sm sm:text-base">
                  <input
                    type="checkbox"
                    checked={chk.checked}
                    onChange={() => handleToggleChecklist(idx)}
                    className="w-4 h-4 text-[#5f6368] accent-[#5f6368] rounded focus:ring-0 cursor-pointer"
                  />
                  <input
                    type="text"
                    value={chk.text}
                    onChange={(e) => {
                      const val = e.target.value;
                      setChecklists((prev) =>
                        prev.map((item, i) => (i === idx ? { ...item, text: val } : item))
                      );
                    }}
                    className={`flex-1 bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] ${
                      chk.checked ? 'line-through text-[#80868b] dark:text-[#9aa0a6]' : ''
                    }`}
                  />
                  <button
                    type="button"
                    onClick={() => handleRemoveChecklist(idx)}
                    className="text-[#5f6368] hover:text-[#202124] dark:hover:text-white cursor-pointer"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              ))}

              <form onSubmit={handleAddChecklist} className="flex items-center gap-2.5 mt-2">
                <input
                  type="checkbox"
                  disabled
                  className="w-4 h-4 text-gray-300 rounded opacity-40"
                />
                <input
                  type="text"
                  value={newChecklistText}
                  onChange={(e) => setNewChecklistText(e.target.value)}
                  placeholder="Ro'yxat elementi qo'shish..."
                  className="flex-1 text-sm sm:text-base bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6]"
                />
              </form>
            </div>
          ) : (
            <textarea
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="Qayd yozish..."
              rows={8}
              className="w-full text-sm sm:text-base bg-transparent border-none outline-none resize-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] leading-relaxed min-h-[160px]"
            />
          )}

          {/* Hidden file input */}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={handleImageUpload}
          />
        </div>

        {/* Footer Toolbar */}
        <div className="p-3 sm:p-4 flex items-center justify-between relative">
          <div className="flex items-center gap-0.5 sm:gap-1">
            {/* Color picker */}
            <div className="relative">
              <button
                type="button"
                onClick={() => setShowColorPicker(!showColorPicker)}
                title="Rang tanlash"
                className="w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
              >
                <Palette className="w-5 h-5" />
              </button>
              {showColorPicker && (
                <div className="absolute bottom-12 left-0 z-50">
                  <ColorPickerMenu
                    currentColorKey={colorKey}
                    onSelectColor={(k) => {
                      setColorKey(k);
                      setShowColorPicker(false);
                    }}
                  />
                </div>
              )}
            </div>

            {/* Checklist Toggle */}
            <button
              type="button"
              onClick={() => setIsChecklistMode(!isChecklistMode)}
              title={isChecklistMode ? 'Oddiy matn rejimi' : "Ro'yxat rejimi"}
              className={`w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center cursor-pointer ${
                isChecklistMode ? 'text-[#202124] dark:text-white font-bold' : 'text-[#5f6368] dark:text-[#9aa0a6]'
              }`}
            >
              <CheckSquare className="w-5 h-5" />
            </button>

            {/* Image upload */}
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={isUploading}
              title="Rasm qo'shish"
              className="w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
            >
              <ImageIcon className="w-5 h-5" />
            </button>

            {/* Trash button */}
            <button
              type="button"
              onClick={() => {
                trashNote(note.id);
                onClose();
              }}
              title="Chiqindiga tashlash"
              className="w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center cursor-pointer"
            >
              <Trash2 className="w-5 h-5" />
            </button>
          </div>

          <button
            type="button"
            onClick={handleSaveAndClose}
            className="px-6 py-2 font-medium text-sm text-[#202124] dark:text-[#e8eaed] hover:bg-black/5 dark:hover:bg-white/10 rounded transition-colors cursor-pointer"
          >
            Yopish
          </button>
        </div>
      </div>
    </div>
  );
};
