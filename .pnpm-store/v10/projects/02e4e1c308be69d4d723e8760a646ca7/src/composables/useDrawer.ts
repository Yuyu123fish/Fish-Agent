import { ref } from 'vue'

const open = ref(false)

export function useDrawer() {
  function openDrawer(): void {
    open.value = true
  }

  function closeDrawer(): void {
    open.value = false
  }

  function toggleDrawer(): void {
    open.value = !open.value
  }

  return { open, openDrawer, closeDrawer, toggleDrawer }
}
