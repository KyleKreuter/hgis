/**
 * Frees the WebGL context of a map container whose own teardown could not.
 *
 * `WEBGL_lose_context` is the only way to give a context back before garbage collection,
 * and garbage collection is too late here: the browser's limit is on live contexts, and
 * it refuses the next one rather than collecting an old one.
 *
 * Deliberately quiet. This runs inside a `catch` that is already handling one failure,
 * and a throw from the cleanup itself would take out the reset that follows it.
 */
export function releaseWebGl(container: HTMLElement | null): void {
  if (!container) return
  try {
    const canvas = container.querySelector('canvas')
    if (!canvas) return
    // `getContext` returns the context the canvas already has when the type matches, so
    // this reaches the map's own rather than creating a second one.
    const gl =
      (canvas.getContext('webgl2') as WebGL2RenderingContext | null) ??
      (canvas.getContext('webgl') as WebGLRenderingContext | null)
    gl?.getExtension('WEBGL_lose_context')?.loseContext()
  }
  catch (error) {
    console.debug('[hgis] releasing the WebGL context failed:', error)
  }
}
