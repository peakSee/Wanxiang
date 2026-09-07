import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  return {
    base: "./",
    plugins: [react()],
    build: {
      outDir: "dist",
      emptyOutDir: true,
      sourcemap: false,
      target: "es2022",
    },
    server: {
      proxy: {
        "/webchat/api": {
          target: env.VITE_WEBCHAT_PROXY_TARGET || "http://127.0.0.1:8080",
          changeOrigin: true,
        },
      },
    },
  };
});
