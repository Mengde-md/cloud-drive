import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 开发期把 /api 代理到后端 8080。
    // 这样浏览器看到的始终是同源请求，完全不涉及 CORS——
    // 后端因此不需要为了本地联调而开放跨域，生产环境用 Nginx 做同样的事。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    // 课程项目不需要体积优化到极致，关掉 sourcemap 让 dist 小一点
    sourcemap: false
  }
})
