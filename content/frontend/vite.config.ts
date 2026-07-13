import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8089",
        changeOrigin: true,
      },
      "/graphql": {
        target: "http://localhost:8091",
        changeOrigin: true,
      },
      "/subscriptions": {
        target: "http://localhost:8091",
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
