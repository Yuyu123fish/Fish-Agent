<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import * as authApi from '@/api/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const tab = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const nickname = ref('')
const loading = ref(false)

/**
 * 提交登录表单。
 */
async function onLogin() {
  loading.value = true
  try {
    const res = await authApi.login(username.value.trim(), password.value)
    auth.setSession(res.token!, res.userId, res.nickname, res.role)
    const redirect = (route.query.redirect as string) || '/chat'
    await router.replace(redirect)
  } catch (e: any) {
    ElMessage.error(e?.message ?? '登录失败')
  } finally {
    loading.value = false
  }
}

/**
 * 提交注册表单。
 */
async function onRegister() {
  loading.value = true
  try {
    const res = await authApi.register(username.value.trim(), password.value, nickname.value.trim() || undefined)
    auth.setSession(res.token!, res.userId, res.nickname, res.role)
    await router.replace('/chat')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="orb orb-1"></div>
    <div class="orb orb-2"></div>
    <el-card class="card" shadow="never">
      <div class="brand">
        <span class="brand-icon">🐟</span>
        <h1 class="title">Fish Agent</h1>
      </div>
      <el-tabs v-model="tab">
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent="onLogin">
            <el-form-item label="账号">
              <el-input v-model="username" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="password" type="password" autocomplete="current-password" show-password />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="loading" class="login-btn" style="width: 100%">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent="onRegister">
            <el-form-item label="账号">
              <el-input v-model="username" autocomplete="username" />
            </el-form-item>
            <el-form-item label="昵称（可选）">
              <el-input v-model="nickname" autocomplete="nickname" />
            </el-form-item>
            <el-form-item label="密码（至少 6 位）">
              <el-input v-model="password" type="password" autocomplete="new-password" show-password />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="loading" class="login-btn" style="width: 100%">
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--gradient-login-bg);
  position: relative;
  overflow: hidden;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.4;
  pointer-events: none;
}

.orb-1 {
  width: 400px;
  height: 400px;
  background: rgba(99, 102, 241, 0.35);
  top: -100px;
  right: -80px;
}

.orb-2 {
  width: 300px;
  height: 300px;
  background: rgba(168, 85, 247, 0.3);
  bottom: -60px;
  left: -60px;
}

.card {
  position: relative;
  z-index: 1;
  width: 400px;
  max-width: 92vw;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: var(--radius-xl);
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: var(--shadow-lg);
}

[data-theme='dark'] .card {
  background: rgba(30, 30, 35, 0.85);
  border-color: rgba(255, 255, 255, 0.08);
}

:deep(.el-card__body) {
  padding: 40px 36px;
}

.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 16px;
}

.brand-icon {
  font-size: 28px;
  animation: float 3s ease-in-out infinite;
}

.title {
  margin: 0;
  text-align: left;
  font-size: 1.5rem;
  font-weight: 600;
  background: var(--gradient-brand);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

:deep(.login-btn) {
  background: var(--gradient-brand) !important;
  border: none !important;
  height: 40px;
  font-size: 15px;
  font-weight: 500;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

:deep(.login-btn:hover) {
  opacity: 0.92;
  transform: translateY(-1px);
}

:deep(.login-btn:active) {
  transform: translateY(0);
}
</style>
