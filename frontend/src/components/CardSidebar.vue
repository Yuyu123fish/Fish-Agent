<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ArrowRight, Files, Link, Search } from '@element-plus/icons-vue'
import type { GroupTreeNode } from '@/api/card'

const props = defineProps<{
  groupTree: GroupTreeNode[]
  currentGroupId: number | null
}>()

const emit = defineEmits<{
  select: [groupId: number | null, groupName?: string]
  discover: []
}>()

const groupKeyword = ref('')
const expandedIds = ref<Set<number>>(new Set())
const collapsed = ref(false)
const sidebarWidth = ref(220)
let dragging = false
let sidebarLeft = 0

const filteredTree = computed(() => filterGroups(props.groupTree, groupKeyword.value.trim().toLowerCase()))
const allCount = computed(() => props.groupTree.reduce((sum, node) => sum + totalCount(node), 0))

function totalCount(node: GroupTreeNode): number {
  return node.cardCount + (node.children ?? []).reduce((sum, child) => sum + totalCount(child), 0)
}

function filterGroups(nodes: GroupTreeNode[], keyword: string): GroupTreeNode[] {
  if (!keyword) return nodes
  return nodes
    .map((node) => {
      const children = filterGroups(node.children ?? [], keyword)
      const matched = node.name.toLowerCase().includes(keyword)
      return matched || children.length ? { ...node, children } : null
    })
    .filter((node): node is GroupTreeNode => Boolean(node))
}

function toggleExpand(id: number) {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

function isExpanded(id: number): boolean {
  return expandedIds.value.has(id) || Boolean(groupKeyword.value.trim())
}

function startDrag(e: MouseEvent) {
  if (collapsed.value) return
  e.preventDefault()
  dragging = true
  const rect = (e.currentTarget as HTMLElement)?.closest('.card-sidebar')?.getBoundingClientRect()
  sidebarLeft = rect?.left ?? 0
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
}

function onDrag(e: MouseEvent) {
  if (!dragging) return
  sidebarWidth.value = Math.min(280, Math.max(180, e.clientX - sidebarLeft))
}

function stopDrag() {
  dragging = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
}

onBeforeUnmount(stopDrag)
</script>

<template>
  <aside class="card-sidebar" :class="{ collapsed }" :style="{ width: collapsed ? '48px' : `${sidebarWidth}px` }">
    <div class="sidebar-icons">
      <button class="icon-only" type="button" title="分组" @click="collapsed = !collapsed">
        <el-icon><Files /></el-icon>
      </button>
      <button class="icon-only" type="button" title="发现关联" @click="emit('discover')">
        <el-icon><Link /></el-icon>
      </button>
    </div>

    <div class="sidebar-content">
      <header class="sidebar-head">
        <div>
          <span>分组</span>
          <strong>{{ allCount }}</strong>
        </div>
        <button class="collapse-btn" type="button" title="折叠侧栏" @click="collapsed = true">&lt;&lt;</button>
      </header>

      <el-input
        v-model="groupKeyword"
        class="group-search"
        clearable
        placeholder="搜索分组"
        :prefix-icon="Search"
      />

      <nav class="group-tree">
        <button
          type="button"
          class="group-node all"
          :class="{ active: currentGroupId == null }"
          @click="emit('select', null)"
        >
          <span class="node-title">全部分组</span>
          <span class="node-count">{{ allCount }}</span>
        </button>

        <template v-for="node in filteredTree" :key="node.id">
          <div class="node-wrap">
            <button
              type="button"
              class="group-node"
              :class="{ active: currentGroupId === node.id }"
              @click="emit('select', node.id, node.name)"
            >
              <el-icon
                v-if="node.children?.length"
                class="node-arrow"
                :class="{ expanded: isExpanded(node.id) }"
                @click.stop="toggleExpand(node.id)"
              >
                <ArrowRight />
              </el-icon>
              <span v-else class="node-spacer" />
              <span class="node-title">{{ node.name }}</span>
              <span class="node-count">{{ totalCount(node) }}</span>
            </button>

            <Transition name="dropdown">
              <div v-if="node.children?.length && isExpanded(node.id)" class="child-list">
                <button
                  v-for="child in node.children"
                  :key="child.id"
                  type="button"
                  class="group-node child"
                  :class="{ active: currentGroupId === child.id }"
                  @click="emit('select', child.id, child.name)"
                >
                  <span class="node-spacer" />
                  <span class="node-title">{{ child.name }}</span>
                  <span class="node-count">{{ child.cardCount }}</span>
                </button>
              </div>
            </Transition>
          </div>
        </template>
      </nav>

      <footer class="sidebar-actions">
        <button class="ghost-btn" type="button" @click="emit('discover')">
          <el-icon><Link /></el-icon>
          发现关联
        </button>
      </footer>
    </div>

    <button v-if="collapsed" class="expand-rail" type="button" title="展开侧栏" @click="collapsed = false">&gt;&gt;</button>
    <div class="resize-handle" @mousedown="startDrag" />
  </aside>
</template>

<style scoped>
.card-sidebar {
  position: relative;
  flex-shrink: 0;
  height: 100%;
  min-width: 48px;
  border-right: 1px solid var(--border);
  background: var(--bg-surface);
  display: flex;
  overflow: hidden;
}

.sidebar-content {
  width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 14px 12px;
}

.collapsed .sidebar-content {
  display: none;
}

.sidebar-icons {
  display: none;
  width: 48px;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding-top: 14px;
}

.collapsed .sidebar-icons {
  display: flex;
}

.icon-only,
.collapse-btn,
.expand-rail {
  border: 1px solid var(--border);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.icon-only {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
}

.icon-only:hover,
.collapse-btn:hover,
.expand-rail:hover {
  color: var(--text-primary);
  border-color: var(--border-bright);
  background: var(--bg-hover);
}

.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.sidebar-head span {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
}

.sidebar-head strong {
  color: var(--text-primary);
  font-size: 20px;
}

.collapse-btn {
  height: 28px;
  border-radius: var(--radius-sm);
}

.group-search {
  margin-bottom: 12px;
}

.group-tree {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.group-node {
  width: 100%;
  min-height: 34px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.group-node:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.group-node.active {
  color: var(--bg-base);
  background: var(--accent);
}

.group-node.child {
  padding-left: 18px;
}

.node-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-count {
  flex-shrink: 0;
  font-size: 11px;
  opacity: 0.72;
}

.node-arrow {
  flex-shrink: 0;
  font-size: 12px;
  transition: transform 0.15s ease;
}

.node-arrow.expanded {
  transform: rotate(90deg);
}

.node-spacer {
  width: 12px;
  flex-shrink: 0;
}

.child-list {
  margin: 2px 0 4px;
}

.sidebar-actions {
  padding-top: 12px;
}

.ghost-btn {
  width: 100%;
  height: 34px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-primary);
  background: transparent;
  cursor: pointer;
}

.expand-rail {
  position: absolute;
  left: 7px;
  bottom: 12px;
  width: 34px;
  height: 28px;
  border-radius: var(--radius-sm);
  font-size: 11px;
}

.resize-handle {
  position: absolute;
  top: 0;
  right: -3px;
  z-index: 5;
  width: 6px;
  height: 100%;
  cursor: col-resize;
}

.resize-handle:hover {
  background: var(--accent);
  opacity: 0.3;
}

.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 1199px) {
  .card-sidebar {
    width: 48px !important;
  }

  .sidebar-content {
    display: none;
  }

  .sidebar-icons {
    display: flex;
  }
}
</style>
