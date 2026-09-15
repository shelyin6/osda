import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * The build output is written straight into the Spring Boot static resources so the packaged
 * jar serves the UI offline, without any CDN or node runtime on the target machine.
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
