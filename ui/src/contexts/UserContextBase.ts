import { createContext } from "react";

export interface UserContextType {
  userId: string;
  setUserId: (userId: string) => void;
}

export const DEFAULT_USER_ID = "demo-user";
export const STORAGE_KEY = "jchatmind.userId";

export const UserContext = createContext<UserContextType | undefined>(undefined);
