import { useContext } from "react";
import { AuthContext } from "../contexts/UserContextBase.ts";
import type { AuthContextType } from "../contexts/UserContextBase.ts";

export function useUser(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useUser must be used within a UserProvider");
  }
  return context;
}
