import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import type { User } from '../types/note';
import { api } from '../services/api';
import {
  getStoredToken,
  getStoredUser,
  clearAuthSession,
  isSessionExpired,
  isGuestOnboarded,
  setGuestOnboarded,
} from '../services/storage';

export const GOOGLE_CLIENT_ID = '506282696730-jmrbedtsp0ce2onj5q5uo5hcg8k89lo6.apps.googleusercontent.com';

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isGuest: boolean;
  isLoading: boolean;
  isSplashPhase: boolean;
  error: string | null;
  loginWithGoogle: (idToken: string) => Promise<void>;
  continueAsGuest: () => void;
  openLoginModal: () => void;
  closeLoginModal: () => void;
  isLoginModalOpen: boolean;
  logout: () => Promise<void>;
  clearError: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(getStoredUser());
  const [isGuest, setIsGuest] = useState<boolean>(() => !getStoredUser() && isGuestOnboarded());
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [isLoginModalOpen, setIsLoginModalOpen] = useState<boolean>(false);

  // Splash/Entry screen shown if user is neither authenticated nor opted into guest mode
  const [isSplashPhase, setIsSplashPhase] = useState<boolean>(() => {
    return !getStoredUser() && !isGuestOnboarded();
  });

  const clearError = useCallback(() => setError(null), []);

  const openLoginModal = useCallback(() => setIsLoginModalOpen(true), []);
  const closeLoginModal = useCallback(() => setIsLoginModalOpen(false), []);

  // Listen for unauthorized events (e.g. 401 from API)
  useEffect(() => {
    const handleUnauthorized = () => {
      clearAuthSession();
      setUser(null);
      setIsGuest(true);
      setError('Sessiya muddati tugadi. Qaytadan kiring.');
    };
    window.addEventListener('notes:unauthorized', handleUnauthorized);
    return () => window.removeEventListener('notes:unauthorized', handleUnauthorized);
  }, []);

  // Check 3-day session expiry on mount and periodically / on visibility change
  useEffect(() => {
    const checkExpiry = () => {
      if (getStoredToken() && isSessionExpired()) {
        clearAuthSession();
        setUser(null);
        setIsGuest(true);
        setError('3 kunlik faolsizlik sababli tizimdan chiqildi.');
      }
    };

    checkExpiry();

    const interval = setInterval(checkExpiry, 60_000); // Check every minute
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        checkExpiry();
      }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);

    return () => {
      clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, []);

  // Check token and user on initial mount
  useEffect(() => {
    const initAuth = async () => {
      const token = getStoredToken();
      if (!token) {
        setIsLoading(false);
        return;
      }
      try {
        const currentUser = await api.getMe();
        setUser(currentUser);
        setIsGuest(false);
        setIsSplashPhase(false);
      } catch {
        clearAuthSession();
        setUser(null);
        setIsGuest(isGuestOnboarded());
      } finally {
        setIsLoading(false);
      }
    };
    initAuth();
  }, []);

  const loginWithGoogle = async (idToken: string) => {
    setIsLoading(true);
    setError(null);
    try {
      const res = await api.loginGoogle(idToken);
      setUser(res.user);
      setIsGuest(false);
      setIsSplashPhase(false);
      setGuestOnboarded(true);
      closeLoginModal();
    } catch (e: any) {
      setError(e.message || 'Google orqali kirishda xatolik yuz berdi.');
      throw e;
    } finally {
      setIsLoading(false);
    }
  };

  const continueAsGuest = () => {
    setGuestOnboarded(true);
    setIsGuest(true);
    setIsSplashPhase(false);
    closeLoginModal();
  };

  const logout = async () => {
    setIsLoading(true);
    try {
      await api.logout();
    } catch {
      // ignore
    } finally {
      clearAuthSession();
      setUser(null);
      setIsGuest(true);
      setIsSplashPhase(false);
      setIsLoading(false);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isGuest,
        isLoading,
        isSplashPhase,
        error,
        loginWithGoogle,
        continueAsGuest,
        openLoginModal,
        closeLoginModal,
        isLoginModalOpen,
        logout,
        clearError,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
