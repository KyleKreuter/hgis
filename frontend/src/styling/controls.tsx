import { useId, type ReactNode } from 'react'
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
  return (
    <div className="flex min-h-6 flex-wrap items-center gap-x-2 gap-y-1">
      <span className="w-20 shrink-0 text-xs text-muted-foreground">{label}</span>
      <div className="flex min-w-0 flex-1 basis-40 items-center gap-1.5">{children}</div>
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
  /** Fires while the picker is still open, so the caller should defer the request. */
  onChange: (color: string) => void
  ariaLabel: string
  className?: string
}

/**
 * The native colour input. No shadcn equivalent exists in this project, and the OS
 * picker is both familiar and the only one that offers an eyedropper.
 */
export function ColorInput({ value, onChange, ariaLabel, className }: ColorInputProps) {
  return (
    <input
      type="color"
      value={value}
      onChange={(event) => onChange(event.target.value)}
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
    <div className={cn('flex min-w-0 items-center gap-1.5', className)}>
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
