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

  const containerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

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
  }, [isExpanded, title, content, checklists, colorKey, isPinned, images, isChecklistMode]);

  const saveAndClose = async () => {
    const hasContent = title.trim() || content.trim() || checklists.length > 0 || images.length > 0;
    if (hasContent) {
      let finalContent = content;
      if (isChecklistMode || images.length > 0) {
        finalContent = buildV3Content(content, checklists, images);
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
      { id: `chk_${Date.now()}`, text: newChecklistText.trim(), checked: false },
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
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="Sarlavha"
                className="w-full font-medium text-base text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] bg-transparent border-none outline-none"
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
                  <div key={chk.id} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={chk.checked}
                      onChange={() => handleToggleChecklist(idx)}
                      className="w-4 h-4 text-[#5f6368] accent-[#5f6368] rounded focus:ring-0 cursor-pointer"
                    />
                    <span
                      className={`flex-1 text-[#202124] dark:text-[#e8eaed] ${
                        chk.checked ? 'line-through text-[#80868b] dark:text-[#9aa0a6]' : ''
                      }`}
                    >
                      {chk.text}
                    </span>
                    <button
                      type="button"
                      onClick={() => handleRemoveChecklist(idx)}
                      className="text-[#5f6368] hover:text-[#202124] dark:hover:text-white cursor-pointer"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}

                <form onSubmit={handleAddChecklist} className="flex items-center gap-2 mt-1">
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
                    className="flex-1 text-sm bg-transparent border-none outline-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6]"
                    autoFocus={isChecklistMode}
                  />
                </form>
              </div>
            ) : (
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="Qayd yozish..."
                rows={3}
                className="w-full text-sm sm:text-base bg-transparent border-none outline-none resize-none text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] leading-relaxed"
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
                  onClick={() => setIsChecklistMode(!isChecklistMode)}
                  title={isChecklistMode ? 'Oddiy matn rejimi' : "Ro'yxat rejimi"}
                  className={`w-8 h-8 rounded-full hover:bg-black/5 dark:hover:bg-white/10 flex items-center justify-center transition-colors cursor-pointer ${
                    isChecklistMode ? 'text-[#202124] dark:text-white font-bold' : 'text-[#5f6368] dark:text-[#9aa0a6]'
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
