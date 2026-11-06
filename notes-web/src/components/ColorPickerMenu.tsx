import React from 'react';
import { NOTE_PALETTE } from '../types/note';
import type { NoteColorKey } from '../types/note';
import { useTheme } from '../context/ThemeContext';
import { Check } from 'lucide-react';

interface ColorPickerMenuProps {
  currentColorKey?: string;
  onSelectColor: (key: NoteColorKey) => void;
  className?: string;
}

export const ColorPickerMenu: React.FC<ColorPickerMenuProps> = ({
  currentColorKey = 'default',
  onSelectColor,
  className = '',
}) => {
  const { isDark } = useTheme();

  return (
    <div
      className={`p-3 bg-white dark:bg-[#202124] border border-gray-200 dark:border-gray-700 rounded-2xl shadow-xl grid grid-cols-5 gap-2 w-[240px] z-50 animate-in fade-in zoom-in-95 duration-150 ${className}`}
      onClick={(e) => e.stopPropagation()}
    >
      {NOTE_PALETTE.map((c) => {
        const isSelected = (currentColorKey || 'default').toLowerCase() === c.key.toLowerCase();
        const bg = isDark ? c.dark : c.light;
        const border = isDark ? c.borderDark : c.borderLight;

        return (
          <button
            key={c.key}
            type="button"
            title={c.labelUz}
            onClick={() => onSelectColor(c.key)}
            className={`w-9 h-9 rounded-full flex items-center justify-center transition-all duration-150 hover:scale-110 active:scale-95 relative cursor-pointer ${
              isSelected ? 'ring-2 ring-primary ring-offset-2 dark:ring-offset-[#202124]' : ''
            }`}
            style={{
              backgroundColor: bg,
              border: `1.5px solid ${border}`,
            }}
          >
            {isSelected && (
              <Check className={`w-4 h-4 ${isDark ? 'text-gray-200' : 'text-gray-700'}`} />
            )}
          </button>
        );
      })}
    </div>
  );
};
