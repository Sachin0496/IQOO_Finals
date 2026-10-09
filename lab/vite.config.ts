import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import basicSsl from '@vitejs/plugin-basic-ssl'
import headers from './headers.json' with { type: 'json' }

// `npm run dev:lan` serves over HTTPS on the LAN so a phone's browser may open the camera.
export default defineConfig(({ mode }) => ({
  base: './',
  plugins: [react(), ...(mode === 'lan' ? [basicSsl()] : [])],
  optimizeDeps: { exclude: ['@mediapipe/tasks-vision', 'onnxruntime-web'] },
  // `npm run preview` serves the production build with the same headers as Vercel and Render.
  preview: { headers },
  // Never inline assets as data: URIs, so the CSP can stay at font-src/img-src 'self'.
  build: { assetsInlineLimit: 0 },
}))
