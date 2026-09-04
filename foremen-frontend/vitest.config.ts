import { defineConfig, configDefaults } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
    // Keep vitest's default excludes and also skip the Playwright e2e specs,
    // which use @playwright/test and must run under Playwright's own runner.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
