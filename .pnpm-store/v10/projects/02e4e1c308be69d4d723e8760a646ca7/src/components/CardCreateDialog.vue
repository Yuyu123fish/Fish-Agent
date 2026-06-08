<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createCard, updateCard, type CardDetail, type CardPayload, type GroupTreeNode } from '@/api/card'

const props = defineProps<{
  visible: boolean
  editCard?: CardDetail | null
  groupTree: GroupTreeNode[]
}>()

const emit = defineEmits<{
  close: []
  saved: []
}>()

const title = ref('')
const content = ref('')
const cardType = ref<'concept' | 'topic'>('concept')
const selectedGroupPath = ref<(string | number)[]>([])
const keywords = ref<string[]>([])
const keywordInput = ref('')
const saving = ref(false)

const isEdit = computed(() => !!props.editCard)
const dialogTitle = computed(() => (isEdit.value ? '编辑知识卡片' : '创建知识卡片'))

/** 将树形数据转为 cascader options */
const cascaderOptions = computed(() => toCascaderOptions(props.groupTree))

function toCascaderOptions(nodes: GroupTreeNode[]): Array<{ value: number; label: string; children?: Array<{ value: number; label: string }> }> {
  if (!nodes?.length) return []
  return nodes.map((n) => ({
    value: n.id,
    label: n.name,
    children: n.children?.length ? toCascaderOptions(n.children) : undefined
  }))
}

watch(
  () => [props.visible, props.editCard] as const,
  () => {
    if (!props.visible) return
    const card = props.editCard
    title.value = card?.title ?? ''
    content.value = card?.content ?? ''
    cardType.value = card?.cardType ?? 'concept'
    selectedGroupPath.value = card?.groupId ? [card.groupId] : []
    keywords.value = [...(card?.keywords ?? [])]
    keywordInput.value = ''
  },
  { immediate: true }
)

function addKeyword() {
  const v = keywordInput.value.trim()
  if (!v) return
  if (!keywords.value.includes(v)) {
    keywords.value = [...keywords.value, v].slice(0, 8)
  }
  keywordInput.value = ''
}

function removeKeyword(kw: string) {
  keywords.value = keywords.value.filter((x) => x !== kw)
}

function buildPayload(): CardPayload {
  const path = selectedGroupPath.value
  const leafId = path.length > 0 ? Number(path[path.length - 1]) : null
  return {
    title: title.value.trim(),
    content: content.value.trim(),
    keywords: keywords.value,
    cardType: cardType.value,
    groupId: leafId && leafId > 0 ? leafId : null
  }
}

async function submit() {
  const payload = buildPayload()
  if (!payload.title) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!payload.content) {
    ElMessage.warning('请填写内容')
    return
  }
  saving.value = true
  try {
    if (props.editCard) {
      await updateCard(props.editCard.id, payload)
      ElMessage.success('卡片已更新')
    } else {
      await createCard(payload)
      ElMessage.success('卡片已创建')
    }
    emit('saved')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="560px"
    class="card-dialog"
    destroy-on-close
    @close="emit('close')"
  >
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="标题">
        <el-input v-model="title" maxlength="200" show-word-limit placeholder="例如：JVM 内存模型" />
      </el-form-item>
      <el-form-item label="内容">
        <el-input
          v-model="content"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 12 }"
          placeholder="支持 Markdown，写下这个知识点的核心内容"
        />
      </el-form-item>
      <el-form-item label="关键词">
        <div class="keyword-editor">
          <el-tag v-for="kw in keywords" :key="kw" closable @close="removeKeyword(kw)">
            {{ kw }}
          </el-tag>
          <el-input
            v-if="keywords.length < 8"
            v-model="keywordInput"
            class="keyword-input"
            placeholder="回车添加"
            @keyup.enter.prevent="addKeyword"
            @blur="addKeyword"
          />
        </div>
      </el-form-item>
      <div class="form-row">
        <el-form-item label="类型">
          <el-radio-group v-model="cardType">
            <el-radio-button label="concept">概念</el-radio-button>
            <el-radio-button label="topic">主题</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分组">
          <el-cascader
            v-model="selectedGroupPath"
            :options="cascaderOptions"
            :props="{ checkStrictly: true, emitPath: false }"
            filterable
            clearable
            placeholder="选择或搜索分组"
            class="group-cascader"
          />
        </el-form-item>
      </div>
    </el-form>

    <template #footer>
      <button class="ghost-btn" type="button" @click="emit('close')">取消</button>
      <button class="primary-btn" type="button" :disabled="saving" @click="submit">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </template>
  </el-dialog>
</template>

<style scoped>
.keyword-editor {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}

.keyword-input {
  width: 120px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 14px;
}

.group-cascader {
  width: 100%;
}

.ghost-btn,
.primary-btn {
  height: 34px;
  padding: 0 18px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.ghost-btn {
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
}

.primary-btn {
  border: 0;
  color: var(--bg-base);
  background: var(--accent);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 560px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
