<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ChatLineRound, EditPen } from '@element-plus/icons-vue'

const emit = defineEmits<{
  create: []
}>()

const router = useRouter()

function goChat() {
  void router.push('/chat')
}
</script>

<template>
  <section class="empty-guide">
    <div class="brain">🧠</div>
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
  border-radius: var(--radius-lg);
  background: rgba(15, 15, 25, 0.42);
}

.brain {
  font-size: 52px;
  margin-bottom: 12px;
  animation: float 3s ease-in-out infinite;
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
  background: rgba(20, 20, 35, 0.72);
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
  transform: translateY(-2px);
  border-color: var(--primary);
  box-shadow: 0 8px 26px rgba(99, 102, 241, 0.22);
}

.guide-card.primary {
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.34), rgba(124, 58, 237, 0.24));
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
</style>
