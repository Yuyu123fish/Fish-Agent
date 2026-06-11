import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const LoginView = () => import('@/views/LoginView.vue')
const ChatView = () => import('@/views/ChatView.vue')
const KnowledgeView = () => import('@/views/KnowledgeView.vue')
const KnowledgeCardView = () => import('@/views/KnowledgeCardView.vue')

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: ChatView, meta: { keepAlive: true } },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/cards', name: 'cards', component: KnowledgeCardView, meta: { requiresAuth: true } }
  ]
})

/**
 * 未登录禁止进入对话页；公开路由仅登录页。
 */
router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  const token = auth.getToken()
  if (to.meta.public) {
    if (token && to.path === '/login') {
      next('/chat')
      return
    }
    next()
    return
  }
  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})
