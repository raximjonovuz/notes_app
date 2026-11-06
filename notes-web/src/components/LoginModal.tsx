import React, { useEffect, useRef, useState } from 'react';
import { X, Shield, Loader2, AlertCircle } from 'lucide-react';
import { useAuth, GOOGLE_CLIENT_ID } from '../context/AuthContext';
import { NotesLogo } from './NotesLogo';

export const LoginModal: React.FC = () => {
  const { isLoginModalOpen, closeLoginModal, loginWithGoogle, isLoading, error, clearError } = useAuth();
  const [localError, setLocalError] = useState<string | null>(null);
  const [isGoogleLoading, setIsGoogleLoading] = useState<boolean>(false);
  const googleBtnRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isLoginModalOpen) return;

    clearError();
    setLocalError(null);

    const initGsi = () => {
      if (window.google?.accounts?.id) {
        window.google.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          cancel_on_tap_outside: false,
          callback: async (response: any) => {
            if (response.credential) {
              setIsGoogleLoading(true);
              setLocalError(null);
              try {
                await loginWithGoogle(response.credential);
              } catch (e: any) {
                setLocalError(e.message || 'Google token tasdiqlanmadi');
              } finally {
                setIsGoogleLoading(false);
              }
            }
          },
        });

        if (googleBtnRef.current) {
          googleBtnRef.current.innerHTML = '';
          window.google.accounts.id.renderButton(googleBtnRef.current, {
            theme: 'outline',
            size: 'large',
            type: 'standard',
            shape: 'pill',
            text: 'continue_with',
            width: 280,
          });
        }
      }
    };

    if (window.google?.accounts?.id) {
      initGsi();
    } else {
      const script = document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.defer = true;
      script.onload = initGsi;
      document.body.appendChild(script);
    }
  }, [isLoginModalOpen, clearError, loginWithGoogle]);

  if (!isLoginModalOpen) return null;

  const currentErr = localError || error;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs select-none animate-in fade-in duration-200">
      <div className="fixed inset-0" onClick={closeLoginModal} />

      <div className="relative w-full max-w-sm bg-white dark:bg-[#202124] rounded-3xl shadow-2xl overflow-hidden border border-gray-100 dark:border-gray-800 animate-in zoom-in-95 duration-200 z-10">
        {/* Top Header */}
        <div className="bg-gradient-to-r from-[#081C15] via-[#1B4332] to-[#2D6A4F] px-6 py-6 text-white relative">
          <button
            onClick={closeLoginModal}
            className="absolute top-4 right-4 p-1.5 rounded-full hover:bg-white/10 active:bg-white/20 transition-colors cursor-pointer text-white/80 hover:text-white"
          >
            <X className="w-5 h-5" />
          </button>

          <div className="flex items-center gap-3">
            <NotesLogo size={48} />
            <div>
              <h3 className="text-xl font-bold tracking-tight">Notes.</h3>
              <p className="text-xs text-[#D8F3DC]/80 font-medium">Your ideas, securely synced</p>
            </div>
          </div>
        </div>

        {/* Content */}
        <div className="p-6 flex flex-col items-center text-center">
          <h4 className="text-lg font-semibold text-gray-900 dark:text-gray-100 mb-1">
            Google orqali kiring
          </h4>
          <p className="text-xs text-gray-500 dark:text-gray-400 mb-6 max-w-xs">
            Barcha qaydlaringiz mobil ilova va web o'rtasida avtomatik sinxronlanadi.
          </p>

          {currentErr && (
            <div className="w-full mb-4 p-3 bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-900 text-red-700 dark:text-red-300 rounded-2xl text-xs flex items-center gap-2 text-left">
              <AlertCircle className="w-4 h-4 shrink-0 text-red-600" />
              <span>{currentErr}</span>
            </div>
          )}

          <div className="w-full flex justify-center mb-6">
            {isGoogleLoading || isLoading ? (
              <div className="w-[280px] h-[46px] rounded-full border border-gray-300 dark:border-gray-700 bg-white dark:bg-[#303134] flex items-center justify-center gap-2">
                <Loader2 className="w-5 h-5 text-blue-600 animate-spin" />
                <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  Ulanmoqda…
                </span>
              </div>
            ) : (
              <div ref={googleBtnRef} className="min-h-[46px] flex justify-center" />
            )}
          </div>

          <div className="flex items-center gap-1.5 text-[11px] text-[#2D6A4F] dark:text-[#74C69D] font-medium">
            <Shield className="w-3.5 h-3.5" />
            <span>Xavfsiz va shifrlangan ma'lumotlar</span>
          </div>
        </div>
      </div>
    </div>
  );
};
