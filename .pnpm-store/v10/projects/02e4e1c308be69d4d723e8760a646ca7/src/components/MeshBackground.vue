<script setup lang="ts">
/**
 * Antigravity 风格粒子星座背景 (Three.js)。
 *
 *   - 粒子铺满全屏（按相机可视范围分布）
 *   - 三色随时间流动：蓝 / 红 / 黄
 *   - 连线 120px（世界坐标）
 *   - 鼠标附近轻微吸引 + 放大
 *   - 缓慢漂浮 + 呼吸
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import PoissonDiskSampling from 'poisson-disk-sampling'
import { useTheme } from '@/composables/useTheme'

/* ── 配色 ───────────────────────────────────────────── */

const DARK_COLORS = ['#7189ff', '#f84242', '#ffcf03']
const LIGHT_COLORS = ['#2c64ed', '#f84242', '#ffcf03']
const DARK_BG = 0x0a0a0a
const LIGHT_BG = 0xfafafa

/* ── Shader ─────────────────────────────────────────── */

const pointVert = /* glsl */ `
  attribute float aPhase;
  attribute float aBreathSpeed;
  attribute float aRotation;

  uniform float uTime;
  uniform float uSize;
  uniform vec2 uMouse;
  uniform float uMouseActive;
  uniform float uDpr;
  uniform vec3 uColor1;
  uniform vec3 uColor2;
  uniform vec3 uColor3;
  uniform float uBoundsX;
  uniform float uBoundsY;

  varying vec3 vColor;
  varying float vAlpha;
  varying float vMorph;      // 0=圆 1=菱形，由鼠标距离驱动
  varying float vRotation;

  void main() {
    vec3 pos = position;

    // 鼠标接近度
    vec2 toMouse = uMouse - pos.xy;
    float mDist = length(toMouse);
    float mFx = smoothstep(0.6, 0.0, mDist) * uMouseActive;
    pos.xy += toMouse * mFx * 0.25;

    // 呼吸：靠近鼠标时更快更剧烈
    float breathAmp = 0.12 + mFx * 0.18;
    float breathSpd = aBreathSpeed * (1.0 + mFx * 1.5);
    float breath = 1.0 + sin(uTime * breathSpd + aPhase) * breathAmp;

    vec4 mv = modelViewMatrix * vec4(pos, 1.0);
    float s = uSize * uDpr * breath * (1.0 + mFx * 1.6);
    gl_PointSize = max(1.0, s);
    gl_Position = projectionMatrix * mv;

    // 颜色
    float nx = (pos.x / uBoundsX + 1.0) * 0.5;
    float colorSpeed = 0.4 + mFx * 0.6;
    float cp = uTime * colorSpeed + aPhase * 6.2832 + nx * 2.0;
    float w1 = sin(cp) * 0.5 + 0.5;
    float w2 = sin(cp + 2.094) * 0.5 + 0.5;
    float w3 = sin(cp + 4.189) * 0.5 + 0.5;
    float sum = max(w1 + w2 + w3, 0.001);
    vec3 col = (uColor1 * w1 + uColor2 * w2 + uColor3 * w3) / sum;
    col = mix(col, col + 0.15, mFx);

    vColor = col;
    vAlpha = 0.45 + breath * 0.1 + mFx * 0.45;
    // morph：圆(2) → 菱形(1)，由鼠标距离驱动
    vMorph = mFx;
    // 旋转：靠近鼠标加速
    vRotation = aRotation + uTime * (0.15 + aPhase * 0.08 + mFx * 2.5);
  }
`

const pointFrag = /* glsl */ `
  varying vec3 vColor;
  varying float vAlpha;
  varying float vMorph;
  varying float vRotation;

  void main() {
    vec2 uv = gl_PointCoord - 0.5;

    // 旋转
    float c = cos(vRotation);
    float s = sin(vRotation);
    vec2 ruv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);

    // 超椭圆指数：n=2 圆形，n=1 菱形，中间值丝滑过渡
    float n = mix(2.0, 1.0, vMorph);
    // 超椭圆 SDF: |x|^n + |y|^n
    float ax = abs(ruv.x);
    float ay = abs(ruv.y);
    float sd = pow(ax, n) + pow(ay, n);

    // 核心形状
    float core = smoothstep(0.5, 0.18, sd);

    // 鼠标附近外圈光环
    float halo = smoothstep(0.55, 0.25, sd) * smoothstep(0.25, 0.08, sd) * vMorph * 0.7;

    float alpha = core + halo;
    if (alpha < 0.01) discard;
    gl_FragColor = vec4(vColor, alpha * vAlpha);
  }
`

/* ── 连线 Shader ────────────────────────────────────── */

