import { describe, expect, it } from 'vitest'
import { buildQueryString, featureKeys } from './features'

describe('buildQueryString', () => {
  it('is empty when nothing restricts the query', () => {
    expect(buildQueryString({ layerId: 'x' })).toBe('')
  })

  it('sends only filter when search is unset', () => {
    const result = buildQueryString({ layerId: 'x', filter: "name = 'Schmidt'" })
    const params = new URLSearchParams(result)
    expect(params.get('filter')).toBe("name = 'Schmidt'")
    expect(params.has('search')).toBe(false)
  })

  it('sends only search when filter is unset', () => {
    const result = buildQueryString({ layerId: 'x', search: 'Schmidt' })
    const params = new URLSearchParams(result)
    expect(params.get('search')).toBe('Schmidt')
    expect(params.has('filter')).toBe(false)
  })

  it('sends filter and search together, both as their own parameter', () => {
    const result = buildQueryString({ layerId: 'x', filter: "age > 5", search: 'Schmidt' })
    const params = new URLSearchParams(result)
    expect(params.get('filter')).toBe('age > 5')
    expect(params.get('search')).toBe('Schmidt')
  })

  it('treats a whitespace-only search like unset', () => {
    expect(buildQueryString({ layerId: 'x', search: '   ' })).toBe('')
  })

  it('treats a whitespace-only filter like unset', () => {
    expect(buildQueryString({ layerId: 'x', filter: '   ' })).toBe('')
  })

  it('trims surrounding whitespace from search before sending it', () => {
    const params = new URLSearchParams(buildQueryString({ layerId: 'x', search: '  Schmidt  ' }))
    expect(params.get('search')).toBe('Schmidt')
  })

  it('still combines sort, cursor and search in one query string', () => {
    const params = new URLSearchParams(
      buildQueryString({ layerId: 'x', sort: 'name', desc: true, search: 'Schmidt' }, 'cursor-1'),
    )
    expect(params.get('sort')).toBe('name')
    expect(params.get('desc')).toBe('true')
    expect(params.get('search')).toBe('Schmidt')
    expect(params.get('cursor')).toBe('cursor-1')
  })
})

describe('featureKeys.page', () => {
  it('differentiates the cache key by search, independently of filter', () => {
    const noRestriction = featureKeys.page({ layerId: 'x' })
    const withSearch = featureKeys.page({ layerId: 'x', search: 'Schmidt' })
    const withFilter = featureKeys.page({ layerId: 'x', filter: "name = 'Schmidt'" })

    expect(withSearch).not.toEqual(noRestriction)
    expect(withFilter).not.toEqual(noRestriction)
    expect(withSearch).not.toEqual(withFilter)
  })

  it('is stable for the same query', () => {
    const a = featureKeys.page({ layerId: 'x', search: 'Schmidt' })
    const b = featureKeys.page({ layerId: 'x', search: 'Schmidt' })
    expect(a).toEqual(b)
  })
})
