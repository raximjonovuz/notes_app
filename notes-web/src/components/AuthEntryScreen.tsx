import React, { useEffect, useRef, useState } from 'react';
import { Shield, Loader2, AlertCircle } from 'lucide-react';
import { useAuth, GOOGLE_CLIENT_ID } from '../context/AuthContext';
import { NotesLogo } from './NotesLogo';

export const AuthEntryScreen: React.FC = () => {
  const { loginWithGoogle, isLoading, error } = useAuth();
  const [localError, setLocalError] = useState<string | null>(null);
  const [isGoogleLoading, setIsGoogleLoading] = useState<boolean>(false);
  const googleBtnContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let isMounted = true;

    const setupGoogle = () => {
      if (!window.google?.accounts?.id) return;

      try {
        window.google.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          cancel_on_tap_outside: false,
          callback: async (response: any) => {
            if (response?.credential && isMounted) {
              setIsGoogleLoading(true);
              setLocalError(null);
              try {
                await loginWithGoogle(response.credential);
              } catch (err: any) {
                if (isMounted) {
                  setLocalError(err.message || 'Google token tasdiqlanmadi');
                }
              } finally {
                if (isMounted) setIsGoogleLoading(false);
              }
            }
          },
        });

        if (googleBtnContainerRef.current && isMounted) {
          googleBtnContainerRef.current.innerHTML = '';
          window.google.accounts.id.renderButton(googleBtnContainerRef.current, {
            theme: 'outline',
            size: 'large',
            type: 'standard',
            shape: 'pill',
            text: 'continue_with',
            width: 280,
          });
        }

        // Display One Tap prompt automatically
        window.google.accounts.id.prompt();
      } catch (e) {
        console.warn('Google Identity error:', e);
      }
    };

    if (window.google?.accounts?.id) {
      setupGoogle();
    } else {
      const script = document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.defer = true;
      script.onload = setupGoogle;
      document.body.appendChild(script);
    }

    return () => {
      isMounted = false;
    };
  }, [loginWithGoogle]);

  const activeError = localError || error;

  return (
    <div className="min-h-screen w-full bg-[#F9FBF9] flex flex-col items-center justify-between font-sans select-none overflow-x-hidden">
      {/* 1. TOP HEADER - Morphing Forest Green Section (identical to mobile AnimatedLoginFlow) */}
      <div className="w-full bg-gradient-to-b from-[#081C15] via-[#1B4332] to-[#2D6A4F] pt-12 pb-16 px-6 rounded-b-[40px] shadow-xl flex flex-col items-center justify-center text-center text-white relative">
        {/* Subtle decorative glow */}
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_50%_40%,rgba(116,198,157,0.15),transparent_60%)] pointer-events-none" />

        <div className="relative z-10 flex flex-col items-center animate-in fade-in zoom-in-95 duration-500">
          <NotesLogo size={105} animated className="mb-4 drop-shadow-lg" />

          <h1 className="text-4xl sm:text-5xl font-bold tracking-tight text-white mb-2">
            Notes<span className="text-[#74C69D]">.</span>
          </h1>

          <p className="text-sm sm:text-base text-[#D8F3DC]/90 max-w-sm tracking-wide font-normal">
            Fikrlaringizni oson va qulay qayd qiling
          </p>
        </div>
      </div>

      {/* 2. BODY CONTENT CARD */}
      <div className="w-full max-w-md px-6 py-8 flex-1 flex flex-col justify-center items-center text-center">
        <div className="w-full animate-in fade-in slide-in-from-bottom-4 duration-500 delay-100">
          <h2 className="text-2xl sm:text-3xl font-bold text-[#081C15] mb-2 tracking-tight">
            Notes ilovasiga xush kelibsiz
          </h2>
          <p className="text-sm text-[#5F6368] mb-8 leading-relaxed max-w-xs mx-auto">
            Qaydlaringiz telefon va kompyuter o'rtasida real vaqtda sinxronlanishi uchun Google hisobingiz bilan kiring.
          </p>

          {/* Error Banner */}
          {activeError && (
            <div className="mb-6 p-3 bg-red-50 border border-red-200 text-red-800 rounded-2xl text-xs sm:text-sm flex items-center gap-2 text-left animate-in shake duration-200">
              <AlertCircle className="w-4 h-4 shrink-0 text-red-600" />
              <span>{activeError}</span>
            </div>
          )}

          {/* Google Sign In Container (Mandatory - No guest mode) */}
          <div className="w-full flex flex-col items-center justify-center">
            {isGoogleLoading || isLoading ? (
              <div className="w-full max-w-[280px] h-[46px] rounded-full border border-gray-300 bg-white shadow-xs flex items-center justify-center gap-3">
                <Loader2 className="w-5 h-5 text-[#2D6A4F] animate-spin" />
                <span className="text-sm font-medium text-gray-700">Google bilan ulanmoqda…</span>
              </div>
            ) : (
              <div ref={googleBtnContainerRef} className="min-h-[46px] flex items-center justify-center" />
            )}
          </div>
        </div>
      </div>

      {/* 3. SECURITY & PRIVACY FOOTER BADGE */}
      <div className="w-full max-w-sm px-6 pb-8 pt-2 flex flex-col items-center text-center animate-in fade-in duration-700 delay-200">
        <div className="flex items-center gap-1.5 text-xs font-semibold text-[#2D6A4F] mb-1">
          <Shield className="w-4 h-4 text-[#40916C]" />
          <span>Shaxsiy va xavfsiz shifrlangan</span>
        </div>
        <p className="text-[11px] leading-tight text-[#8E919A]">
          Qaydlaringiz faqat sizning hisobingizga tegishli va doimiy zaxiralanadi.
        </p>
      </div>
    </div>
  );
};
