<script setup lang="ts">
import { onUnmounted } from 'vue'
import { useChatStore } from '@/store/chat'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'

const store = useChatStore()

onUnmounted(() => {
  store.cleanup()
})
</script>

<template>
  <div class="layout">
    <AppHeader />
    <DrawerSidebar />
    <main class="main">
      <MessageList />
      <ChatInput />
    </main>
  </div>
</template>

<style scoped>
.layout {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100vh;
  width: 100vw;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding-top: 48px;
  background: var(--bg-chat-area);
}
</style>
