import React, { useState, useRef, useEffect } from 'react';
import { CheckSquare, Palette, Pin, Image as ImageIcon, X, PenTool } from 'lucide-react';
import { useNotes } from '../context/NotesContext';
import { useTheme } from '../context/ThemeContext';
import { getColorToken } from '../types/note';
import type { NoteColorKey } from '../types/note';
import { ColorPickerMenu } from './ColorPickerMenu';
import { buildV3Content } from '../services/richText';
import type { ChecklistItem } from '../services/richText';
import { api } from '../services/api';

export const CreateNoteBar: React.FC = () => {
  const { addNote } = useNotes();
  const { isDark } = useTheme();

  const [isExpanded, setIsExpanded] = useState(false);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [isChecklistMode, setIsChecklistMode] = useState(false);
  const [checklists, setChecklists] = useState<ChecklistItem[]>([]);
  const [newChecklistText, setNewChecklistText] = useState('');
  const [isPinned, setIsPinned] = useState(false);
  const [colorKey, setColorKey] = useState<NoteColorKey>('default');
  const [showColorPicker, setShowColorPicker] = useState(false);
  const [images, setImages] = useState<string[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const [pendingFocus, setPendingFocus] = useState<{ index: number; cursorPosition: number } | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);
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

  // Close and save when clicked outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        if (isExpanded) {
          saveAndClose();
        }
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isExpanded, title, content, checklists, newChecklistText, colorKey, isPinned, images, isChecklistMode]);

  const saveAndClose = async () => {
    let finalChecklists = [...checklists];
    if (newChecklistText.trim()) {
      finalChecklists.push({
        id: `chk_${Date.now()}`,
        text: newChecklistText.trim(),
        checked: false,
      });
    }
    finalChecklists = finalChecklists.filter((item) => item.text.trim().length > 0);

    const hasContent = title.trim() || content.trim() || finalChecklists.length > 0 || images.length > 0;
    if (hasContent) {
      let finalContent = content;
      if (isChecklistMode || images.length > 0) {
        finalContent = buildV3Content(content, finalChecklists, images);
      }
      await addNote({
        title: title.trim(),
        content: finalContent,
        colorKey,
        isPinned,
      });
    }

    // Reset state
    setTitle('');
    setContent('');
    setChecklists([]);
    setNewChecklistText('');
    setIsChecklistMode(false);
    setIsPinned(false);
    setColorKey('default');
    setImages([]);
    setShowColorPicker(false);
    setIsExpanded(false);
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

  const handleToggleChecklist = (index: number) => {
    setChecklists((prev) =>
      prev.map((item, idx) => (idx === index ? { ...item, checked: !item.checked } : item))
    );
  };

  const handleRemoveChecklist = (index: number) => {
    setChecklists((prev) => prev.filter((_, idx) => idx !== index));
  };

  // Keyboard navigation for checklist items
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

  const handleTextareaKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'ArrowUp' && e.currentTarget.selectionStart === 0 && e.currentTarget.selectionEnd === 0) {
      e.preventDefault();
      titleRef.current?.focus();
    }
  };

  const handleToggleChecklistMode = () => {
    if (isChecklistMode) {
      const listLines = checklists.map((c) => c.text);
      const combined = [content, ...listLines].filter((s) => s.trim().length > 0).join('\n');
      setContent(combined);
      setChecklists([]);
      setIsChecklistMode(false);
    } else {
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
  const cardBg = isDark ? colorToken.dark : colorToken.light;
  const cardBorder = isDark ? colorToken.borderDark : colorToken.borderLight;

  return (
    <div className="w-full max-w-[600px] mx-auto mt-2 sm:mt-6 mb-3 sm:mb-8 px-1 sm:px-4 select-none">
      <div
        ref={containerRef}
        style={{
          backgroundColor: isExpanded && colorKey !== 'default' ? cardBg : undefined,
          borderColor: isExpanded && colorKey !== 'default' ? cardBorder : undefined,
        }}
        className={`rounded-lg transition-all duration-200 border ${
          !isExpanded
            ? 'h-[42px] sm:h-[46px] bg-white dark:bg-[#202124] border-gray-200/90 dark:border-[#5f6368] shadow-[0_1px_2px_0_rgba(60,64,67,0.3),0_2px_6px_2px_rgba(60,64,67,0.15)] dark:shadow-none cursor-text'
            : 'bg-white dark:bg-[#202124] border-gray-200/90 dark:border-[#5f6368] shadow-[0_1px_3px_0_rgba(60,64,67,0.3),0_4px_8px_3px_rgba(60,64,67,0.15)] dark:shadow-none'
        }`}
      >
        {!isExpanded ? (
          // Collapsed Bar (Keep style 1:1)
          <div
            onClick={() => setIsExpanded(true)}
            className="h-full flex items-center justify-between px-3 sm:px-4 text-[#5f6368] dark:text-[#9aa0a6]"
          >
            <span className="font-normal text-xs sm:text-base">Qayd yozish...</span>
            <div className="flex items-center gap-0.5" onClick={(e) => e.stopPropagation()}>
              <button
                type="button"
                onClick={() => {
                  setIsExpanded(true);
                  setIsChecklistMode(true);
                }}
                title="Yangi ro'yxat"
                className="w-8 h-8 sm:w-10 sm:h-10 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center transition-colors cursor-pointer"
              >
                <CheckSquare className="w-4 h-4 sm:w-5 sm:h-5" />
              </button>
              <button
                type="button"
                onClick={() => {
                  setIsExpanded(true);
                }}
                title="Rasm chizish"
                className="w-8 h-8 sm:w-10 sm:h-10 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] hidden sm:flex items-center justify-center transition-colors cursor-pointer"
              >
                <PenTool className="w-4 h-4 sm:w-5 sm:h-5" />
              </button>
              <button
                type="button"
                onClick={() => {
                  setIsExpanded(true);
                  fileInputRef.current?.click();
                }}
                title="Rasm qo'shish"
                className="w-8 h-8 sm:w-10 sm:h-10 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center transition-colors cursor-pointer"
              >
                <ImageIcon className="w-4 h-4 sm:w-5 sm:h-5" />
              </button>
            </div>
          </div>
        ) : (
          // Expanded Form
          <div className="p-4 flex flex-col gap-3">
            {/* Title & Pin */}
            <div className="flex items-center justify-between gap-2">
              <input
                ref={titleRef}
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                onKeyDown={handleTitleKeyDown}
                placeholder="Sarlavha"
                className="w-full font-medium text-base text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] bg-transparent border-none outline-none select-text"
                autoFocus={!isChecklistMode}
              />
              <button
                type="button"
                onClick={() => setIsPinned(!isPinned)}
                title={isPinned ? 'Qadashni bekor qilish' : 'Tepaga qadash (Pin)'}
                className={`w-9 h-9 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center transition-colors cursor-pointer ${
                  isPinned ? 'text-[#202124] dark:text-[#e8eaed]' : 'text-[#5f6368] dark:text-[#9aa0a6]'
                }`}
              >
                <Pin className={`w-5 h-5 ${isPinned ? 'fill-current' : ''}`} />
              </button>
            </div>

            {/* Images preview */}
            {images.length > 0 && (
              <div className="flex gap-2 overflow-x-auto py-2">
                {images.map((img, idx) => (
                  <div key={idx} className="relative group w-24 h-24 rounded-lg overflow-hidden border border-black/10 dark:border-white/10">
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

            {/* Body or Checklists */}
            {isChecklistMode ? (
              <div className="flex flex-col gap-2 my-1">
                {checklists.map((chk, idx) => (
                  <div key={chk.id} className="flex items-center gap-2 text-sm group">
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
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}

                <form onSubmit={handleAddChecklist} className="flex items-center gap-2 mt-1">
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
                    className="flex-1 text-sm bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] select-text"
                    autoFocus={isChecklistMode}
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
                rows={3}
                className="w-full text-sm sm:text-base bg-transparent border-none outline-none resize-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] leading-relaxed select-text"
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

            {/* Bottom Actions Toolbar */}
            <div className="flex items-center justify-between pt-2">
              <div className="flex items-center gap-0.5">
                {/* Color Palette Button */}
                <div className="relative">
                  <button
                    type="button"
                    onClick={() => setShowColorPicker(!showColorPicker)}
                    title="Rang tanlash"
                    className="w-8 h-8 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center transition-colors cursor-pointer"
                  >
                    <Palette className="w-4 h-4" />
                  </button>
                  {showColorPicker && (
                    <div className="absolute bottom-10 left-0 z-50">
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

                {/* Checklist Mode Toggle */}
                <button
                  type="button"
                  onClick={handleToggleChecklistMode}
                  title={isChecklistMode ? 'Oddiy matn rejimi' : "Ro'yxat rejimi"}
                  className={`w-8 h-8 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center transition-colors cursor-pointer ${
                    isChecklistMode ? 'text-[#202124] dark:text-white font-bold bg-black/5 dark:bg-white/10' : 'text-[#5f6368] dark:text-[#9aa0a6]'
                  }`}
                >
                  <CheckSquare className="w-4 h-4" />
                </button>

                {/* Add Image */}
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={isUploading}
                  title="Rasm qo'shish"
                  className="w-8 h-8 rounded-full hover:bg-black/5 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#9aa0a6] flex items-center justify-center transition-colors cursor-pointer"
                >
                  <ImageIcon className="w-4 h-4" />
                </button>
              </div>

              {/* Close / Save Button */}
              <button
                type="button"
                onClick={saveAndClose}
                className="px-6 py-2 text-sm font-medium text-[#202124] dark:text-[#e8eaed] hover:bg-black/5 dark:hover:bg-white/10 rounded transition-colors cursor-pointer"
              >
                Yopish
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
