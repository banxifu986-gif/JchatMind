import React, { useEffect, useMemo, useState } from "react";
import {
  DEFAULT_USER_ID,
  STORAGE_KEY,
  UserContext,
} from "./UserContextBase.ts";

export function UserProvider({ children }: { children: React.ReactNode }) {
  const [userId, setUserIdState] = useState<string>(() => {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    return saved && saved.trim() ? saved : DEFAULT_USER_ID;
  });

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, userId);
  }, [userId]);

  const value = useMemo(
    () => ({
      userId,
      setUserId: (nextUserId: string) => {
        const normalized = nextUserId.trim();
        setUserIdState(normalized || DEFAULT_USER_ID);
      },
    }),
    [userId],
  );

  return <UserContext.Provider value={value}>{children}</UserContext.Provider>;
}
