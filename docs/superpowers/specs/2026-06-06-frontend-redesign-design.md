# 前端视觉重设计 — 设计规格

**日期**: 2026-06-06
**状态**: 待审核
**范围**: Fish Agent 前端全部页面（登录、聊天、知识库）

---

## 1. 设计目标

将现有 Vue 3 + Element Plus 前端从"功能可用"升级为"精致沉浸"：

- **全屏粒子背景** — 网络连线型，蓝紫色，鼠标排斥交互
- **毛玻璃拟态** — 所有内容层使用 `backdrop-filter: blur`
- **深色蓝紫基调** — 统一深色主题，移除明暗切换
- **抽屉式侧栏** — 聊天区占满宽度，沉浸感最大化
- **精致动效** — 抽屉滑入、消息淡入、输入框发光

---

## 2. 技术选型

### 2.1 粒子引擎：tsParticles

| 项目 | 详情 |
|------|------|
| 包名 | `@tsparticles/vue3` + `@tsparticles/slim` |
| 体积 | ~40KB gzip |
| 原因 | Vue 3 原生组件，配置式驱动，网络连线效果开箱即用，TypeScript 完整支持 |

### 2.2 移除的依赖/功能

- `useTheme.ts` composable — 明暗切换不再需要
- `[data-theme='dark']` 动态切换机制 — 全局深色，CSS 变量直接写死

### 2.3 保留的依赖

- Vue 3 + TypeScript + Vite + Pinia + Vue Router — 不变
- Element Plus — 保留，暗色样式全局覆盖
- `marked` + `highlight.js` — Markdown 渲染不变

---

## 3. 架构变更

### 3.1 新增文件

| 文件路径 | 职责 |
|----------|------|
| `src/components/ParticleBackground.vue` | 全局粒子背景层，`position: fixed`, `z-index: 0`, `pointer-events: none` |
| `src/components/AppHeader.vue` | 顶部导航条（毛玻璃），汉堡按钮 + 品牌 + 知识库入口 + 用户信息 + 退出 |
| `src/components/DrawerSidebar.vue` | 抽屉侧栏，从左滑入 280px，含会话列表和上传区 |
| `src/composables/useDrawer.ts` | 抽屉开关响应式状态 |

### 3.2 重构/删除文件

| 文件 | 变更 |
|------|------|
| `src/components/SessionSidebar.vue` | 拆分 → `DrawerSidebar.vue`（抽屉逻辑 + 会话列表） |
| `src/composables/useTheme.ts` | 删除 |
| `src/style.css` | 大幅重写：移除 `[data-theme='dark']` 块，新增毛玻璃/发光/深色变量体系 |

### 3.3 修改文件

| 文件 | 变更概要 |
|------|----------|
| `App.vue` | 挂载 `ParticleBackground`，移除主题相关代码 |
| `views/ChatView.vue` | 移除固定侧栏，改用 `AppHeader` + `DrawerSidebar` |
| `views/LoginView.vue` | 移除 orb 装饰，移除 tab 切换，改为登录/注册单卡片切换 |
| `views/KnowledgeView.vue` | 移除 Banner，复用 `AppHeader`，内容区毛玻璃包裹 |
| `components/MessageBubble.vue` | 助手气泡改为毛玻璃样式 |
| `components/ChatInput.vue` | 外框改为毛玻璃 + 蓝紫 focus 发光 |
| `components/MessageList.vue` | 欢迎屏卡片改为毛玻璃小卡片 |
| `components/KnowledgeUpload.vue` | 拖拽区样式适配深色 |

---

## 4. 页面设计

### 4.1 聊天页（核心）

**布局结构：**

```
┌──────────────────────────────────────────────┐
│  AppHeader (fixed top, z:10, 毛玻璃)          │
│  ☰ 汉堡  │  🐟 Fish Agent  │   📚 ⚙ 👤      │
├──────────────────────────────────────────────┤
│                                              │
│        ParticleBackground (fixed, z:0)       │
│                                              │
│     ┌──────────────────────────────┐         │
│     │   消息列表（居中, max 800px）  │         │
│     │                              │         │
│     │  用户气泡 →  渐变紫色          │         │
│     │  ← 助手气泡   毛玻璃          │         │
│     │  ← 工具气泡   半透明 + 蓝紫边框│         │
│     └──────────────────────────────┘         │
│                                              │
│     ┌──────────────────────────────┐         │
│     │   输入框（居中, max 800px）    │         │
│     └──────────────────────────────┘         │
└──────────────────────────────────────────────┘
```