const lineVert = /* glsl */ `
  attribute float aAlpha;
  varying float vAlpha;
  void main() {
    vAlpha = aAlpha;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`

const lineFrag = /* glsl */ `
  uniform vec3 uLineColor;
  varying float vAlpha;
  void main() {
    gl_FragColor = vec4(uLineColor, vAlpha);
  }
`

/* ── 常量 ───────────────────────────────────────────── */

const FOV = 40
const CAMERA_Z = 3.1
const CONNECT_DIST = 0.18
const MAX_LINES = 1200

/* ── 组件 ───────────────────────────────────────────── */

const containerRef = ref<HTMLElement | null>(null)
const { dark } = useTheme()

let scene: THREE.Scene
let camera: THREE.PerspectiveCamera
let renderer: THREE.WebGLRenderer
let clock: THREE.Clock
let animationId = 0
let paused = false

// 可视范围
let boundsX = 1
let boundsY = 1

// 粒子
let pointsMesh: THREE.Points
let pointMat: THREE.ShaderMaterial

// 连线
let lineGeom: THREE.BufferGeometry
let lineMesh: THREE.LineSegments
let lineMat: THREE.ShaderMaterial
let linePositions: Float32Array
let lineAlphas: Float32Array

// 粒子数据
let basePositions: Float32Array
let particlePhases: Float32Array
let particleBreathSpeeds: Float32Array
let particleVelocities: Float32Array
let particleShapeTypes: Float32Array // kept for resize rebuild
let particleRotations: Float32Array
let particleCount = 0

// 鼠标
let mouseNDC = new THREE.Vector2(999, 999)
let smoothMouseWorld = new THREE.Vector2(999, 999)
let mouseActive = 0
let raycaster = new THREE.Raycaster()
let hitPlane: THREE.Mesh

onMounted(() => {
  init()
  window.addEventListener('resize', handleResize)
  document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener('mousemove', handleMouseMove, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener('mousemove', handleMouseMove)
  if (animationId) cancelAnimationFrame(animationId)
  dispose()
})

watch(dark, () => updateTheme())

/* ── 计算可视范围 ────────────────────────────────────── */

function calcBounds(aspect: number) {
  const halfFovRad = (FOV / 2) * (Math.PI / 180)
  boundsY = CAMERA_Z * Math.tan(halfFovRad)
  boundsX = boundsY * aspect
}

/* ── 初始化 ─────────────────────────────────────────── */

function init() {
  const el = containerRef.value
  if (!el) return

  const w = el.offsetWidth
  const h = el.offsetHeight
  const aspect = w / h

  calcBounds(aspect)

  scene = new THREE.Scene()
  scene.background = new THREE.Color(dark.value ? DARK_BG : LIGHT_BG)

  camera = new THREE.PerspectiveCamera(FOV, aspect, 0.1, 100)
  camera.position.z = CAMERA_Z

  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  el.appendChild(renderer.domElement)

  hitPlane = new THREE.Mesh(
    new THREE.PlaneGeometry(30, 30),
    new THREE.MeshBasicMaterial({ visible: false, side: THREE.DoubleSide }),
  )
  scene.add(hitPlane)

  clock = new THREE.Clock()

  generateParticles()
  createPointsMesh()
  createLineMesh()

  animate()
}

/* ── 粒子生成（铺满全屏） ───────────────────────────── */

function generateParticles() {
  const pds = new PoissonDiskSampling({
    shape: [500, 500],
    minDistance: 11,
    maxDistance: 13,
    tries: 20,
  })
  const sampled = pds.fill()
  particleCount = sampled.length

  basePositions = new Float32Array(particleCount * 3)
  particlePhases = new Float32Array(particleCount)
  particleBreathSpeeds = new Float32Array(particleCount)
  particleVelocities = new Float32Array(particleCount * 2)
  particleShapeTypes = new Float32Array(particleCount)
  particleRotations = new Float32Array(particleCount)

  // 按可视范围拉伸，加 10% 余量确保边缘不留空
  const padX = boundsX * 1.1
  const padY = boundsY * 1.1

  for (let i = 0; i < particleCount; i++) {
    basePositions[i * 3] = ((sampled[i][0] - 250) / 250) * padX
    basePositions[i * 3 + 1] = ((sampled[i][1] - 250) / 250) * padY
    basePositions[i * 3 + 2] = 0

    particlePhases[i] = Math.random() * Math.PI * 2
    particleBreathSpeeds[i] = 0.25 + Math.random() * 0.4
    particleVelocities[i * 2] = (Math.random() - 0.5) * 0.005
    particleVelocities[i * 2 + 1] = (Math.random() - 0.5) * 0.005

    // 形状分配：~40% 圆点, ~30% 菱形, ~30% 线段
    const r = Math.random()
    particleShapeTypes[i] = r < 0.4 ? 0 : r < 0.7 ? 1 : 2

    // 随机初始旋转角度
    particleRotations[i] = Math.random() * Math.PI * 2
  }
}

/* ── Points Mesh ────────────────────────────────────── */

function createPointsMesh() {
  const geom = new THREE.BufferGeometry()
  geom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(particleCount * 3), 3))
  geom.setAttribute('aPhase', new THREE.BufferAttribute(particlePhases, 1))
  geom.setAttribute('aBreathSpeed', new THREE.BufferAttribute(particleBreathSpeeds, 1))
  geom.setAttribute('aRotation', new THREE.BufferAttribute(particleRotations, 1))

  const palette = dark.value ? DARK_COLORS : LIGHT_COLORS

  pointMat = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uSize: { value: 2.5 },
      uMouse: { value: new THREE.Vector2(999, 999) },
      uMouseActive: { value: 0 },
      uDpr: { value: Math.min(window.devicePixelRatio, 2) },
      uColor1: { value: new THREE.Color(palette[0]) },
      uColor2: { value: new THREE.Color(palette[1]) },
      uColor3: { value: new THREE.Color(palette[2]) },
      uBoundsX: { value: boundsX },
      uBoundsY: { value: boundsY },
    },
    vertexShader: pointVert,
    fragmentShader: pointFrag,
    transparent: true,
    depthWrite: false,
  })

  pointsMesh = new THREE.Points(geom, pointMat)
  scene.add(pointsMesh)
}

