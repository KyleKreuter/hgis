import wappen from '@/assets/hamburg-wappen.png'
import { cn } from '@/lib/utils'

/**
 * The application's wordmark: Hamburg's coat of arms next to "hGIS".
 *
 * The lowercase red h is the mark itself -- it is what the favicon carries, alone and
 * without the wordmark, so the two have to stay the same red. That red is the one taken
 * from the coat of arms (#ff0000), not a theme colour: a token would drift with the
 * palette and no longer match the image beside it.
 */
export function Brand({ className }: { className?: string }) {
  return (
    <span className={cn('flex items-center gap-2', className)}>
      {/* Height-bound, width automatic: the arms are portrait (100x128) and would be
          squashed by a square box. Empty alt -- the wordmark right next to it already
          names the application, and a screen reader repeating it twice helps nobody. */}
      <img src={wappen} alt="" className="h-5 w-auto" />
      <span className="font-semibold tracking-tight">
        <span className="text-[#ff0000]">h</span>GIS
      </span>
    </span>
  )
}
