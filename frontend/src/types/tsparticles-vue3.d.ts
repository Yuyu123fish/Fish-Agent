declare module '@tsparticles/vue3' {
  import type { App } from 'vue'
  import type { Engine } from '@tsparticles/engine'

  export interface ParticlesPluginOptions {
    /** 初始化 tsParticles 引擎；当前项目只加载 slim 预设。 */
    init?: (engine: Engine) => Promise<void> | void
  }

  const Particles: (app: App, options?: ParticlesPluginOptions) => void
  export default Particles
}
