import React, { useState } from 'react';
import {
  Menu,
  Search,
  RefreshCw,
  LayoutGrid,
  Rows,
  Settings,
  Sun,
  Moon,
  LogOut,
  User as UserIcon,
  X,
  CheckCircle2,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useNotes } from '../context/NotesContext';
import { useTheme } from '../context/ThemeContext';
import { NotesLogo } from './NotesLogo';

interface HeaderProps {
  onToggleSidebar: () => void;
  onOpenLogin: () => void;
  onOpenSettings: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  onToggleSidebar,
  onOpenLogin,
  onOpenSettings,
}) => {
  const { user, isAuthenticated, logout } = useAuth();
  const { searchQuery, setSearchQuery, viewMode, setViewMode, isSyncing, syncNow, lastSyncTime } = useNotes();
  const { isDark, toggleTheme } = useTheme();

  const [showProfileMenu, setShowProfileMenu] = useState(false);
  const [showSettingsMenu, setShowSettingsMenu] = useState(false);

  return (
    <header className="sticky top-0 z-40 bg-white dark:bg-[#202124] border-b border-[#e0e0e0] dark:border-[#5f6368]/60 px-2 sm:px-4 h-16 flex items-center justify-between gap-2 sm:gap-4 transition-colors select-none">
      {/* Left: Hamburger & Google Keep style Notes brand */}
      <div className="flex items-center gap-1 sm:gap-2 shrink-0">
        <button
          onClick={onToggleSidebar}
          className="w-12 h-12 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#e8eaed] flex items-center justify-center transition-colors cursor-pointer"
          title="Asosiy menyu"
        >
          <Menu className="w-6 h-6" />
        </button>

        <div className="flex items-center gap-2 cursor-pointer pr-4">
          <NotesLogo size={34} animated />
          <span className="text-[22px] font-normal tracking-tight text-[#5f6368] dark:text-[#e8eaed]">
            Notes
          </span>
        </div>
      </div>

      {/* Center: Search Bar (Google Keep 8px rounded container) */}
      <div className="flex-1 max-w-[720px] relative mx-1 sm:mx-2">
        <div className="relative flex items-center group">
          <button
            type="button"
            className="w-10 h-10 flex items-center justify-center absolute left-1 text-[#5f6368] dark:text-[#9aa0a6] pointer-events-none"
          >
            <Search className="w-5 h-5" />
          </button>
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Qidirish"
            className="w-full h-11 sm:h-12 pl-12 pr-10 bg-[#f1f3f4] dark:bg-[#525355]/30 focus:bg-white dark:focus:bg-[#202124] text-[#202124] dark:text-[#e8eaed] placeholder-[#5f6368] dark:placeholder-[#9aa0a6] rounded-lg focus:shadow-[0_1px_1px_0_rgba(65,69,73,0.3),0_1px_3px_1px_rgba(65,69,73,0.15)] focus:outline-none transition-all text-sm sm:text-base border border-transparent focus:border-transparent"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="w-8 h-8 rounded-full flex items-center justify-center absolute right-2 text-[#5f6368] dark:text-[#9aa0a6] hover:bg-gray-200 dark:hover:bg-white/10 cursor-pointer"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {/* Right Actions: Keep style toolbar */}
      <div className="flex items-center gap-0.5 sm:gap-1 shrink-0">
        {/* Refresh / Sync button */}
        <button
          onClick={() => syncNow()}
          disabled={isSyncing}
          title={
            isSyncing
              ? 'Sinxronlanmoqda...'
              : lastSyncTime
              ? `Oxirgi sinxron: ${lastSyncTime.toLocaleTimeString()}`
              : 'Yangilash'
          }
          className="w-10 h-10 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#e8eaed] flex items-center justify-center transition-colors cursor-pointer"
        >
          <RefreshCw className={`w-5 h-5 ${isSyncing ? 'animate-spin text-[#2D6A4F] dark:text-[#74C69D]' : ''}`} />
        </button>

        {/* View Mode Switcher */}
        <button
          onClick={() => setViewMode(viewMode === 'grid' ? 'linear' : 'grid')}
          title={viewMode === 'grid' ? "Ro'yxat ko'rinishi" : "Setka ko'rinishi"}
          className="w-10 h-10 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#e8eaed] hidden sm:flex items-center justify-center transition-colors cursor-pointer"
        >
          {viewMode === 'grid' ? <Rows className="w-5 h-5" /> : <LayoutGrid className="w-5 h-5" />}
        </button>

        {/* Settings ⚙️ Gear Button */}
        <div className="relative">
          <button
            onClick={() => setShowSettingsMenu(!showSettingsMenu)}
            title="Sozlamalar"
            className="w-10 h-10 rounded-full hover:bg-gray-100 dark:hover:bg-white/10 text-[#5f6368] dark:text-[#e8eaed] flex items-center justify-center transition-colors cursor-pointer"
          >
            <Settings className="w-5 h-5" />
          </button>

          {showSettingsMenu && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setShowSettingsMenu(false)}
              />
              <div className="absolute right-0 mt-2 w-56 bg-white dark:bg-[#303134] rounded-lg shadow-xl border border-gray-200 dark:border-[#5f6368] py-2 z-50 animate-in fade-in zoom-in-95 duration-100 text-[#202124] dark:text-[#e8eaed]">
                <button
                  onClick={() => {
                    setShowSettingsMenu(false);
                    onOpenSettings();
                  }}
                  className="w-full px-4 py-2.5 text-left text-sm hover:bg-gray-100 dark:hover:bg-white/10 flex items-center gap-3 transition-colors cursor-pointer"
                >
                  <Settings className="w-4 h-4 text-[#5f6368] dark:text-[#9aa0a6]" />
                  <span>Sozlamalar</span>
                </button>

                <button
                  onClick={() => {
                    toggleTheme();
                    setShowSettingsMenu(false);
                  }}
                  className="w-full px-4 py-2.5 text-left text-sm hover:bg-gray-100 dark:hover:bg-white/10 flex items-center justify-between transition-colors cursor-pointer"
                >
                  <div className="flex items-center gap-3">
                    {isDark ? (
                      <Sun className="w-4 h-4 text-amber-500" />
                    ) : (
                      <Moon className="w-4 h-4 text-[#2D6A4F]" />
                    )}
                    <span>{isDark ? "Yorug' mavzuni yoqish" : "Qorong'i mavzuni yoqish"}</span>
                  </div>
                </button>
              </div>
            </>
          )}
        </div>

        {/* Profile / Sign-in */}
        {isAuthenticated && user ? (
          <div className="relative ml-2">
            <button
              onClick={() => setShowProfileMenu(!showProfileMenu)}
              className="w-8 h-8 rounded-full bg-[#1B4332] text-white font-medium flex items-center justify-center text-xs hover:ring-2 hover:ring-[#74C69D] transition-all cursor-pointer shadow-xs"
              title={user.email}
            >
              {user.email.charAt(0).toUpperCase()}
            </button>

            {showProfileMenu && (
              <>
                <div
                  className="fixed inset-0 z-40"
                  onClick={() => setShowProfileMenu(false)}
                />
                <div className="absolute right-0 mt-2 w-72 bg-white dark:bg-[#303134] rounded-2xl shadow-2xl border border-gray-200 dark:border-[#5f6368] p-4 z-50 animate-in fade-in zoom-in-95 duration-100">
                  <div className="flex items-center gap-3 pb-3 border-b border-gray-100 dark:border-[#3c4043]">
                    <div className="w-10 h-10 rounded-full bg-[#1B4332] text-white flex items-center justify-center font-bold text-base shrink-0">
                      {user.email.charAt(0).toUpperCase()}
                    </div>
                    <div className="overflow-hidden">
                      <p className="font-semibold text-sm text-[#202124] dark:text-[#e8eaed] truncate">
                        {user.email}
                      </p>
                      <div className="flex items-center gap-1 text-xs text-[#2D6A4F] dark:text-[#74C69D] font-medium mt-0.5">
                        <CheckCircle2 className="w-3.5 h-3.5" />
                        <span>Sinxronlangan</span>
                      </div>
                    </div>
                  </div>
                  <button
                    onClick={() => {
                      setShowProfileMenu(false);
                      logout();
                    }}
                    className="w-full mt-3 px-3 py-2 text-left text-sm text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/30 rounded-lg flex items-center gap-2 transition-colors cursor-pointer font-medium"
                  >
                    <LogOut className="w-4 h-4" />
                    Chiqish
                  </button>
                </div>
              </>
            )}
          </div>
        ) : (
          <button
            onClick={onOpenLogin}
            className="ml-2 px-4 py-1.5 bg-[#2D6A4F] hover:bg-[#1B4332] active:scale-95 text-white font-medium rounded-full text-xs sm:text-sm shadow-xs transition-all flex items-center gap-1.5 cursor-pointer"
          >
            <UserIcon className="w-4 h-4" />
            <span>Kirish</span>
          </button>
        )}
      </div>
    </header>
  );
};
