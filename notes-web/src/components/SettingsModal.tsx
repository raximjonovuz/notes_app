import React, { useState } from 'react';
import { X, Sun, Moon, Monitor, Shield } from 'lucide-react';
import { useTheme } from '../context/ThemeContext';
import type { ThemeMode } from '../context/ThemeContext';
import { useAuth } from '../context/AuthContext';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({ isOpen, onClose }) => {
  const { theme, setTheme } = useTheme();
  const { user, isAuthenticated } = useAuth();

  const [selectedTheme, setSelectedTheme] = useState<ThemeMode>(theme);
  const [moveCheckedToBottom, setMoveCheckedToBottom] = useState<boolean>(() => {
    return localStorage.getItem('notes_setting_move_checked') !== 'false';
  });

  if (!isOpen) return null;

  const handleSave = () => {
    setTheme(selectedTheme);
    localStorage.setItem('notes_setting_move_checked', String(moveCheckedToBottom));
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs select-none animate-in fade-in duration-150">
      <div className="fixed inset-0" onClick={onClose} />

      <div className="relative w-full max-w-md bg-white dark:bg-[#202124] rounded-2xl shadow-2xl border border-gray-200 dark:border-[#5f6368] overflow-hidden z-10 animate-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 dark:border-[#3c4043]">
          <h3 className="text-lg font-medium text-[#202124] dark:text-[#e8eaed]">
            Sozlamalar
          </h3>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full hover:bg-gray-100 dark:hover:bg-[#303134] text-gray-500 dark:text-gray-400 cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="p-6 space-y-6">
          {/* Theme Option */}
          <div>
            <label className="block text-sm font-medium text-[#202124] dark:text-[#e8eaed] mb-3">
              Mavzu (Rang rejimi)
            </label>
            <div className="grid grid-cols-3 gap-2">
              <button
                type="button"
                onClick={() => setSelectedTheme('light')}
                className={`flex flex-col items-center justify-center gap-2 p-3 rounded-xl border text-xs font-medium cursor-pointer transition-all ${
                  selectedTheme === 'light'
                    ? 'border-[#2D6A4F] bg-[#D8F3DC]/30 text-[#081C15] dark:text-white'
                    : 'border-gray-200 dark:border-[#3c4043] text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-[#303134]'
                }`}
              >
                <Sun className="w-5 h-5 text-amber-500" />
                <span>Yorug' (Kunduzi)</span>
              </button>

              <button
                type="button"
                onClick={() => setSelectedTheme('dark')}
                className={`flex flex-col items-center justify-center gap-2 p-3 rounded-xl border text-xs font-medium cursor-pointer transition-all ${
                  selectedTheme === 'dark'
                    ? 'border-[#2D6A4F] bg-[#D8F3DC]/30 text-[#081C15] dark:text-white'
                    : 'border-gray-200 dark:border-[#3c4043] text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-[#303134]'
                }`}
              >
                <Moon className="w-5 h-5 text-[#74C69D]" />
                <span>Qorong'i (Tun)</span>
              </button>

              <button
                type="button"
                onClick={() => setSelectedTheme('system')}
                className={`flex flex-col items-center justify-center gap-2 p-3 rounded-xl border text-xs font-medium cursor-pointer transition-all ${
                  selectedTheme === 'system'
                    ? 'border-[#2D6A4F] bg-[#D8F3DC]/30 text-[#081C15] dark:text-white'
                    : 'border-gray-200 dark:border-[#3c4043] text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-[#303134]'
                }`}
              >
                <Monitor className="w-5 h-5 text-gray-500" />
                <span>Tizim bo'yicha</span>
              </button>
            </div>
          </div>

          {/* Checklist Behavior */}
          <div className="pt-2 border-t border-gray-100 dark:border-[#3c4043]">
            <label className="flex items-center justify-between cursor-pointer">
              <span className="text-sm text-[#202124] dark:text-[#e8eaed]">
                Bajarilgan elementlarni ro'yxat oxiriga tushirish
              </span>
              <input
                type="checkbox"
                checked={moveCheckedToBottom}
                onChange={(e) => setMoveCheckedToBottom(e.target.checked)}
                className="w-4 h-4 text-[#2D6A4F] accent-[#2D6A4F] rounded cursor-pointer"
              />
            </label>
          </div>

          {/* Session & Account Info */}
          <div className="pt-2 border-t border-gray-100 dark:border-[#3c4043]">
            <div className="flex items-start gap-3 p-3 bg-gray-50 dark:bg-[#303134] rounded-xl text-xs text-gray-600 dark:text-gray-300">
              <Shield className="w-4 h-4 text-[#2D6A4F] shrink-0 mt-0.5" />
              <div>
                <p className="font-semibold text-gray-800 dark:text-gray-100 mb-0.5">
                  Xavfsiz kesh va sessiya
                </p>
                <p>
                  {isAuthenticated && user
                    ? `Faol hisob: ${user.email}. Oxirgi faollikdan keyin 3 kun davomida kirmasangiz, sessiya avtomatik tozalanadi.`
                    : "Siz mehmon rejimidamisiz. Qaydlaringiz qurilma xotirasida saqlanadi."}
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-6 py-4 bg-gray-50 dark:bg-[#303134]/50 border-t border-gray-100 dark:border-[#3c4043]">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-200/60 dark:hover:bg-white/10 rounded-lg transition-colors cursor-pointer"
          >
            Bekor qilish
          </button>
          <button
            type="button"
            onClick={handleSave}
            className="px-5 py-2 text-sm font-medium text-white bg-[#2D6A4F] hover:bg-[#1B4332] active:scale-95 rounded-lg shadow-xs transition-all cursor-pointer"
          >
            Saqlash
          </button>
        </div>
      </div>
    </div>
  );
};
