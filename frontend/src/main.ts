import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'highlight.js/styles/github-dark.css'
import App from './App.vue'
import { router } from './router'
import { applyTheme, STORAGE_KEY } from './composables/useTheme'
import './style.css'

/** 早于 createApp，避免暗色用户首帧闪白 */
try {
  if (typeof localStorage !== 'undefined' && localStorage.getItem(STORAGE_KEY) === 'dark') {
    applyTheme(true)
  }
} catch {
  /* localStorage 不可用，默认亮色 */
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')
