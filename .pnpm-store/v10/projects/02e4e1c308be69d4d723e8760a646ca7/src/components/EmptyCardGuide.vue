<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ChatLineRound, EditPen } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { drawFishLogo } from '@/utils/fishLogo'

const emit = defineEmits<{
  create: []
}>()

const router = useRouter()
const logoCanvas = ref<HTMLCanvasElement | null>(null)
const { dark } = useTheme()

function renderLogo() {
  if (!logoCanvas.value) return
  drawFishLogo(logoCanvas.value, 40, dark.value ? '#fafafa' : '#0a0a0a')
}

onMounted(renderLogo)
watch(dark, renderLogo)

function goChat() {
  void router.push('/chat')
}
</script>

<template>
  <section class="empty-guide">
    <canvas ref="logoCanvas" class="brain" aria-hidden="true" />
    <h2>知识卡片</h2>
    <p class="subtitle">把散落在对话和资料里的关键概念沉淀成可复用的个人知识。</p>

    <div class="actions">
      <button class="guide-card" type="button" @click="goChat">
        <el-icon><ChatLineRound /></el-icon>
        <span>从对话提取</span>
      </button>
      <button class="guide-card primary" type="button" @click="emit('create')">
        <el-icon><EditPen /></el-icon>
        <span>手动创建</span>
      </button>
    </div>

    <p class="tip">小贴士：阶段 1 支持手动创建，后续会接入 AI 自动提取和知识图谱。</p>
  </section>
</template>

<style scoped>
.empty-guide {
  min-height: 460px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 42px 20px;
  border: 1px dashed var(--border-bright);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.brain {
  width: 40px;
  height: 40px;
  margin-bottom: 12px;
  animation: fish-sway 8s ease-in-out infinite;
}

h2 {
  margin: 0;
  font-size: 28px;
  color: var(--text-primary);
}

.subtitle {
  max-width: 460px;
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 10px 0 28px;
}

.actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(160px, 1fr));
  gap: 14px;
  width: min(420px, 100%);
}

.guide-card {
  height: 86px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-surface);
  color: var(--text-primary);
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: transform var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.guide-card:hover {
  border-color: var(--border-bright);
  box-shadow: var(--shadow-md);
}

.guide-card.primary {
  background: var(--bg-hover);
}

.tip {
  margin: 22px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 520px) {
  .actions {
    grid-template-columns: 1fr;
  }
}

@keyframes fish-sway {
  0%,
  100% {
    transform: rotate(0deg);
  }

  25% {
    transform: rotate(2deg);
  }

  75% {
    transform: rotate(-2deg);
  }
}
</style>
