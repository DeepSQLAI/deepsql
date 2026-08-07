import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080'
// DeepSQL Agent chat service. The frontend reaches it at /agent-api/* which is
// rewritten to the agent's /* — a distinct prefix because both apps serve /api/*.
const agentProxyTarget = process.env.VITE_AGENT_PROXY_TARGET || 'http://localhost:8787'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  esbuild: {
    loader: 'jsx',
    include: /src\/.*\.(jsx?|tsx?)$/,
    exclude: [],
  },
  optimizeDeps: {
    esbuildOptions: {
      loader: {
        '.js': 'jsx',
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'charts': ['recharts'],
          'editor': ['@monaco-editor/react'],
        },
      },
    },
  },
  server: {
    port: 3000,
    open: false,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
        // LLM calls can take 60-120 s (multi-pass: schema + SQL + summarise).
        // Default http-proxy timeout is 30 s — raise both ends to 5 min so the
        // proxy never drops a connection before the backend finishes.
        timeout: 300000,
        proxyTimeout: 300000,
      },
      // Agent chat service: /agent-api/api/chat/stream → :8787/api/chat/stream
      '/agent-api': {
        target: agentProxyTarget,
        // Keep the browser Host (localhost:3000) so Hermes CSRF Origin checks
        // pass. changeOrigin:true rewrites Host to :8787 and profile/switch 403s.
        changeOrigin: false,
        rewrite: (p) => p.replace(/^\/agent-api/, ''),
        timeout: 300000,
        proxyTimeout: 300000,
      },
    },
  },
})
