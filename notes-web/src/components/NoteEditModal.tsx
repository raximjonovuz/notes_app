import React, { useState, useRef, useEffect } from 'react';
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
  const [pendingFocus, setPendingFocus] = useState<{ index: number; cursorPosition: number } | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const titleRef = useRef<HTMLInputElement>(null);
  const itemRefs = useRef<(HTMLInputElement | null)[]>([]);
  const newItemInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Manage focus transitions after checklist state updates
  useEffect(() => {
    if (pendingFocus) {
      const el = itemRefs.current[pendingFocus.index];
      if (el) {
        el.focus();
        el.setSelectionRange(pendingFocus.cursorPosition, pendingFocus.cursorPosition);
      }
      setPendingFocus(null);
    }
  }, [checklists, pendingFocus]);

  const handleSaveAndClose = async () => {
    let finalChecklists = [...checklists];
    if (newChecklistText.trim()) {
      finalChecklists.push({
        id: `chk_${Date.now()}`,
        text: newChecklistText.trim(),
        checked: false,
      });
    }
    // Clean up empty checklist items
    finalChecklists = finalChecklists.filter((item) => item.text.trim().length > 0);

    let finalContent = content;
    if (isChecklistMode || images.length > 0) {
      finalContent = buildV3Content(content, finalChecklists, images);
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
      { id: `chk_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`, text: newChecklistText.trim(), checked: false },
    ]);
    setNewChecklistText('');
  };

  // Keyboard navigation for existing checklist items
  const handleItemKeyDown = (
    e: React.KeyboardEvent<HTMLInputElement>,
    idx: number
  ) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      const input = e.currentTarget;
      const start = input.selectionStart ?? input.value.length;
      const end = input.selectionEnd ?? input.value.length;

      const currentText = checklists[idx].text;
      const leftText = currentText.slice(0, start);
      const rightText = currentText.slice(end);

      const newItem: ChecklistItem = {
        id: `chk_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
        text: rightText,
        checked: false,
      };

      const nextChecklists = [...checklists];
      nextChecklists[idx] = { ...nextChecklists[idx], text: leftText };
      nextChecklists.splice(idx + 1, 0, newItem);

      setChecklists(nextChecklists);
      setPendingFocus({ index: idx + 1, cursorPosition: 0 });
    } else if (e.key === 'Backspace') {
      const input = e.currentTarget;
      const start = input.selectionStart ?? 0;
      const end = input.selectionEnd ?? 0;

      if (!checklists[idx].text) {
        // Current item is completely empty
        e.preventDefault();
        const nextChecklists = checklists.filter((_, i) => i !== idx);
        setChecklists(nextChecklists);

        if (idx > 0) {
          const prevLen = checklists[idx - 1].text.length;
          setPendingFocus({ index: idx - 1, cursorPosition: prevLen });
        } else if (nextChecklists.length > 0) {
          setPendingFocus({ index: 0, cursorPosition: 0 });
        } else {
          titleRef.current?.focus();
        }
      } else if (start === 0 && end === 0 && idx > 0) {
        // Cursor at very start of non-empty item: merge into previous item
        e.preventDefault();
        const prevText = checklists[idx - 1].text;
        const prevLen = prevText.length;
        const mergedText = prevText + checklists[idx].text;

        const nextChecklists = [...checklists];
        nextChecklists[idx - 1] = { ...nextChecklists[idx - 1], text: mergedText };
        nextChecklists.splice(idx, 1);

        setChecklists(nextChecklists);
        setPendingFocus({ index: idx - 1, cursorPosition: prevLen });
      }
    } else if (e.key === 'ArrowDown') {
      const input = e.currentTarget;
      const pos = input.selectionStart ?? 0;
      if (pos === input.value.length || e.ctrlKey || e.altKey) {
        if (idx < checklists.length - 1) {
          e.preventDefault();
          itemRefs.current[idx + 1]?.focus();
        } else if (newItemInputRef.current) {
          e.preventDefault();
          newItemInputRef.current.focus();
        }
      }
    } else if (e.key === 'ArrowUp') {
      const input = e.currentTarget;
      const pos = input.selectionStart ?? 0;
      if (pos === 0 || e.ctrlKey || e.altKey) {
        if (idx > 0) {
          e.preventDefault();
          itemRefs.current[idx - 1]?.focus();
        } else if (titleRef.current) {
          e.preventDefault();
          titleRef.current.focus();
        }
      }
    }
  };

  // Keyboard navigation for the "Add checklist item" field
  const handleNewChecklistKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (newChecklistText.trim()) {
        const newItem: ChecklistItem = {
          id: `chk_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
          text: newChecklistText.trim(),
          checked: false,
        };
        setChecklists((prev) => [...prev, newItem]);
        setNewChecklistText('');
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (checklists.length > 0) {
        itemRefs.current[checklists.length - 1]?.focus();
      } else {
        titleRef.current?.focus();
      }
    } else if (e.key === 'Backspace' && !newChecklistText) {
      if (checklists.length > 0) {
        e.preventDefault();
        const lastIdx = checklists.length - 1;
        const lastLen = checklists[lastIdx].text.length;
        setPendingFocus({ index: lastIdx, cursorPosition: lastLen });
      }
    }
  };

  // Title keyboard navigation
  const handleTitleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === 'ArrowDown') {
      e.preventDefault();
      if (isChecklistMode) {
        if (checklists.length > 0) {
          itemRefs.current[0]?.focus();
        } else {
          newItemInputRef.current?.focus();
        }
      } else {
        textareaRef.current?.focus();
      }
    }
  };

  // Textarea keyboard navigation
  const handleTextareaKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'ArrowUp' && e.currentTarget.selectionStart === 0 && e.currentTarget.selectionEnd === 0) {
      e.preventDefault();
      titleRef.current?.focus();
    }
  };

  // Toggle between Checklist Mode and Plain Text Mode safely without losing data
  const handleToggleChecklistMode = () => {
    if (isChecklistMode) {
      // Checklist -> Plain text
      const listLines = checklists.map((c) => c.text);
      const combined = [content, ...listLines].filter((s) => s.trim().length > 0).join('\n');
      setContent(combined);
      setChecklists([]);
      setIsChecklistMode(false);
    } else {
      // Plain text -> Checklist
      const lines = content.split('\n').filter((l) => l.trim().length > 0);
      if (lines.length > 0) {
        const newItems: ChecklistItem[] = lines.map((line, idx) => ({
          id: `chk_${Date.now()}_${idx}`,
          text: line.replace(/^[-*•]\s?(\[[ xX]\]\s?)?/, '').replace(/^\d+[\.\)]\s?/, ''),
          checked: false,
        }));
        setChecklists(newItems);
      }
      setContent('');
      setIsChecklistMode(true);
    }
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
            ref={titleRef}
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={handleTitleKeyDown}
            placeholder="Sarlavha"
            className="w-full font-medium text-base sm:text-lg bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] select-text"
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
                <div key={chk.id} className="flex items-center gap-2.5 text-sm sm:text-base group">
                  <input
                    type="checkbox"
                    checked={chk.checked}
                    onChange={() => handleToggleChecklist(idx)}
                    className="w-4 h-4 text-[#5f6368] accent-[#5f6368] rounded focus:ring-0 cursor-pointer shrink-0"
                  />
                  <input
                    ref={(el) => {
                      itemRefs.current[idx] = el;
                    }}
                    type="text"
                    value={chk.text}
                    onChange={(e) => {
                      const val = e.target.value;
                      setChecklists((prev) =>
                        prev.map((item, i) => (i === idx ? { ...item, text: val } : item))
                      );
                    }}
                    onKeyDown={(e) => handleItemKeyDown(e, idx)}
                    className={`flex-1 bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] select-text ${
                      chk.checked ? 'line-through text-[#80868b] dark:text-[#9aa0a6]' : ''
                    }`}
                  />
                  <button
                    type="button"
                    onClick={() => handleRemoveChecklist(idx)}
                    className="text-[#5f6368] hover:text-[#202124] dark:hover:text-white cursor-pointer opacity-70 group-hover:opacity-100 transition-opacity p-1"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              ))}

              <form onSubmit={handleAddChecklist} className="flex items-center gap-2.5 mt-2">
                <input
                  type="checkbox"
                  disabled
                  className="w-4 h-4 text-gray-300 rounded opacity-40 shrink-0"
                />
                <input
                  ref={newItemInputRef}
                  type="text"
                  value={newChecklistText}
                  onChange={(e) => setNewChecklistText(e.target.value)}
                  onKeyDown={handleNewChecklistKeyDown}
                  placeholder="Ro'yxat elementi qo'shish..."
                  className="flex-1 text-sm sm:text-base bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] select-text"
                />
              </form>
            </div>
          ) : (
            <textarea
              ref={textareaRef}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              onKeyDown={handleTextareaKeyDown}
              placeholder="Qayd yozish..."
              rows={8}
              className="w-full text-sm sm:text-base bg-transparent border-none outline-none resize-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] leading-relaxed min-h-[160px] select-text"
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
              onClick={handleToggleChecklistMode}
              title={isChecklistMode ? 'Oddiy matn rejimi' : "Ro'yxat rejimi"}
              className={`w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center cursor-pointer ${
                isChecklistMode ? 'text-[#202124] dark:text-white font-bold bg-black/5 dark:bg-white/10' : 'text-[#5f6368] dark:text-[#9aa0a6]'
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
