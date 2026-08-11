import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { CATEGORY_PALETTE, COLOR_RAMPS, sampleRamp } from './defaults'

/** Distinct hues, as opposed to the ordered ramps -- the right default for categories. */
export const DEFAULT_CATEGORY_PALETTE = 'categorical'

/** An ordered ramp is what a graduated renderer needs; a hue set would hide the order. */
export const DEFAULT_RAMP = COLOR_RAMPS[0].id

/**
 * `count` colours from the named palette. The categorical one repeats once it runs out
 * -- past eight categories the colours stop telling anything apart anyway, and the
 * alternative would be to invent hues nobody chose.
 */
export function paletteColors(paletteId: string, count: number): string[] {
  if (paletteId === DEFAULT_CATEGORY_PALETTE) {
    return Array.from({ length: count }, (_, index) => CATEGORY_PALETTE[index % CATEGORY_PALETTE.length])
  }
  const ramp = COLOR_RAMPS.find((candidate) => candidate.id === paletteId) ?? COLOR_RAMPS[0]
  return sampleRamp(ramp, count)
}

interface PaletteSelectProps {
  value: string
  onValueChange: (paletteId: string) => void
  /** Categorical hues only make sense for categories, not for ordered classes. */
  includeCategorical?: boolean
}

export function PaletteSelect({ value, onValueChange, includeCategorical = true }: PaletteSelectProps) {
  return (
    <Select value={value} onValueChange={(next) => next && onValueChange(next)}>
      <SelectTrigger size="sm" className="min-w-0 flex-1">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {includeCategorical && (
          <SelectItem value={DEFAULT_CATEGORY_PALETTE}>
            <PalettePreview colors={[...CATEGORY_PALETTE]} />
            Kategorien
          </SelectItem>
        )}
        {COLOR_RAMPS.map((ramp) => (
          <SelectItem key={ramp.id} value={ramp.id}>
            <PalettePreview colors={sampleRamp(ramp, 6)} />
            {ramp.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function PalettePreview({ colors }: { colors: string[] }) {
  return (
    <span className="flex h-3 shrink-0 overflow-hidden rounded-sm">
      {colors.map((color, index) => (
        <span key={index} className="w-2" style={{ backgroundColor: color }} />
      ))}
    </span>
  )
}
