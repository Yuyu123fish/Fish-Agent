import { onMounted, onUnmounted, ref } from 'vue'

/**
 * 响应式断点检测。
 * mobile <= 767px, tablet 768-1199px, desktop >= 1200px。
 * 基于 matchMedia 监听视口变化，并在组件卸载时清理监听。
 */
export function useResponsive() {
  const isMobile = ref(false)
  const isTablet = ref(false)
  const isDesktop = ref(true)

  let mobileQuery: MediaQueryList | null = null
  let tabletQuery: MediaQueryList | null = null

  function update() {
    isMobile.value = mobileQuery?.matches ?? false
    isTablet.value = tabletQuery?.matches ?? false
    isDesktop.value = !isMobile.value && !isTablet.value
  }

  onMounted(() => {
    mobileQuery = window.matchMedia('(max-width: 767px)')
    tabletQuery = window.matchMedia('(min-width: 768px) and (max-width: 1199px)')
    update()
    mobileQuery.addEventListener('change', update)
    tabletQuery.addEventListener('change', update)
  })

  onUnmounted(() => {
    mobileQuery?.removeEventListener('change', update)
    tabletQuery?.removeEventListener('change', update)
  })

  return { isMobile, isTablet, isDesktop }
}
