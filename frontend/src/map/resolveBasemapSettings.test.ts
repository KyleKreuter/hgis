import { describe, expect, it } from 'vitest'
import { hasLayerBasemapOverride, resolveBasemapSettings } from './resolveBasemapSettings'

const PROJECT = { basemap: 'osm', basemapOpacity: 0.8 }

describe('resolveBasemapSettings', () => {
  it('falls back to the project when there is no active layer', () => {
    const resolved = resolveBasemapSettings(null, PROJECT)

    expect(resolved).toEqual({
      basemapId: 'osm',
      opacity: 0.8,
      basemapFromLayer: false,
      opacityFromLayer: false,
    })
  })

  it('falls back to the project when the active layer sets neither field', () => {
    const resolved = resolveBasemapSettings({ basemap: null, basemapOpacity: null }, PROJECT)

    expect(resolved.basemapId).toBe('osm')
    expect(resolved.opacity).toBe(0.8)
    expect(resolved.basemapFromLayer).toBe(false)
    expect(resolved.opacityFromLayer).toBe(false)
  })

  it('prefers the layer over the project for the basemap alone', () => {
    const resolved = resolveBasemapSettings({ basemap: 'opentopo', basemapOpacity: null }, PROJECT)

    expect(resolved.basemapId).toBe('opentopo')
    expect(resolved.basemapFromLayer).toBe(true)
    // The opacity was not overridden, so it still comes from the project.
    expect(resolved.opacity).toBe(0.8)
    expect(resolved.opacityFromLayer).toBe(false)
  })

  it('prefers the layer over the project for the opacity alone', () => {
    const resolved = resolveBasemapSettings({ basemap: null, basemapOpacity: 0.3 }, PROJECT)

    expect(resolved.opacity).toBe(0.3)
    expect(resolved.opacityFromLayer).toBe(true)
    // The basemap was not overridden, so it still comes from the project.
    expect(resolved.basemapId).toBe('osm')
    expect(resolved.basemapFromLayer).toBe(false)
  })

  it('prefers the layer over the project for both when both are set', () => {
    const resolved = resolveBasemapSettings({ basemap: 'none', basemapOpacity: 0.5 }, PROJECT)

    expect(resolved.basemapId).toBe('none')
    expect(resolved.opacity).toBe(0.5)
    expect(resolved.basemapFromLayer).toBe(true)
    expect(resolved.opacityFromLayer).toBe(true)
  })

  it('treats a missing field the same as an explicit null', () => {
    const resolved = resolveBasemapSettings({ basemap: undefined, basemapOpacity: undefined }, PROJECT)

    expect(resolved.basemapId).toBe('osm')
    expect(resolved.opacity).toBe(0.8)
    expect(resolved.basemapFromLayer).toBe(false)
    expect(resolved.opacityFromLayer).toBe(false)
  })
})

describe('hasLayerBasemapOverride', () => {
  it('is false without an active layer', () => {
    expect(hasLayerBasemapOverride(null)).toBe(false)
    expect(hasLayerBasemapOverride(undefined)).toBe(false)
  })

  it('is false for a layer that overrides neither field', () => {
    expect(hasLayerBasemapOverride({ basemap: null, basemapOpacity: null })).toBe(false)
  })

  it('is true when only the basemap is overridden', () => {
    expect(hasLayerBasemapOverride({ basemap: 'opentopo', basemapOpacity: null })).toBe(true)
  })

  it('is true when only the opacity is overridden', () => {
    expect(hasLayerBasemapOverride({ basemap: null, basemapOpacity: 0.4 })).toBe(true)
  })
})
