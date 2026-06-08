/**
 * 在指定 Canvas 上绘制几何化鱼形 logo。
 * 组件只负责传入尺寸和颜色，绘制细节集中在这里，方便后续扩展不同品牌形态。
 *
 * @param canvas 目标 canvas 元素
 * @param size 绘制尺寸，单位 px
 * @param color 线条和眼睛颜色
 */
export function drawFishLogo(canvas: HTMLCanvasElement, size: number, color: string): void {
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const ratio = 2
  canvas.width = size * ratio
  canvas.height = size * ratio
  canvas.style.width = `${size}px`
  canvas.style.height = `${size}px`

  ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  ctx.clearRect(0, 0, size, size)
  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineWidth = Math.max(1.4, size * 0.04)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'

  const cx = size / 2
  const cy = size / 2
  const headX = cx - size * 0.34
  const tailX = cx + size * 0.34
  const tailTipX = cx + size * 0.42
  const bodyTopY = cy - size * 0.23
  const bodyBottomY = cy + size * 0.23

  // 鱼身使用两段贝塞尔曲线形成轻量线描轮廓。
  ctx.beginPath()
  ctx.moveTo(headX, cy)
  ctx.arc(headX + size * 0.08, cy, size * 0.08, Math.PI * 0.82, Math.PI * 1.18)
  ctx.moveTo(headX, cy)
  ctx.bezierCurveTo(cx - size * 0.12, bodyTopY, cx + size * 0.2, bodyTopY, tailX, cy)
  ctx.bezierCurveTo(cx + size * 0.2, bodyBottomY, cx - size * 0.12, bodyBottomY, headX, cy)
  ctx.stroke()

  // 尾鳍向右分叉，保持和鱼身同一套描边语义。
  ctx.beginPath()
  ctx.moveTo(tailX, cy)
  ctx.bezierCurveTo(tailX + size * 0.08, cy - size * 0.07, tailTipX, cy - size * 0.18, tailTipX, cy - size * 0.24)
  ctx.moveTo(tailX, cy)
  ctx.bezierCurveTo(tailX + size * 0.08, cy + size * 0.07, tailTipX, cy + size * 0.18, tailTipX, cy + size * 0.24)
  ctx.stroke()

  // 眼睛采用实心圆，保证在小尺寸下仍清晰可见。
  ctx.beginPath()
  ctx.arc(cx - size * 0.15, cy - size * 0.05, size * 0.04, 0, Math.PI * 2)
  ctx.fill()

  // 拖尾短线使用渐隐透明度，作为品牌符号里的运动感而非装饰噪声。
  const trailStartX = tailTipX + size * 0.02
  for (let i = 0; i < 4; i += 1) {
    const alpha = 0.42 - i * 0.11
    const offsetX = i * size * 0.025
    const offsetY = (i - 1.5) * size * 0.045
    ctx.globalAlpha = Math.max(0.08, alpha)
    ctx.beginPath()
    ctx.moveTo(trailStartX + offsetX, cy + offsetY)
    ctx.lineTo(trailStartX + size * 0.08 + offsetX, cy + offsetY)
    ctx.stroke()
  }
  ctx.globalAlpha = 1
}
