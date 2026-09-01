import React from "react";

interface ContentProps {
  children: React.ReactNode;
}

const Content: React.FC<ContentProps> = ({ children }) => {
  return <main className="app-content h-full flex-1">{children}</main>;
};

export default Content;