**AppHeader 规范：**
- 高度 48px
- `background: rgba(10, 10, 20, 0.7)`
- `backdrop-filter: blur(16px)`
- `border-bottom: 1px solid rgba(99, 102, 241, 0.15)`
- 左侧：汉堡按钮（☰ 图标，点击打开 DrawerSidebar）+ 🐟 + "Fish Agent" 品牌名（渐变文字）
- 右侧：知识库入口按钮 + 用户昵称 + 退出按钮

**DrawerSidebar 规范：**
- 宽度 280px
- `background: rgba(15, 15, 25, 0.9)` + `backdrop-filter: blur(24px)`
- `border-right: 1px solid rgba(99, 102, 241, 0.15)`
- 打开动画：`transform: translateX(-100%) → translateX(0)`, 300ms ease-out
- 遮罩层：`rgba(0, 0, 0, 0.5)`，点击关闭
- 内容从上到下：品牌行 + 新会话按钮 → 会话列表（可滚动）→ 知识库上传区 → 底部状态栏

**MessageBubble 毛玻璃化：**
- 助手气泡：`background: rgba(20, 20, 35, 0.8)` + `backdrop-filter: blur(12px)` + `border: 1px solid rgba(99, 102, 241, 0.1)`
- 用户气泡：`linear-gradient(135deg, #4f46e5, #7c3aed)` + `box-shadow: 0 2px 16px rgba(99, 102, 241, 0.35)`
- 工具气泡：`background: rgba(25, 25, 40, 0.6)` + 毛玻璃 + 蓝紫左边框

**ChatInput 毛玻璃化：**
- `background: rgba(20, 20, 35, 0.65)` + `backdrop-filter: blur(20px)`
- `border: 1px solid rgba(99, 102, 241, 0.2)` + `border-radius: 24px`
- focus: `box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.2), 0 0 20px rgba(99, 102, 241, 0.1)`

**欢迎屏：**
- 居中 🐟 + "你好，我是 Fish Agent" + 副标题
- 4 个建议卡片：毛玻璃小卡片 `background: rgba(20, 20, 35, 0.6)` + 蓝紫左边框
- 粒子背景全屏可见

### 4.2 登录页

**布局：** 全屏粒子背景 + 居中毛玻璃登录卡片

**卡片规范：**
- `background: rgba(15, 15, 25, 0.7)` + `backdrop-filter: blur(24px)`
- `border: 1px solid rgba(99, 102, 241, 0.15)` + `border-radius: 24px`
- 宽度 400px

**关键变更：**
1. **移除 Orb 装饰** — 两个模糊圆形（orb-1, orb-2）被粒子背景替代
2. **移除 Tabs** — 登录/注册改为单卡片内切换：
   - 登录卡片底部"没有账号？去注册 →"
   - 点击后卡片内容淡入滑动切换为注册表单
   - 注册卡片底部"已有账号？去登录 →"
3. **登录按钮** — 渐变紫色，hover 发光 `box-shadow: 0 4px 24px rgba(99, 102, 241, 0.4)`
4. **品牌** — 🐟 保持浮动动画，标题保持渐变文字
5. **切换动画** — `opacity 0→1` + `translateY(8px)→0`, 250ms

### 4.3 知识库页

**布局：** AppHeader + 全屏粒子 + 居中毛玻璃内容容器（max 960px）

**内容区容器：**
- `background: rgba(15, 15, 25, 0.6)` + `backdrop-filter: blur(16px)`
- `border-radius: 20px` + `border: 1px solid rgba(99, 102, 241, 0.15)`

