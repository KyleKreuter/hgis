/**
 * Paints the pieces `furniture.ts` decided on onto the composed image.
 *
 * Every length in here is a CSS pixel and goes through `scaled()`. That is the same trick
 * the export map itself uses for its labels: a title at a fixed 16 device pixels would be
 * a third of its proper height on a 300 dpi page, while 16 CSS pixels times the pixel
 * ratio keeps it at 4.2 mm whatever the resolution.
 *
 * Kept free of decisions on purpose -- what appears at all is `buildFurniture`'s call, and
 * that one is testable without a canvas.
 */

import type { FurniturePlan } from './furniture'
import type { ImageSize } from './pageFormat'

/** Matches the app's own type stack, with fallbacks for a canvas drawn before the font loads. */
const FONT_STACK = '"Geist Variable", system-ui, -apple-system, "Segoe UI", sans-serif'

const INK = '#171717'
const MUTED_INK = '#404040'
const BOX_FILL = 'rgba(255, 255, 255, 0.86)'
const BOX_BORDER = 'rgba(0, 0, 0, 0.14)'

/** CSS pixels. */
const MARGIN = 12
const BOX_RADIUS = 6
const TITLE_FONT_SIZE = 16
const SMALL_FONT_SIZE = 10
const MIN_FONT_SIZE = 7
const NORTH_RADIUS = 20
const SCALE_BAR_HEIGHT = 6

export function drawFurniture(
  ctx: CanvasRenderingContext2D,
  plan: FurniturePlan,
  size: ImageSize,
): void {
  const scaled = (value: number) => value * size.pixelRatio
  const margin = scaled(MARGIN)

  if (plan.title) drawTitle(ctx, plan.title, scaled, margin)
  if (plan.northArrow) drawNorthArrow(ctx, plan.northArrow.bearing, scaled, size, margin)

  const scaleBarWidth = plan.scaleBar
    ? drawScaleBar(ctx, plan.scaleBar.widthCssPx, plan.scaleBar.label, scaled, size, margin)
    : 0
  if (plan.attribution) {
    drawAttribution(ctx, plan.attribution, scaled, size, margin, scaleBarWidth)
  }
}

type Scale = (value: number) => number

/** `roundRect` is recent enough that a fallback is cheaper than a support matrix. */
function boxPath(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
): void {
  ctx.beginPath()
  if (typeof ctx.roundRect === 'function') {
    ctx.roundRect(x, y, width, height, radius)
    return
  }
  ctx.rect(x, y, width, height)
}

function fillBox(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  scaled: Scale,
): void {
  boxPath(ctx, x, y, width, height, scaled(BOX_RADIUS))
  ctx.fillStyle = BOX_FILL
  ctx.fill()
  ctx.lineWidth = Math.max(1, scaled(1))
  ctx.strokeStyle = BOX_BORDER
  ctx.stroke()
}

function drawTitle(
  ctx: CanvasRenderingContext2D,
  title: string,
  scaled: Scale,
  margin: number,
): void {
  const fontSize = scaled(TITLE_FONT_SIZE)
  ctx.font = `600 ${fontSize}px ${FONT_STACK}`
  const textWidth = ctx.measureText(title).width
  const padX = scaled(10)
  const padY = scaled(7)
  const boxHeight = fontSize + padY * 2

  fillBox(ctx, margin, margin, textWidth + padX * 2, boxHeight, scaled)

  ctx.fillStyle = INK
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillText(title, margin + padX, margin + boxHeight / 2)
}

/**
 * A compass rose in the top right corner, turned so its arrow points at true north.
 *
 * The whole symbol turns, the "N" included -- the usual cartographic convention, and the
 * only one that stays readable as a compass rather than as a letter with an arrow next
 * to it. `bearing` is the compass direction that is "up" on the map, so north sits at
 * minus that angle.
 */
