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
            <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">登录</el-button>
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
            <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">注册</el-button>
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
  background: var(--bg-page, #0f1419);
}
.card {
  width: 400px;
  max-width: 92vw;
  border: 1px solid var(--border);
  box-shadow: none;
  border-radius: 12px;
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
  font-size: 18px;
}
.title {
  margin: 0;
  text-align: left;
  font-size: 1.5rem;
  font-weight: 500;
}
</style>
