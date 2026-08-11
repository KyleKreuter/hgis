import { cn } from '@/lib/utils'

/** The application wordmark in the project browser header. */
export function Brand({ className }: { className?: string }) {
  return (
    <span className={cn('font-semibold tracking-tight', className)}>hGIS</span>
  )
}
