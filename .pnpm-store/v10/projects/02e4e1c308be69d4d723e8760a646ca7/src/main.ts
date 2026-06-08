import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
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
app.config.errorHandler = (err, instance, info) => {
  console.error('[FishAgent] 全局异常:', err, info)
}
app.mount('#app')
