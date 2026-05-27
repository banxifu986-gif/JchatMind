import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  AuthContext,
  TOKEN_KEY,
  USER_KEY,
} from "./UserContextBase.ts";
import type { AuthContextType, UserInfo } from "./UserContextBase.ts";

async function whoamiCall(token: string): Promise<{ user: UserInfo; token: string } | null> {
  try {
    const resp = await fetch("/api/users/whoami", {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!resp.ok) return null;
    const body = await resp.json();
    if (body.code !== 200 || !body.data) return null;
    return { user: body.data, token: body.data.token || token };
  } catch {
    return null;
  }
}

export function UserProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [token, setToken] = useState<string | null>(() =>
    window.localStorage.getItem(TOKEN_KEY),
  );
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (token) {
      whoamiCall(token).then((result) => {
        if (result) {
          setUser(result.user);
          if (result.token !== token) {
            setToken(result.token);
            window.localStorage.setItem(TOKEN_KEY, result.token);
          }
          window.localStorage.setItem(USER_KEY, JSON.stringify(result.user));
        } else {
          setToken(null);
          setUser(null);
          window.localStorage.removeItem(TOKEN_KEY);
          window.localStorage.removeItem(USER_KEY);
        }
        setLoading(false);
      });
    } else {
      setLoading(false);
    }
  }, []);

  const login = useCallback((newToken: string, newUser: UserInfo) => {
    setToken(newToken);
    setUser(newUser);
    window.localStorage.setItem(TOKEN_KEY, newToken);
    window.localStorage.setItem(USER_KEY, JSON.stringify(newUser));
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
    window.localStorage.removeItem(TOKEN_KEY);
    window.localStorage.removeItem(USER_KEY);
  }, []);

  const refreshUser = useCallback(async () => {
    if (!token) return;
    const result = await whoamiCall(token);
    if (result) {
      setUser(result.user);
      if (result.token !== token) {
        setToken(result.token);
        window.localStorage.setItem(TOKEN_KEY, result.token);
      }
    }
  }, [token]);

  const value: AuthContextType = useMemo(
    () => ({
      user,
      token,
      isLogin: !!user,
      loading,
      login,
      logout,
      refreshUser,
    }),
    [user, token, loading, login, logout, refreshUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
