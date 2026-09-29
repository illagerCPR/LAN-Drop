import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// 开发模式：vite 跑在 5173，/api（含 WebSocket）代理到本机服务端 8787；
// 生产模式：`pnpm --filter @lan-drop/web build` 产出 apps/web/dist，
// 由服务端 @fastify/static 直接托管（app.ts 检测到 dist/index.html 即自动切换），同源无跨域。
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8787",
        ws: true,
      },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
});
