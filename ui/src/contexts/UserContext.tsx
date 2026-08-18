import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AuthContext,
  TOKEN_KEY,
  USER_KEY,
} from "./UserContextBase.ts";
import type { AuthContextType, UserInfo } from "./UserContextBase.ts";
import { BASE_URL } from "../api/http.ts";

async function whoamiCall(token: string): Promise<{ user: UserInfo; token: string } | null> {
  try {
    const resp = await fetch(`${BASE_URL}/users/whoami`, {
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
  const tokenRef = useRef(token);
  const [loading, setLoading] = useState(() =>
    Boolean(window.localStorage.getItem(TOKEN_KEY)),
  );
  const updateToken = useCallback((newToken: string | null) => {
    tokenRef.current = newToken;
    setToken(newToken);
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (!token) {
      return;
    }

    whoamiCall(token).then((result) => {
      if (cancelled || tokenRef.current !== token) {
        return;
      }
      if (result) {
        setUser(result.user);
        if (result.token !== token) {
          updateToken(result.token);
          window.localStorage.setItem(TOKEN_KEY, result.token);
        }
        window.localStorage.setItem(USER_KEY, JSON.stringify(result.user));
      } else {
        updateToken(null);
        setUser(null);
        window.localStorage.removeItem(TOKEN_KEY);
        window.localStorage.removeItem(USER_KEY);
      }
      setLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, [token, updateToken]);

  const login = useCallback((newToken: string, newUser: UserInfo) => {
    updateToken(newToken);
    setUser(newUser);
    window.localStorage.setItem(TOKEN_KEY, newToken);
    window.localStorage.setItem(USER_KEY, JSON.stringify(newUser));
  }, [updateToken]);

  const logout = useCallback(() => {
    updateToken(null);
    setUser(null);
    window.localStorage.removeItem(TOKEN_KEY);
    window.localStorage.removeItem(USER_KEY);
  }, [updateToken]);

  const refreshUser = useCallback(async () => {
    if (!token) return;
    const currentToken = token;
    const result = await whoamiCall(currentToken);
    if (tokenRef.current !== currentToken) {
      return;
    }
    if (result) {
      setUser(result.user);
      if (result.token !== currentToken) {
        updateToken(result.token);
        window.localStorage.setItem(TOKEN_KEY, result.token);
      }
    }
  }, [token, updateToken]);

  const value: AuthContextType = useMemo(
    () => ({
      user,
      token,
      isLogin: !!user,
      loading: Boolean(token) && loading,
      login,
      logout,
      refreshUser,
    }),
    [user, token, loading, login, logout, refreshUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
