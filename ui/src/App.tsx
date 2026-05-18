import { BrowserRouter } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import { UserProvider } from "./contexts/UserContext.tsx";

function App() {
  return (
    <BrowserRouter>
      <UserProvider>
        <ChatSessionsProvider>
          <JChatMindLayout />
        </ChatSessionsProvider>
      </UserProvider>
    </BrowserRouter>
  );
}

export default App;
