import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ command }) => ({
  plugins: [
    vue(),
    AutoImport({
      // 生产构建前已经执行 vue-tsc；build 阶段不再写声明文件，避免 Windows 文件锁导致构建偶发失败。
      dts: command === 'serve',
      resolvers: [ElementPlusResolver()]
    }),
    Components({
      // 开发服务仍自动刷新组件声明；生产构建只消费现有声明，保持构建过程只读且更稳定。
      dts: command === 'serve',
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // SSE 走 /api 代理到后端 8080；changeOrigin 必须开启
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
}))
