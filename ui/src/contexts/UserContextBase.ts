import { createContext } from "react";

export interface UserInfo {
  userId: number;
  account: string;
  username: string;
  avatarUrl?: string;
  isAdmin: number;
  email?: string;
}

export interface AuthContextType {
  user: UserInfo | null;
  token: string | null;
  isLogin: boolean;
  loading: boolean;
  login: (token: string, user: UserInfo) => void;
  logout: () => void;
  refreshUser: () => Promise<void>;
}

export const TOKEN_KEY = "jchatmind.token";
export const USER_KEY = "jchatmind.user";

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

// 兼容旧引用
export const UserContext = AuthContext;
export const DEFAULT_USER_ID = "demo-user";
export const STORAGE_KEY = "jchatmind.userId";