/* ── LineSegments ───────────────────────────────────── */

function createLineMesh() {
  linePositions = new Float32Array(MAX_LINES * 6)
  lineAlphas = new Float32Array(MAX_LINES * 2)

  lineGeom = new THREE.BufferGeometry()
  lineGeom.setAttribute('position', new THREE.BufferAttribute(linePositions, 3))
  lineGeom.setAttribute('aAlpha', new THREE.BufferAttribute(lineAlphas, 1))

  lineMat = new THREE.ShaderMaterial({
    uniforms: {
      uLineColor: { value: new THREE.Color(dark.value ? 0.55 : 0.2, dark.value ? 0.55 : 0.2, dark.value ? 0.55 : 0.2) },
    },
    vertexShader: lineVert,
    fragmentShader: lineFrag,
    transparent: true,
    depthWrite: false,
  })

  lineMesh = new THREE.LineSegments(lineGeom, lineMat)
  scene.add(lineMesh)
}

/* ── 每帧更新 ───────────────────────────────────────── */

function updateParticles(time: number) {
  const posAttr = pointsMesh.geometry.getAttribute('position') as THREE.BufferAttribute
  const padX = boundsX * 1.1
  const padY = boundsY * 1.1

  for (let i = 0; i < particleCount; i++) {
    let x = basePositions[i * 3] + particleVelocities[i * 2] * time
    let y = basePositions[i * 3 + 1] + particleVelocities[i * 2 + 1] * time

    // 边界循环
    x = ((x + padX) % (padX * 2) + padX * 2) % (padX * 2) - padX
    y = ((y + padY) % (padY * 2) + padY * 2) % (padY * 2) - padY

    posAttr.setXYZ(i, x, y, 0)
  }
  posAttr.needsUpdate = true
}

