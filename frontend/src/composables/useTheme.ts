import { ref } from 'vue'

export const STORAGE_KEY = 'theme'

function readInitialDark(): boolean {
  try {
    if (typeof localStorage === 'undefined') return false
    return localStorage.getItem(STORAGE_KEY) === 'dark'
  } catch {
    return false
  }
}

const dark = ref(readInitialDark())

/**
 * 自定义 data-theme 与 Element Plus .dark 必须同步，否则会出现半黑半白。
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
