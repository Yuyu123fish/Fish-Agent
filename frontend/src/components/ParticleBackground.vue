<script setup lang="ts">
import { computed } from 'vue'
import { useTheme } from '@/composables/useTheme'

const { dark } = useTheme()

/** 深色模式：亮色粒子 + 发光连线（在 #0a0a14 上醒目） */
const darkOptions = {
  background: { color: 'transparent' },
  fpsLimit: 60,
  particles: {
    number: { value: 140, density: { enable: true, area: 700 } },
    color: { value: ['#a5b4fc', '#c4b5fd', '#ddd6fe', '#818cf8', '#e0e7ff'] },
    shape: { type: 'circle' },
    opacity: {
      value: { min: 0.4, max: 0.85 },
      animation: { enable: true, speed: 0.6, minimumValue: 0.3, sync: false }
    },
    size: {
      value: { min: 2, max: 5 },
      animation: { enable: true, speed: 2, minimumValue: 1, sync: false }
    },
    links: {
      enable: true,
      distance: 180,
      color: '#a5b4fc',
      opacity: 0.5,
      width: 1.5,
      shadow: { enable: true, color: '#818cf8', blur: 4 }
    },
    move: {
      enable: true,
      speed: 1.5,
      direction: 'none' as const,
      outModes: 'bounce' as const,
      random: true
    }
  },
  interactivity: {
    detectsOn: 'window' as const,
    events: {
      onHover: { enable: true, mode: 'grab' },
      onClick: { enable: true, mode: 'push' },
      resize: true
    },
    modes: {
      grab: { distance: 200, links: { opacity: 0.7, color: '#c4b5fd' } },
      push: { quantity: 3 }
    }
  },
  detectRetina: true
}

/** 亮色模式：深色粒子（在 #f0f2f5 上醒目） */
const lightOptions = {
  background: { color: 'transparent' },
  fpsLimit: 60,
  particles: {
    number: { value: 120, density: { enable: true, area: 800 } },
    color: { value: ['#4338ca', '#4f46e5', '#6366f1', '#312e81', '#3730a3'] },
    shape: { type: 'circle' },
    opacity: {
      value: { min: 0.25, max: 0.55 },
      animation: { enable: true, speed: 0.5, minimumValue: 0.15, sync: false }
    },
    size: {
      value: { min: 2, max: 4.5 },
      animation: { enable: true, speed: 1.5, minimumValue: 1, sync: false }
    },
    links: {
      enable: true,
      distance: 170,
      color: '#6366f1',
      opacity: 0.3,
      width: 1.2
    },
    move: {
      enable: true,
      speed: 1.2,
      direction: 'none' as const,
      outModes: 'bounce' as const,
      random: true
    }
  },
  interactivity: {
    detectsOn: 'window' as const,
    events: {
      onHover: { enable: true, mode: 'grab' },
      onClick: { enable: true, mode: 'push' },
      resize: true
    },
    modes: {
      grab: { distance: 200, links: { opacity: 0.5, color: '#4f46e5' } },
      push: { quantity: 3 }
    }
  },
  detectRetina: true
}

const options = computed(() => dark.value ? darkOptions : lightOptions)
</script>

<template>
  <vue-particles
    id="tsparticles"
    :options="options"
    class="particle-bg"
  />
</template>

<style scoped>
.particle-bg {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
}
</style>
