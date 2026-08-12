import { cn } from '@/lib/utils'

/** The application wordmark in the project browser header. */
export function Brand({ className }: { className?: string }) {
  return (
    <span className={cn('font-semibold tracking-tight', className)}>
      {/* The literal red of `public/favicon.svg`, which is this same "h" as a glyph.
          Not a theme token on purpose: it is the mark's own colour and has to stay
          the same in both themes, the way the tab icon does. */}
      <span className="text-[#ff0000]">h</span>GIS
    </span>
  )
}
