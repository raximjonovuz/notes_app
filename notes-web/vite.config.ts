import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    port: 3000,
    proxy: {
      '/auth': {
        target: 'https://notes-backend.raximjonozod1727.workers.dev',
        changeOrigin: true,
        secure: true,
      },
      '/notes': {
        target: 'https://notes-backend.raximjonozod1727.workers.dev',
        changeOrigin: true,
        secure: true,
      },
      '/upload': {
        target: 'https://notes-backend.raximjonozod1727.workers.dev',
        changeOrigin: true,
        secure: true,
      },
      '/files': {
        target: 'https://notes-backend.raximjonozod1727.workers.dev',
        changeOrigin: true,
        secure: true,
      },
    },
  },
})
