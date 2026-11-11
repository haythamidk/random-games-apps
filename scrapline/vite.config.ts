import { defineConfig } from 'vitest/config';

export default defineConfig({
  // Relative base so the same build works from a sub-path URL and inside the Android WebView.
  base: './',
  build: { target: 'es2022', chunkSizeWarningLimit: 900 },
  test: { include: ['tests/**/*.test.ts'], environment: 'node' },
});