function updateLines() {
  const posAttr = pointsMesh.geometry.getAttribute('position') as THREE.BufferAttribute
  let lineIdx = 0
  const maxAlpha = dark.value ? 0.12 : 0.08
  const mouseAlpha = dark.value ? 0.25 : 0.15
  const mx = smoothMouseWorld.x
  const my = smoothMouseWorld.y
  const mouseInRange = mouseActive > 0.1

  // 粒子间连线
  for (let i = 0; i < particleCount && lineIdx < MAX_LINES; i++) {
    const ax = posAttr.getX(i)
    const ay = posAttr.getY(i)

    for (let j = i + 1; j < particleCount && lineIdx < MAX_LINES; j++) {
      const bx = posAttr.getX(j)
      const by = posAttr.getY(j)
      const dx = ax - bx
      const dy = ay - by
      const dist = Math.sqrt(dx * dx + dy * dy)

      if (dist < CONNECT_DIST) {
        const alpha = (1 - dist / CONNECT_DIST) * maxAlpha
        const idx = lineIdx * 6

        linePositions[idx] = ax
        linePositions[idx + 1] = ay
        linePositions[idx + 2] = 0
        linePositions[idx + 3] = bx
        linePositions[idx + 4] = by
        linePositions[idx + 5] = 0

        lineAlphas[lineIdx * 2] = alpha
        lineAlphas[lineIdx * 2 + 1] = alpha

        lineIdx++
      }
    }
  }

  // 鼠标引力线：鼠标附近的粒子连线到鼠标
  if (mouseInRange) {
    const mouseRange = 0.5
    for (let i = 0; i < particleCount && lineIdx < MAX_LINES; i++) {
      const px = posAttr.getX(i)
      const py = posAttr.getY(i)
      const dx = px - mx
      const dy = py - my
      const dist = Math.sqrt(dx * dx + dy * dy)

      if (dist < mouseRange) {
        const alpha = (1 - dist / mouseRange) * mouseAlpha * mouseActive
        const idx = lineIdx * 6

        linePositions[idx] = px
        linePositions[idx + 1] = py
        linePositions[idx + 2] = 0
        linePositions[idx + 3] = mx
        linePositions[idx + 4] = my
        linePositions[idx + 5] = 0

        lineAlphas[lineIdx * 2] = alpha
        lineAlphas[lineIdx * 2 + 1] = alpha * 0.3

        lineIdx++
      }
    }
  }

  for (let i = lineIdx * 6; i < MAX_LINES * 6; i++) linePositions[i] = 0
  for (let i = lineIdx * 2; i < MAX_LINES * 2; i++) lineAlphas[i] = 0

  lineGeom.getAttribute('position').needsUpdate = true
  lineGeom.getAttribute('aAlpha').needsUpdate = true
  lineGeom.setDrawRange(0, lineIdx * 2)
}

/* ── 动画循环 ───────────────────────────────────────── */

function animate() {
  animationId = requestAnimationFrame(animate)
  if (paused) return

  const time = clock.getElapsedTime()

  // 鼠标 → 世界坐标
  raycaster.setFromCamera(mouseNDC, camera)
  const hits = raycaster.intersectObject(hitPlane)
  let mx = 999, my = 999
  if (hits.length > 0) {
    mx = hits[0].point.x
    my = hits[0].point.y
  }

  smoothMouseWorld.x += (mx - smoothMouseWorld.x) * 0.06
  smoothMouseWorld.y += (my - smoothMouseWorld.y) * 0.06
  mouseActive += ((mx < 100 ? 1 : 0) - mouseActive) * 0.05

  updateParticles(time)
  updateLines()

  pointMat.uniforms.uTime.value = time
  pointMat.uniforms.uMouse.value.set(smoothMouseWorld.x, smoothMouseWorld.y)
  pointMat.uniforms.uMouseActive.value = mouseActive

  renderer.render(scene, camera)
}

/* ── 主题切换 ───────────────────────────────────────── */

function updateTheme() {
  if (!scene) return
  scene.background = new THREE.Color(dark.value ? DARK_BG : LIGHT_BG)

  const palette = dark.value ? DARK_COLORS : LIGHT_COLORS
  pointMat.uniforms.uColor1.value.set(palette[0])
  pointMat.uniforms.uColor2.value.set(palette[1])
  pointMat.uniforms.uColor3.value.set(palette[2])

  const lv = dark.value ? 0.55 : 0.2
  lineMat.uniforms.uLineColor.value.set(lv, lv, lv)
}

/* ── 事件 ───────────────────────────────────────────── */

function handleMouseMove(e: MouseEvent) {
  mouseNDC.x = (e.clientX / window.innerWidth) * 2 - 1
  mouseNDC.y = -(e.clientY / window.innerHeight) * 2 + 1
}

function handleResize() {
  const el = containerRef.value
  if (!el || !camera || !renderer) return
  const w = el.offsetWidth
  const h = el.offsetHeight
  const aspect = w / h

  calcBounds(aspect)

  camera.aspect = aspect
  camera.updateProjectionMatrix()
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  pointMat.uniforms.uDpr.value = Math.min(window.devicePixelRatio, 2)
  pointMat.uniforms.uBoundsX.value = boundsX
  pointMat.uniforms.uBoundsY.value = boundsY

  // 重新分布粒子以适配新尺寸
  generateParticles()
}

function handleVisibility() {
  paused = document.hidden
  paused ? clock.stop() : clock.start()
}

/* ── 清理 ───────────────────────────────────────────── */

function dispose() {
  pointsMesh?.geometry.dispose()
  pointMat?.dispose()
  lineGeom?.dispose()
  lineMat?.dispose()
  hitPlane?.geometry.dispose()
  ;(hitPlane?.material as THREE.Material)?.dispose()
  renderer?.dispose()
  renderer?.domElement.parentElement?.removeChild(renderer.domElement)
}
</script>

<template>
  <div ref="containerRef" class="particle-container" aria-hidden="true" />
</template>

<style scoped>
.particle-container {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.particle-container canvas {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
}
</style>
