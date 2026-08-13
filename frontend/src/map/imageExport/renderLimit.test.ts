import { describe, expect, it } from 'vitest'
import { computeImageSize, findPageChoice, type ImageSize } from './pageFormat'
import { maxCanvasSizeFor, renderLimitMessage } from './renderLimit'

const SCREEN = { width: 900, height: 600 }

function a3At300(): ImageSize {
  return computeImageSize(findPageChoice('a3-landscape'), 300, SCREEN)
}

describe('renderLimitMessage', () => {
  it('lässt ein Bild durch, das unter der Grenze bleibt', () => {
    expect(renderLimitMessage(a3At300(), 16384)).toBeNull()
  })

  it('lehnt ein Bild ab, das breiter ist als die Grenze', () => {
    const message = renderLimitMessage(a3At300(), 4096)

    expect(message).not.toBeNull()
    expect(message).toContain('4096')
    expect(message).toContain('4961 × 3508 Pixel')
  })

  it('lehnt auch ab, wenn nur die Höhe die Grenze überschreitet', () => {
    const size = computeImageSize(findPageChoice('a3-portrait'), 300, SCREEN)

    expect(renderLimitMessage(size, 4200)).not.toBeNull()
  })

  it('lässt ein Bild genau auf der Grenze durch', () => {
    const size: ImageSize = {
      widthPx: 4096,
      heightPx: 4096,
      cssWidth: 4096,
      cssHeight: 4096,
      pixelRatio: 1,
    }

    expect(renderLimitMessage(size, 4096)).toBeNull()
  })

  /**
   * A browser that cannot answer must not block the export: without WebGL there is no
   * map on screen to export in the first place, so a null limit means "cannot say",
   * never "zero".
   */
  it('lehnt nichts ab, wenn die Grenze unbekannt ist', () => {
    expect(renderLimitMessage(a3At300(), null)).toBeNull()
  })
})

describe('maxCanvasSizeFor', () => {
  /**
   * MapLibre's own default is 4096 x 4096 and it enforces it by lowering the pixel ratio
   * without a word. Anything below the target here would produce a quietly softer image.
   */
  it('gibt die Grenze der Grafikkarte weiter, nicht die MapLibre-Vorgabe', () => {
    expect(maxCanvasSizeFor(a3At300(), 16384)).toEqual([16384, 16384])
  })

  it('nimmt ohne bekannte Grenze die Zielgröße plus Reserve', () => {
    const size = a3At300()
    const [width, height] = maxCanvasSizeFor(size, null)

    expect(width).toBeGreaterThan(size.widthPx)
    expect(height).toBeGreaterThan(size.heightPx)
  })
})