**关键变更：**
1. **移除顶部 Banner** — `::before` 伪元素紫色 Banner 移除，复用 AppHeader
2. **上传区** — 虚线蓝紫边框 + 半透明背景，拖入时边框发光
3. **表格暗色适配**：
   - 表头：`rgba(99, 102, 241, 0.08)`
   - 行 hover：`rgba(99, 102, 241, 0.06)`
   - 斑马纹：`rgba(99, 102, 241, 0.03)`
   - 边框：`rgba(99, 102, 241, 0.1)`
4. **分页器** — 暗色适配，当前页渐变紫色
5. **返回导航** — 通过 AppHeader 左侧返回箭头实现

---

## 5. 全局样式体系

### 5.1 CSS 变量

```css
:root {
  /* 背景 */
  --bg-base: #0a0a14;
  --bg-glass: rgba(15, 15, 25, 0.75);
  --bg-glass-heavy: rgba(15, 15, 25, 0.9);
  --bg-hover: rgba(99, 102, 241, 0.08);

  /* 文字 */
  --text-primary: #e8e8ea;
  --text: #c9d1d9;
  --text-secondary: #6b7280;
  --text-muted: #4b5563;

  /* 边框 */
  --border: rgba(99, 102, 241, 0.15);
  --border-bright: rgba(99, 102, 241, 0.3);

  /* 品牌色 */
  --primary: #6366f1;
  --primary-light: #818cf8;
  --primary-dim: #4f46e5;

  /* 渐变 */
  --gradient-brand: linear-gradient(135deg, #4f46e5, #7c3aed);
  --gradient-brand-hover: linear-gradient(135deg, #4338ca, #6d28d9);

  /* 毛玻璃 */
  --glass-blur: blur(16px);
  --glass-blur-heavy: blur(24px);

  /* 发光 */
  --glow-primary: 0 0 20px rgba(99, 102, 241, 0.15);
  --glow-focus: 0 0 0 3px rgba(99, 102, 241, 0.2);

  /* 阴影 */
  --shadow-sm: 0 1px 4px rgba(0, 0, 0, 0.3);
  --shadow-md: 0 4px 16px rgba(0, 0, 0, 0.4);
  --shadow-lg: 0 8px 32px rgba(0, 0, 0, 0.5);

  /* 圆角 */
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 16px;
  --radius-xl: 24px;

  /* 过渡 */
  --transition-fast: 0.12s ease;
  --transition-normal: 0.2s ease;
  --transition-slow: 0.35s ease;

  /* 气泡 */
  --bubble-user: linear-gradient(135deg, #4f46e5, #7c3aed);
  --bubble-user-text: #ffffff;
  --bubble-assistant: rgba(20, 20, 35, 0.8);
  --bubble-assistant-text: #e0e0e4;
  --bubble-tool: rgba(25, 25, 40, 0.6);
  --bubble-tool-border: #6366f1;

  /* 复制按钮 */
  --copy-btn-bg: rgba(255, 255, 255, 0.08);
}
```

**不再有 `[data-theme='dark']` 块** — 全局深色，变量直接生效。

### 5.2 Element Plus 暗色覆盖

保留 style.css 底部的 Element Plus 暗色覆盖代码（表格、分页、按钮、输入框、卡片、Tabs），但移除 `[data-theme='dark']` 选择器前缀，直接全局应用。

---

## 6. 粒子系统

### 6.1 tsParticles 配置

```json
{
  "background": { "color": "#0a0a14" },
  "fpsLimit": 60,
  "particles": {
    "number": { "value": 60, "density": { "enable": true, "area": 900 } },
    "color": { "value": ["#6366f1", "#818cf8", "#a78bfa"] },
    "shape": { "type": "circle" },
    "opacity": { "value": { "min": 0.2, "max": 0.5 } },
    "size": { "value": { "min": 1, "max": 3 } },
    "links": {
      "enable": true,
      "distance": 150,
      "color": "#6366f1",
      "opacity": 0.2,
      "width": 1
    },
    "move": {
      "enable": true,
      "speed": 0.8,
      "direction": "none",
      "outModes": "bounce"
    }
  },
  "interactivity": {
    "events": {
      "onHover": { "enable": true, "mode": "repulse" },
      "resize": true
    },
    "modes": {
      "repulse": { "distance": 120, "duration": 0.4 }
    }
  }
}
```

