import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Library mode: a single ES module that registers <polaris-assistant>.
// customElement: true compiles the SFC styles into the shadow root.
export default defineConfig({
  plugins: [vue({ features: { customElement: true } })],
  define: { 'process.env.NODE_ENV': '"production"' },
  build: {
    lib: { entry: 'src/main.js', formats: ['es'], fileName: () => 'assistant.js' },
    cssCodeSplit: false,
  },
  test: { environment: 'jsdom', include: ['tests/**/*.test.js'] },
})
