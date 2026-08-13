import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Unmounts whatever the previous test rendered. Without it a component stays mounted,
// its effects keep running, and `getByRole` finds two of everything in the next test.
afterEach(cleanup)

/**
 * Browser APIs jsdom does not implement, stubbed just far enough for the components
 * under test to mount.
 *
 * Each of these is called during render or in an effect, and an undefined constructor
 * or method throws before a single assertion runs. They are stubs, not simulations: no
 * test may depend on what they return, only on the component surviving the call.
 */

// @tanstack/react-virtual measures its scroll element with one of these.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', ResizeObserverStub)

class IntersectionObserverStub {
  readonly root = null
  readonly rootMargin = ''
  readonly thresholds: number[] = []
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
}
vi.stubGlobal('IntersectionObserver', IntersectionObserverStub)

// next-themes and several base-ui primitives ask for the user's colour scheme and
// reduced-motion preference on mount.
vi.stubGlobal(
  'matchMedia',
  vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
)

// jsdom has no layout, so it implements neither scrolling nor pointer capture. base-ui's
// dialog and select drive both, and the attribute table scrolls its container to the top
// on every sort or filter change.
Element.prototype.scrollTo = () => {}
Element.prototype.scrollIntoView = () => {}
Element.prototype.hasPointerCapture = () => false
Element.prototype.setPointerCapture = () => {}
Element.prototype.releasePointerCapture = () => {}

// The Web Animations API, which jsdom does not have at all. base-ui's scroll area asks
// its viewport for running animations from a timer, so an unstubbed call lands outside
// any test's stack and fails the run as an unhandled error rather than as an assertion.
Element.prototype.getAnimations = () => []
Element.prototype.animate = (() => ({
  cancel: () => {},
  finish: () => {},
  play: () => {},
  pause: () => {},
  addEventListener: () => {},
  removeEventListener: () => {},
})) as unknown as Element['animate']