- 60 个粒子，三色蓝紫渐变（#6366f1 / #818cf8 / #a78bfa）
- 连线距离 150px，半透明（opacity 0.2）
- 鼠标悬停排斥 120px
- 缓慢漂浮（speed 0.8），无方向
- `fpsLimit: 60` 保证流畅

### 6.2 组件实现

`ParticleBackground.vue`：
- `position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 0; pointer-events: none`
- 粒子层不接收鼠标事件（`pointer-events: none`），鼠标交互通过 tsParticles 的 `interactivity.detect_on: "window"` 实现
- 挂载在 `App.vue` 最外层，所有页面共享同一个实例

### 6.3 性能策略

| 场景 | 处理 |
|------|------|
| `prefers-reduced-motion` | 检测系统偏好，开启时粒子静止（`move.enable: false`） |
| 粒子层交互 | `pointer-events: none`，不影响内容区滚动和点击 |
| 页面切换 | 单实例挂载在 App.vue，路由切换不重新初始化 |
| 组件卸载 | `unmount` 时调用 `tsParticles.destroy()` 释放 |
| 低端设备 | 可通过 props 减少粒子数量（`particles.number.value: 30`） |

---

## 7. 动效清单

| 元素 | 动效 | 时长 | 缓动 |
|------|------|------|------|
| DrawerSidebar 滑入 | `translateX(-100%) → 0` | 300ms | ease-out |
| 遮罩层 | `opacity 0 → 0.5` | 300ms | ease |
| 消息气泡出现 | `opacity 0→1, translateY(8px)→0` | 250ms | ease-out |
| 复制按钮 hover | `opacity 0→1` | 120ms | ease |
| 输入框 focus | 边框发光 + `box-shadow` | 200ms | ease |
| 登录/注册切换 | `opacity 0→1, translateY(8px)→0` | 250ms | ease-out |
| 粒子漂浮 | 常驻缓慢移动 + 鼠标排斥 | 持续 | — |
| 🐟 品牌 icon | `translateY(0) ↔ translateY(-8px)` | 3s | ease-in-out, infinite |
| 流式光标 | 闪烁竖线 | 1s | steps(2, start) |
| 思考中三点 | scale + opacity 跳动 | 1.2s | infinite |
| 建议卡片 hover | `translateY(-2px)` + 阴影加深 | 200ms | ease |
| 按钮 hover | `opacity: 0.9` + `translateY(-1px)` | 120ms | ease |
| 按钮 active | `translateY(0)` + `scale(0.95)` | 100ms | ease |

---

## 8. 实施影响分析

### 8.1 不变的部分

- 所有 API 层（`api/*.ts`）— 完全不变
- 所有 Store（`store/*.ts`）— 完全不变
- 所有类型定义（`types/*.ts`）— 完全不变
- Router 逻辑（`router/index.ts`）— 完全不变
- 工具函数（`utils/time.ts`）— 完全不变
- MessageBubble 的 Markdown 渲染逻辑 — 不变
- KnowledgeUpload 的上传逻辑 — 不变

### 8.2 影响范围总结

- 新增 4 个文件
- 删除 1 个文件（useTheme.ts）
- 重写 1 个文件（SessionSidebar → DrawerSidebar）
- 修改 8 个文件（style.css, App.vue, 3 个 views, 3 个 components）
- `package.json` 新增 2 个依赖，删除 0 个

### 8.3 风险点

| 风险 | 缓解措施 |
|------|----------|
| tsParticles 与 Element Plus 弹窗 z-index 冲突 | 粒子层 z-index: 0，Element Plus 弹窗默认 z-index: 2000+，无冲突 |
| 毛玻璃 `backdrop-filter` 性能 | 仅在 Header/Sidebar/Input/Bubble 上使用，不在列表行上使用 |
| 移除亮色主题后无法恢复 | 保留 CSS 变量体系，后续如需亮色只需修改变量值 |
| Element Plus el-input 暗色样式覆盖不全 | 逐组件检查，必要时增加覆盖规则 |
