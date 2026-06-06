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

function switchTab() {
  tab.value = tab.value === 'login' ? 'register' : 'login'
}

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

function onSubmit() {
  if (tab.value === 'login') void onLogin()
  else void onRegister()
}
</script>

<template>
  <div class="login-page">
    <div class="card">
      <div class="brand">
        <span class="brand-icon">🐟</span>
        <h1 class="title">Fish Agent</h1>
      </div>

      <Transition name="form-switch" mode="out-in">
        <el-form v-if="tab === 'login'" key="login" label-position="top" @submit.prevent="onSubmit">
          <el-form-item label="账号">
            <el-input v-model="username" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="password" type="password" autocomplete="current-password" show-password />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="submit-btn">
            登录
          </el-button>
          <p class="switch-text">
            没有账号？<button type="button" class="link" @click="switchTab">去注册</button>
          </p>
        </el-form>

        <el-form v-else key="register" label-position="top" @submit.prevent="onSubmit">
          <el-form-item label="账号">
            <el-input v-model="username" autocomplete="username" />
          </el-form-item>
          <el-form-item label="昵称（可选）">
            <el-input v-model="nickname" autocomplete="nickname" />
          </el-form-item>
          <el-form-item label="密码（至少 6 位）">
            <el-input v-model="password" type="password" autocomplete="new-password" show-password />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="submit-btn">
            注册
          </el-button>
          <p class="switch-text">
            已有账号？<button type="button" class="link" @click="switchTab">去登录</button>
          </p>
        </el-form>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  z-index: 1;
}

.card {
  width: 400px;
  max-width: 92vw;
  padding: 40px 36px;
  border-radius: var(--radius-xl);
  background: var(--bg-glass);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
}

.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 24px;
}

.brand-icon {
  font-size: 28px;
  animation: float 3s ease-in-out infinite;
}

.title {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  background: var(--gradient-brand);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.submit-btn {
  width: 100%;
  background: var(--gradient-brand) !important;
  border: none !important;
  height: 40px;
  font-size: 15px;
  font-weight: 500;
  transition: opacity var(--transition-fast), transform var(--transition-fast), box-shadow var(--transition-fast);
}

.submit-btn:hover {
  opacity: 0.92;
  transform: translateY(-1px);
  box-shadow: 0 4px 24px rgba(99, 102, 241, 0.4);
}

.submit-btn:active {
  transform: translateY(0);
}

.switch-text {
  text-align: center;
  margin: 16px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.link {
  background: none;
  border: none;
  color: var(--primary-light);
  cursor: pointer;
  font-size: 13px;
  padding: 0;
}

.link:hover {
  text-decoration: underline;
}

/* 表单切换动画 */
.form-switch-enter-active,
.form-switch-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.form-switch-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.form-switch-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
