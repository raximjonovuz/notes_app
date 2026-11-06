import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import type { User } from '../types/note';
import { api } from '../services/api';
import {
  getStoredToken,
  getStoredUser,
  clearAuthSession,
  isSessionExpired,
} from '../services/storage';

export const GOOGLE_CLIENT_ID = '506282696730-jmrbedtsp0ce2onj5q5uo5hcg8k89lo6.apps.googleusercontent.com';

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  loginWithGoogle: (idToken: string) => Promise<void>;
  openLoginModal: () => void;
  closeLoginModal: () => void;
  isLoginModalOpen: boolean;
  logout: () => Promise<void>;
  clearError: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(getStoredUser());
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [isLoginModalOpen, setIsLoginModalOpen] = useState<boolean>(false);

  const clearError = useCallback(() => setError(null), []);

  const openLoginModal = useCallback(() => setIsLoginModalOpen(true), []);
  const closeLoginModal = useCallback(() => setIsLoginModalOpen(false), []);

  // Listen for unauthorized events (e.g. 401 from API)
  useEffect(() => {
    const handleUnauthorized = () => {
      clearAuthSession();
      setUser(null);
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
      } catch {
        clearAuthSession();
        setUser(null);
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
      closeLoginModal();
    } catch (e: any) {
      setError(e.message || 'Google orqali kirishda xatolik yuz berdi.');
      throw e;
    } finally {
      setIsLoading(false);
    }
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
      setIsLoading(false);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        error,
        loginWithGoogle,
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
