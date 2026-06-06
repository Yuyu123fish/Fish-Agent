import { ref } from 'vue'

export const STORAGE_KEY = 'theme'

export function readInitialDark(): boolean {
  try {
    if (typeof localStorage === 'undefined') return true
    const stored = localStorage.getItem(STORAGE_KEY)
    // 默认深色：没有存储或显式为 'dark' 都走深色
    return stored !== 'light'
  } catch {
    return true
  }
}

const dark = ref(readInitialDark())

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === STORAGE_KEY && e.newValue) {
      const isDark = e.newValue === 'dark'
      if (isDark !== dark.value) applyTheme(isDark)
    }
  })
}

/**
 * 同步 data-theme 属性与 Element Plus .dark class。
 */
export function applyTheme(isDark: boolean): void {
  if (typeof document === 'undefined') return
  document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light')
  document.documentElement.classList.toggle('dark', isDark)
  try {
    localStorage.setItem(STORAGE_KEY, isDark ? 'dark' : 'light')
  } catch {
    /* ignore quota / private mode */
  }
  dark.value = isDark
}

export function useTheme() {
  function toggle(): void {
    applyTheme(!dark.value)
  }

  function setDark(isDark: boolean): void {
    applyTheme(isDark)
  }

  return { dark, toggle, setDark, applyTheme }
}
