<script setup lang="ts">
/**
 * 知识卡片图谱视图。
 *
 * 组件只负责图谱数据加载、vis-network 渲染和图谱交互；详情打开交给父页面统一处理。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DataSet, Network, type Edge, type Node, type Options } from 'vis-network/standalone'
import 'vis-network/styles/vis-network.css'
import { ElMessage } from 'element-plus'
import { Aim, FullScreen, Refresh, View } from '@element-plus/icons-vue'
import { listAllCardRelations, listCards, type CardListItem, type ExtractRelation } from '@/api/card'
import { useTheme } from '@/composables/useTheme'

const props = defineProps<{
  groupId?: number | null
  refreshKey?: number
}>()

const emit = defineEmits<{
  openDetail: [cardId: number]
}>()

type RelationType = 'related_to' | 'contains' | 'precedes' | 'derived_from'

interface GraphNode extends Node {
  id: number
}

interface GraphEdge extends Edge {
  id: number
  relationType: RelationType
}

interface ClickParams {
  nodes: number[]
  pointer: {
    DOM: { x: number; y: number }
  }
}

const relationOptions: Array<{ label: string; value: RelationType }> = [
  { label: '相关', value: 'related_to' },
  { label: '包含', value: 'contains' },
  { label: '前置', value: 'precedes' },
  { label: '衍生', value: 'derived_from' }
]

const graphRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const cards = ref<CardListItem[]>([])
const relations = ref<ExtractRelation[]>([])
const activeRelationTypes = ref<RelationType[]>(relationOptions.map((item) => item.value))
const selectedCard = ref<CardListItem | null>(null)
const popup = ref({ visible: false, x: 0, y: 0 })
const { dark } = useTheme()

let network: Network | null = null
let nodesData: DataSet<GraphNode> | null = null
let edgesData: DataSet<GraphEdge> | null = null

const hasGraphData = computed(() => cards.value.length > 0)
const activeTypeSet = computed(() => new Set(activeRelationTypes.value))

const palette = computed(() => {
  if (dark.value) {
    return {
      topicBg: '#7c3aed',
      topicBorder: '#8b5cf6',
      conceptBg: '#4f46e5',
      conceptBorder: '#6366f1',
      highlightBg: '#a78bfa',
      highlightBorder: '#c4b5fd',
      hoverBg: '#818cf8',
      hoverBorder: '#a5b4fc',
      font: '#e8e8ea',
      related: '#6b7280',
      contains: '#818cf8',
      precedes: '#a78bfa',
      derived: '#34d399'
    }
  }
  return {
    topicBg: '#c4b5fd',
    topicBorder: '#7c3aed',
    conceptBg: '#bfdbfe',
    conceptBorder: '#2563eb',
    highlightBg: '#ddd6fe',
    highlightBorder: '#6d28d9',
    hoverBg: '#dbeafe',
    hoverBorder: '#1d4ed8',
    font: '#111827',
    related: '#4b5563',
    contains: '#4f46e5',
    precedes: '#7c3aed',
    derived: '#059669'
  }
})

onMounted(async () => {
  await loadGraph()
})

onBeforeUnmount(() => {
  destroyNetwork()
})

watch(dark, () => {
  renderNetwork()
})

watch(activeRelationTypes, () => {
  updateEdgeVisibility()
})

watch(() => props.groupId, () => {
  loadGraph()
})

watch(() => props.refreshKey, () => {
  loadGraph()
})

async function loadGraph() {
  loading.value = true
  popup.value.visible = false
  try {
    const [cardPage, relationRows] = await Promise.all([
      listCards({
        page: 1,
        size: 9999,
        status: 'confirmed',
        groupId: props.groupId && props.groupId > 0 ? props.groupId : undefined
      }),
      listAllCardRelations()
    ])
    cards.value = cardPage.records
    relations.value = relationRows
    await nextTick()
    renderNetwork()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载图谱失败')
  } finally {
    loading.value = false
  }
}

function renderNetwork() {
  if (!canvasRef.value) return
  destroyNetwork()
  if (!hasGraphData.value) return

  nodesData = new DataSet(cards.value.map(toNode))
  edgesData = new DataSet(buildEdges())
  network = new Network(canvasRef.value, { nodes: nodesData, edges: edgesData }, buildOptions())
  bindNetworkEvents()
}

function bindNetworkEvents() {
  if (!network) return
  network.on('click', (params: ClickParams) => {
    const id = params.nodes?.[0]
    if (!id) {
      popup.value.visible = false
      selectedCard.value = null
      return
    }
    selectedCard.value = cards.value.find((card) => card.id === Number(id)) ?? null
    popup.value = {
      visible: !!selectedCard.value,
      x: Math.min(params.pointer.DOM.x + 12, Math.max(260, (graphRef.value?.clientWidth ?? 360) - 280)),
      y: Math.max(54, params.pointer.DOM.y - 18)
    }
  })
  network.on('doubleClick', (params: ClickParams) => {
    const id = params.nodes?.[0]
    if (id) emit('openDetail', Number(id))
  })
}

function destroyNetwork() {
  if (network) {
    network.destroy()
    network = null
  }
  nodesData = null
  edgesData = null
}

function toNode(card: CardListItem): GraphNode {
  const isTopic = card.cardType === 'topic'
  const p = palette.value
  return {
    id: card.id,
    label: truncate(card.title, 10),
    // 不设 title，禁用原生浏览器 tooltip
    shape: 'dot',
    size: isTopic ? 36 : 22,
    color: {
      background: isTopic ? p.topicBg : p.conceptBg,
      border: isTopic ? p.topicBorder : p.conceptBorder,
      highlight: { background: p.highlightBg, border: p.highlightBorder },
      hover: { background: p.hoverBg, border: p.hoverBorder }
    },
    font: {
      color: p.font,
      size: isTopic ? 13 : 11,
      face: 'system-ui, -apple-system, sans-serif',
      strokeWidth: 3,
      strokeColor: dark.value ? 'rgba(10,10,20,0.7)' : 'rgba(255,255,255,0.85)'
    },
    borderWidth: isTopic ? 3 : 2,
    shadow: {
      enabled: true,
      color: isTopic ? 'rgba(124,58,237,0.35)' : 'rgba(79,70,229,0.25)',
      size: isTopic ? 12 : 8,
      x: 0,
      y: 0
    }
  }
}

function buildEdges(): GraphEdge[] {
  const nodeIds = new Set(cards.value.map((card) => card.id))
  const seen = new Set<string>()
  return relations.value
    .filter((rel) => nodeIds.has(rel.fromCardId) && nodeIds.has(rel.toCardId))
    .filter((rel) => {
      const key = `${rel.fromCardId}:${rel.toCardId}:${rel.relationType}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .map(toEdge)
}

function toEdge(rel: ExtractRelation): GraphEdge {
  const type = normalizeRelationType(rel.relationType)
  const style = edgeStyle(type)
  return {
    id: rel.id,
    from: rel.fromCardId,
    to: rel.toCardId,
    relationType: type,
    color: { color: style.color, highlight: style.color, hover: style.color },
    dashes: style.dashes,
    arrows: style.arrow ? 'to' : undefined,
    width: 1.5,
    smooth: { enabled: true, type: 'continuous', roundness: 0 },
    hidden: !activeTypeSet.value.has(type)
  }
}

function edgeStyle(type: RelationType) {
  const p = palette.value
  if (type === 'contains') return { color: p.contains, dashes: false, arrow: false }
  if (type === 'precedes') return { color: p.precedes, dashes: false, arrow: true }
  if (type === 'derived_from') return { color: p.derived, dashes: false, arrow: true }
  return { color: p.related, dashes: [5, 5], arrow: false }
}

function buildOptions(): Options {
  return {
    autoResize: true,
    nodes: {
      scaling: { min: 16, max: 34 }
    },
    edges: {
      selectionWidth: 2,
      hoverWidth: 2
    },
    physics: {
      enabled: true,
      solver: 'forceAtlas2Based',
      forceAtlas2Based: {
        gravitationalConstant: -80,
        springLength: 150,
        springConstant: 0.08
      },
      stabilization: { iterations: 200 }
    },
    interaction: {
      hover: true,
      tooltipDelay: 999999,
      navigationButtons: false,
      keyboard: false
    }
  }
}

function updateEdgeVisibility() {
  if (!edgesData) return
  const active = activeTypeSet.value
  edgesData.update(
    edgesData.get().map((edge) => ({
      id: edge.id,
      hidden: !active.has(edge.relationType)
    }))
  )
}

function fitGraph() {
  network?.fit({ animation: { duration: 450, easingFunction: 'easeInOutQuad' } })
}

async function toggleFullscreen() {
  const target = graphRef.value
  if (!target) return
  if (!document.fullscreenElement) {
    await target.requestFullscreen?.()
  } else {
    await document.exitFullscreen?.()
  }
}

function openSelectedDetail() {
  if (selectedCard.value) emit('openDetail', selectedCard.value.id)
}

function normalizeRelationType(raw: string): RelationType {
  if (raw === 'contains' || raw === 'precedes' || raw === 'derived_from') return raw
  return 'related_to'
}

function relationLabel(type: RelationType): string {
  if (type === 'contains') return '包含'
  if (type === 'precedes') return '前置'
  if (type === 'derived_from') return '衍生'
  return '相关'
}

function truncate(text: string, max: number): string {
  const raw = text?.trim() ?? ''
  return raw.length > max ? `${raw.slice(0, max)}...` : raw
}
</script>

<template>
  <section ref="graphRef" class="graph-panel">
    <header class="graph-toolbar">
      <div class="type-filters">
        <el-checkbox-group v-model="activeRelationTypes">
          <el-checkbox v-for="item in relationOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <div class="graph-actions">
        <button class="icon-btn" type="button" title="适配视图" @click="fitGraph">
          <el-icon><Aim /></el-icon>
        </button>
        <button class="icon-btn" type="button" title="刷新图谱" @click="loadGraph">
          <el-icon><Refresh /></el-icon>
        </button>
        <button class="icon-btn" type="button" title="全屏" @click="toggleFullscreen">
          <el-icon><FullScreen /></el-icon>
        </button>
      </div>
    </header>

    <div v-loading="loading" class="graph-stage">
      <div v-if="!loading && !hasGraphData" class="graph-empty">
        <el-icon><View /></el-icon>
        <span>暂无已确认卡片，确认卡片后即可生成知识图谱</span>
      </div>
      <div v-show="hasGraphData" ref="canvasRef" class="network-canvas" />

      <Transition name="popup">
        <aside
          v-if="popup.visible && selectedCard"
          class="node-popup"
          :style="{ left: `${popup.x}px`, top: `${popup.y}px` }"
        >
          <strong>{{ selectedCard.title }}</strong>
          <p>{{ truncate(selectedCard.contentPreview, 86) }}</p>
          <div class="popup-meta">
            <span>{{ selectedCard.cardType === 'topic' ? '主题' : '概念' }}</span>
            <span>{{ selectedCard.groupName || '未分组' }}</span>
          </div>
          <button class="detail-btn" type="button" @click="openSelectedDetail">查看详情</button>
        </aside>
      </Transition>
    </div>

    <footer class="legend">
      <span v-for="item in relationOptions" :key="item.value" class="legend-item">
        <i :class="`line ${item.value}`"></i>
        {{ relationLabel(item.value) }}
      </span>
    </footer>
  </section>
</template>

<style scoped>
.graph-panel {
  position: relative;
  min-height: 620px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(52, 211, 153, 0.05)),
    rgba(10, 10, 20, 0.28);
  overflow: hidden;
}

.graph-panel:fullscreen {
  width: 100vw;
  height: 100vh;
  border-radius: 0;
  background: var(--bg-chat-area);
}

.graph-toolbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-glass);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

.type-filters {
  min-width: 0;
}

.graph-actions {
  display: flex;
  gap: 8px;
}

.icon-btn {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.icon-btn:hover {
  color: var(--primary-light);
  border-color: var(--primary);
}

.graph-stage {
  position: relative;
  height: calc(100vh - 340px);
  min-height: 420px;
}

.graph-panel:fullscreen .graph-stage {
  height: calc(100vh - 92px);
  min-height: unset;
}

.network-canvas {
  width: 100%;
  height: 100%;
}

.graph-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  color: var(--text-secondary);
}

.graph-empty .el-icon {
  font-size: 32px;
  color: var(--primary-light);
}

.node-popup {
  position: absolute;
  z-index: 3;
  width: 260px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-glass-heavy);
  backdrop-filter: var(--glass-blur-heavy);
  -webkit-backdrop-filter: var(--glass-blur-heavy);
  box-shadow: var(--shadow-lg);
}

.node-popup strong {
  display: block;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 1.45;
}

.node-popup p {
  margin: 8px 0;
  color: var(--text);
  font-size: 12px;
  line-height: 1.65;
}

.popup-meta {
  display: flex;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;
  margin-bottom: 10px;
}

.detail-btn {
  width: 100%;
  height: 30px;
  border: 0;
  border-radius: var(--radius-sm);
  color: #fff;
  background: var(--gradient-brand);
  cursor: pointer;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.line {
  width: 24px;
  height: 0;
  border-top: 2px solid #6b7280;
}

.line.related_to {
  border-top-style: dashed;
}

.line.contains {
  border-color: #818cf8;
}

.line.precedes {
  border-color: #a78bfa;
}

.line.derived_from {
  border-color: #34d399;
}

.popup-enter-active,
.popup-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.popup-enter-from,
.popup-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media (max-width: 760px) {
  .graph-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .graph-actions {
    justify-content: flex-end;
  }

  .graph-stage {
    height: 460px;
  }
}
</style>
