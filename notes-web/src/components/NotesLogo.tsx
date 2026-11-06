import React from 'react';

interface NotesLogoProps {
  size?: number;
  className?: string;
  animated?: boolean;
}

export const NotesLogo: React.FC<NotesLogoProps> = ({ size = 40, className = '', animated = false }) => {
  return (
    <div
      style={{ width: size, height: size }}
      className={`relative inline-flex items-center justify-center shrink-0 ${animated ? 'hover:scale-105 transition-transform duration-300' : ''} ${className}`}
    >
      <svg
        viewBox="0 0 100 100"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        className="w-full h-full drop-shadow-sm"
      >
        <defs>
          <linearGradient id="logoForest" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#081C15" />
            <stop offset="60%" stopColor="#1B4332" />
            <stop offset="100%" stopColor="#2D6A4F" />
          </linearGradient>
          <linearGradient id="spineGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#40916C" />
            <stop offset="100%" stopColor="#2D6A4F" />
          </linearGradient>
        </defs>

        {/* Outer squircle badge */}
        <rect width="100" height="100" rx="26" fill="url(#logoForest)" />

        {/* Notebook sheet */}
        <rect x="23" y="20" width="54" height="60" rx="8" fill="#D8F3DC" />
        
        {/* Notebook Spine */}
        <path d="M23 28 C23 23.58 26.58 20 31 20 L35 20 L35 80 L31 80 C26.58 80 23 76.42 23 72 Z" fill="url(#spineGrad)" />

        {/* Ruling lines */}
        <rect x="41" y="33" width="28" height="3.5" rx="1.75" fill="#2D6A4F" opacity="0.9" />
        <rect x="41" y="43" width="24" height="3.5" rx="1.75" fill="#2D6A4F" opacity="0.9" />
        <rect x="41" y="53" width="18" height="3.5" rx="1.75" fill="#40916C" opacity="0.8" />

        {/* Sparkle star / Quill accent */}
        <circle cx="71" cy="65" r="3" fill="#74C69D" />
      </svg>
    </div>
  );
};
