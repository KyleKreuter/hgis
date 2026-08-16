import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

/**
 * One setting per line, label left, control right. Dense on purpose: the panel sits in
 * a dock next to the map, not on a form page.
 */
export function Row({ label, children }: { label: string; children: ReactNode }) {
  // Wraps rather than overflowing: the side panel is resizable down to a narrow strip,
  // and a fixed 5rem label plus its controls needs more room than it has there -- the row
  // then ran out past the panel's edge instead of giving way. basis-40 keeps the controls
  // on the label's line for as long as they fit.
  //
  // min-w-0 on the row itself is what makes that basis a preference instead of a floor.
  // The row is a grid item, so its automatic minimum size is its own min-content -- and a
  // 10rem basis inside puts that at 10rem, which the panel undercuts long before its own
  // minimum width: at 900px the dock goes down to 108px, and the row stood 78px past its
  // edge, reachable only through a second scrollbar. With the minimum lifted the controls
  // wrap onto their own line and then shrink into whatever the panel has.
  return (
    <div className="flex min-h-6 min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
      <span className="w-20 shrink-0 text-xs text-muted-foreground">{label}</span>
      {/*
       * flex-wrap here too: a row with two controls (GraduatedEditor's "Klassen" --
       * `NumberInput` plus `PaletteSelect`) still overflowed at 108px even once it had
       * this whole line to itself. `PaletteSelect`'s trigger has its own unavoidable
       * floor -- chevron, padding and the gap between them, none of which can shrink any
       * further, `min-w-0` or not -- so once two such controls compete for one line
       * there is a width neither can retreat below. Letting them split onto their own
       * lines here is the same idea as the wrap around this div, one level in.
       */}
      <div className="flex min-w-0 flex-1 basis-40 flex-wrap items-center gap-1.5">{children}</div>
    </div>
  )
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="grid gap-1.5 border-t px-2 py-2 first:border-t-0">
      <h3 className="text-xs font-medium tracking-wide uppercase text-muted-foreground">{title}</h3>
      {children}
    </section>
  )
}

interface ColorInputProps {
  value: string
  /**
   * Fires with `{ defer: true }` while the picker is still open and without it once the
   * picker is closed. Callers have to pass `options` straight on to `useStyleEditor`'s
   * `apply`: deferring the closing value too would leave the request waiting for the
   * full defer window after the user is already done choosing.
   */
  onChange: (color: string, options?: { defer?: boolean }) => void
  ariaLabel: string
  className?: string
}

/**
 * The native colour input. No shadcn equivalent exists in this project, and the OS
 * picker is both familiar and the only one that offers an eyedropper.
 *
 * It is the one control here that has no React-visible "done" event: React maps its
 * `onChange` to the DOM's `input` event, which fires continuously while the picker is
 * open. The DOM's own `change` event is what says the picker was closed, and it is
 * subscribed by hand below -- without it every colour change stayed queued for the whole
 * defer window after the picker was already gone.
 */
export function ColorInput({ value, onChange, ariaLabel, className }: ColorInputProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  // Read at event time so the listener, registered once, never calls a stale handler.
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    const input = inputRef.current
    if (!input) return
    const commit = () => onChangeRef.current(input.value)
    input.addEventListener('change', commit)
    return () => input.removeEventListener('change', commit)
  }, [])

  return (
    <input
      ref={inputRef}
      type="color"
      value={value}
      onChange={(event) => onChange(event.target.value, { defer: true })}
      aria-label={ariaLabel}
      title={value}
      className={cn(
        'h-6 w-8 shrink-0 cursor-pointer rounded border border-input bg-transparent p-0.5 outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
        // Without these the browser draws its own inset frame around the swatch, which
        // at this size leaves barely any colour visible.
        '[&::-moz-color-swatch]:rounded-sm [&::-moz-color-swatch]:border-0 [&::-webkit-color-swatch]:rounded-sm [&::-webkit-color-swatch]:border-0 [&::-webkit-color-swatch-wrapper]:p-0',
        className,
      )}
    />
  )
}

interface NumberInputProps {
  value: number
  onChange: (value: number) => void
  label: string
  min?: number
  max?: number
  step?: number
  className?: string
}

/**
 * A number with its own label, for the places where a slider would be overkill.
 *
 * Clamped as it is typed, not on blur: the value goes straight into a PATCH, and the
 * server rejects a width below zero or more than twelve classes with a 400. Correcting
 * an out-of-range digit while typing is the lesser annoyance of the two.
 */
export function NumberInput({ value, onChange, label, min, max, step = 1, className }: NumberInputProps) {
  const id = useId()
  return (
    // Wraps for the same reason `Row` does, and it has to do so itself: its label is a
    // single word and stops shrinking at that word's width, so in the narrowest dock the
    // number was pushed the last 7px past the panel's edge however far it gave way. On
    // its own line it has the whole width of the row to shrink into.
    <div className={cn('flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5', className)}>
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        {label}
      </Label>
      <input
        id={id}
        type="number"
        value={value}
        min={min}
        max={max}
        step={step}
        onChange={(event) => {
          const next = event.target.valueAsNumber
          if (!Number.isFinite(next)) return
          onChange(Math.min(max ?? Number.POSITIVE_INFINITY, Math.max(min ?? Number.NEGATIVE_INFINITY, next)))
        }}
        // w-14 is the width it wants; min-w-0 lets it give way below that in a narrow
        // panel instead of pushing the row past the edge.
        className="h-6 w-14 min-w-0 rounded border border-input bg-transparent px-1.5 text-xs tabular-nums outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      />
    </div>
  )
}
