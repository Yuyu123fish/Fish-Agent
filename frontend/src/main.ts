import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import Particles from '@tsparticles/vue3'
import { loadSlim } from '@tsparticles/slim'
import 'element-plus/dist/index.css'
import 'highlight.js/styles/github-dark.css'
import App from './App.vue'
import { router } from './router'
import './style.css'

/** 初始化主题：读取 localStorage，默认深色。 */
import { applyTheme, readInitialDark } from './composables/useTheme'
applyTheme(readInitialDark())

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.use(Particles, {
  /** 只加载 slim 预设，控制首屏粒子背景的包体和初始化成本。 */
  init: async (engine) => {
    await loadSlim(engine)
  }
})
app.config.errorHandler = (err, instance, info) => {
  console.error('[FishAgent] 全局异常:', err, info)
}
app.mount('#app')
