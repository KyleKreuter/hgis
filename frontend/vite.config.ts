import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { tanstackRouter } from '@tanstack/router-plugin/vite'

export default defineConfig({
  plugins: [
    // Must run before the react plugin so generated route files are transformed too.
    tanstackRouter({ target: 'react', autoCodeSplitting: true }),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': new URL('./src', import.meta.url).pathname,
    },
  },
  optimizeDeps: {
    // MapLibre spawns its tile-parsing worker via `new Worker(new URL(...))`. Vite's
    // dependency pre-bundling rewrites that URL and the worker then never starts --
    // silently: raster tiles keep loading (main thread), vector tiles never arrive,
    // and no error is raised anywhere.
    exclude: ['maplibre-gl'],
  },
  server: {
    port: 5173,
    proxy: {
      // Keeps the browser on one origin during development, so tile requests and
      // uploads are not subject to CORS.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
