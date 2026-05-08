<script setup lang="ts">
import { onUnmounted } from 'vue'
import { useChatStore } from '@/store/chat'
import SessionSidebar from '@/components/SessionSidebar.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'

const store = useChatStore()

onUnmounted(() => {
  store.cleanup()
})
</script>

<template>
  <div class="layout">
    <SessionSidebar />
    <main class="main">
      <MessageList />
      <ChatInput />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  width: 100vw;
  background: var(--bg-page);
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-main);
  min-width: 0;
}
</style>