function drawNorthArrow(
  ctx: CanvasRenderingContext2D,
  bearing: number,
  scaled: Scale,
  size: ImageSize,
  margin: number,
): void {
  const radius = scaled(NORTH_RADIUS)
  const centerX = size.widthPx - margin - radius
  const centerY = margin + radius

  ctx.beginPath()
  ctx.arc(centerX, centerY, radius, 0, Math.PI * 2)
  ctx.fillStyle = BOX_FILL
  ctx.fill()
  ctx.lineWidth = Math.max(1, scaled(1))
  ctx.strokeStyle = BOX_BORDER
  ctx.stroke()

  ctx.save()
  ctx.translate(centerX, centerY)
  ctx.rotate((-bearing * Math.PI) / 180)

  ctx.beginPath()
  ctx.moveTo(0, scaled(-7))
  ctx.lineTo(scaled(7), scaled(12))
  ctx.lineTo(0, scaled(6))
  ctx.lineTo(scaled(-7), scaled(12))
  ctx.closePath()
  ctx.fillStyle = INK
  ctx.fill()

  ctx.font = `600 ${scaled(10)}px ${FONT_STACK}`
  ctx.fillStyle = INK
  ctx.textAlign = 'center'
  ctx.textBaseline = 'alphabetic'
  ctx.fillText('N', 0, scaled(-9))

  ctx.restore()
}

/**
 * Bottom left: the label over a bracket-shaped bar, the same shape the on-screen control
 * draws. Returns the box width so the attribution can keep clear of it.
 */
function drawScaleBar(
  ctx: CanvasRenderingContext2D,
  widthCssPx: number,
  label: string,
  scaled: Scale,
  size: ImageSize,
  margin: number,
): number {
  const barWidth = scaled(widthCssPx)
  const barHeight = scaled(SCALE_BAR_HEIGHT)
  const fontSize = scaled(SMALL_FONT_SIZE)
  const padX = scaled(8)
  const padY = scaled(6)
  const gap = scaled(3)

  // Set before measuring: `measureText` answers for whatever font is current, and the
  // box would be sized against the previous piece's type otherwise.
  ctx.font = `500 ${fontSize}px ${FONT_STACK}`
  const boxWidth = Math.max(barWidth, ctx.measureText(label).width) + padX * 2
  const boxHeight = fontSize + gap + barHeight + padY * 2
  const boxX = margin
  const boxY = size.heightPx - margin - boxHeight

  fillBox(ctx, boxX, boxY, boxWidth, boxHeight, scaled)

  ctx.fillStyle = MUTED_INK
  ctx.textAlign = 'left'
  ctx.textBaseline = 'top'
  ctx.fillText(label, boxX + padX, boxY + padY)

  const barY = boxY + padY + fontSize + gap
  ctx.beginPath()
  ctx.moveTo(boxX + padX, barY)
  ctx.lineTo(boxX + padX, barY + barHeight)
  ctx.lineTo(boxX + padX + barWidth, barY + barHeight)
  ctx.lineTo(boxX + padX + barWidth, barY)
  ctx.lineWidth = Math.max(1, scaled(1.5))
  ctx.strokeStyle = MUTED_INK
  ctx.stroke()

  return boxWidth
}

/**
 * Bottom right, opposite the scale bar. The notice is a licence term, so it never gets
 * cut off: where the room between the two is not enough, the type shrinks instead, down
 * to a floor that is still legible in print.
 */
function drawAttribution(
  ctx: CanvasRenderingContext2D,
  attribution: string,
  scaled: Scale,
  size: ImageSize,
  margin: number,
  scaleBarWidth: number,
): void {
  const padX = scaled(6)
  const padY = scaled(4)
  const available = size.widthPx - margin * 2 - scaleBarWidth - scaled(8) - padX * 2

  let fontSize = scaled(SMALL_FONT_SIZE)
  const floor = scaled(MIN_FONT_SIZE)
  ctx.font = `${fontSize}px ${FONT_STACK}`
  while (ctx.measureText(attribution).width > available && fontSize > floor) {
    fontSize = Math.max(floor, fontSize - scaled(0.5))
    ctx.font = `${fontSize}px ${FONT_STACK}`
  }

  const textWidth = ctx.measureText(attribution).width
  const boxWidth = textWidth + padX * 2
  const boxHeight = fontSize + padY * 2
  const boxX = size.widthPx - margin - boxWidth
  const boxY = size.heightPx - margin - boxHeight

  fillBox(ctx, boxX, boxY, boxWidth, boxHeight, scaled)

  ctx.fillStyle = MUTED_INK
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillText(attribution, boxX + padX, boxY + boxHeight / 2)
}
