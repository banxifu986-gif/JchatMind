import React from "react";

interface SidebarProps {
  children: React.ReactNode;
  collapsed?: boolean;
}

const Sidebar: React.FC<SidebarProps> = ({ children, collapsed = false }) => {
  return (
    <div
      className={`app-sidebar h-full ${collapsed ? "is-collapsed" : ""}`}
    >
      {children}
    </div>
  );
};

export default Sidebar;
