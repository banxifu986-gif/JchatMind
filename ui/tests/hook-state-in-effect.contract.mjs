import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const userContextSource = readFileSync(
  new URL("../src/contexts/UserContext.tsx", import.meta.url),
  "utf8",
);
const agentsSource = readFileSync(
  new URL("../src/hooks/useAgents.ts", import.meta.url),
  "utf8",
);

assert.doesNotMatch(
  userContextSource,
  /else\s*\{\s*setLoading\(false\);\s*\}/,
);
assert.match(
  userContextSource,
  /useState\(\(\) =>\s*Boolean\(window\.localStorage\.getItem\(TOKEN_KEY\)\),?\s*\)/,
);
assert.match(userContextSource, /const tokenRef = useRef\(token\);/);
assert.match(userContextSource, /if \(tokenRef\.current !== currentToken\) \{/);
assert.match(userContextSource, /loading: Boolean\(token\) && loading/);
assert.doesNotMatch(
  agentsSource,
  /if \(!isLogin\)\s*\{\s*setAgents\(\[\]\);\s*return;\s*\}/,
);
assert.match(agentsSource, /const userId = user\.userId;/);
assert.match(agentsSource, /agentState\.userId === user\?\.userId/);

const authEffect = userContextSource.slice(
  userContextSource.indexOf("  useEffect(() => {"),
  userContextSource.indexOf("  const login"),
);
assert.match(authEffect, /\}, \[token, updateToken\]\);/);
assert.match(authEffect, /if \(cancelled \|\| tokenRef\.current !== token\) \{/);

const chatViewSource = readFileSync(
  new URL("../src/components/views/AgentChatView.tsx", import.meta.url),
  "utf8",
);
const initEffect = chatViewSource.slice(
  chatViewSource.indexOf("if (!state?.init"),
  chatViewSource.indexOf("  const handleSendMessage"),
);
assert.match(
  initEffect,
  /\[state\?\.init, state\?\.initMessage, chatSessionId, agentId, getChatMessages, navigate, location\.pathname\]/,
);
