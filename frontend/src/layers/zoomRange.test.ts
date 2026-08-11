import { describe, expect, it } from 'vitest'
import { LAYER_ZOOM_MAX, LAYER_ZOOM_MIN, withMaxZoom, withMinZoom } from './zoomRange'

describe('withMinZoom', () => {
  it('lässt max stehen, wenn es noch darüber liegt', () => {
    expect(withMinZoom(4, 16)).toEqual({ minZoom: 4, maxZoom: 16 })
  })

  it('hebt max an, wenn min darüber geschoben wird', () => {
    expect(withMinZoom(18, 10)).toEqual({ minZoom: 18, maxZoom: 18 })
  })

  it('klemmt an die untere Grenze', () => {
    expect(withMinZoom(-3, 12)).toEqual({ minZoom: LAYER_ZOOM_MIN, maxZoom: 12 })
  })

  it('rundet auf ganze Zoomstufen', () => {
    expect(withMinZoom(3.7, 12)).toEqual({ minZoom: 4, maxZoom: 12 })
  })
})

describe('withMaxZoom', () => {
  it('lässt min stehen, wenn es noch darunter liegt', () => {
    expect(withMaxZoom(4, 16)).toEqual({ minZoom: 4, maxZoom: 16 })
  })

  it('senkt min, wenn max darunter geschoben wird', () => {
    expect(withMaxZoom(14, 8)).toEqual({ minZoom: 8, maxZoom: 8 })
  })

  it('klemmt an die obere Grenze', () => {
    expect(withMaxZoom(4, 99)).toEqual({ minZoom: 4, maxZoom: LAYER_ZOOM_MAX })
  })
})
