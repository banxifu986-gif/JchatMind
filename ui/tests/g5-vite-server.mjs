import { createServer } from "vite";

const port = Number(process.env.G5_UI_PORT);
if (!Number.isInteger(port) || port < 49152 || port > 65535) {
  throw new Error("An isolated high UI port is required");
}
const backendUrl = new URL(process.env.G5_CHAT_HTTP_URL);
if (backendUrl.protocol !== "http:" || backendUrl.hostname !== "127.0.0.1"
  || Number(backendUrl.port) < 49152 || Number(backendUrl.port) > 65535) {
  throw new Error("An isolated loopback backend URL is required");
}

const server = await createServer({
  envDir: false,
  server: {
    host: "127.0.0.1",
    port,
    strictPort: true,
    proxy: {
      "/mock-api/chat-messages": {
        target: backendUrl.origin,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/mock-api/, "/api"),
      },
      "/mock-sse": {
        target: backendUrl.origin,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/mock-sse/, "/sse"),
      },
    },
  },
});
await server.listen();
process.stdout.write("G5_VITE_LISTENING\n");

process.on("SIGTERM", () => {
  void server.close().then(() => process.exit(0));
});
