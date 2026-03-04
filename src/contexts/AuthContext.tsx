import { createContext, useContext, useEffect, useState, useCallback, type ReactNode } from 'react';
import { auth as authApi, users as usersApi, setAuthTokens, clearAuthTokens, setOnTokenRefreshFailed } from '@/lib/api';
import { wsClient } from '@/lib/ws';
import type { User, LoginRequest, RegisterRequest, AuthTokens } from '@/types';

interface AuthState {
  user: User | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
  error: string | null;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const handleTokens = useCallback(async (tokens: AuthTokens) => {
    setAuthTokens(tokens);
    try {
      const userData = await usersApi.me();
      setUser(userData);
      wsClient.connect();
    } catch {
      clearAuthTokens();
      setUser(null);
    }
  }, []);

  const logout = useCallback(() => {
    clearAuthTokens();
    setUser(null);
    wsClient.disconnect();
  }, []);

  useEffect(() => {
    setOnTokenRefreshFailed(logout);
    // On mount, no persisted token (stored in memory only), so just mark as loaded
    setIsLoading(false);
  }, [logout]);

  const login = useCallback(async (data: LoginRequest) => {
    setError(null);
    setIsLoading(true);
    try {
      const tokens = await authApi.login(data);
      await handleTokens(tokens);
    } catch (err: any) {
      const message = err?.response?.data?.message || 'Login failed';
      setError(message);
      throw new Error(message);
    } finally {
      setIsLoading(false);
    }
  }, [handleTokens]);

  const register = useCallback(async (data: RegisterRequest) => {
    setError(null);
    setIsLoading(true);
    try {
      const tokens = await authApi.register(data);
      await handleTokens(tokens);
    } catch (err: any) {
      const message = err?.response?.data?.message || 'Registration failed';
      setError(message);
      throw new Error(message);
    } finally {
      setIsLoading(false);
    }
  }, [handleTokens]);

  return (
    <AuthContext.Provider
      value={{
        user,
        isLoading,
        isAuthenticated: !!user,
        login,
        register,
        logout,
        error,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
