import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { CATEGORY_PALETTE, COLOR_RAMPS, sampleRamp } from './defaults'
import { paletteLabel } from './labels'
import { DEFAULT_CATEGORY_PALETTE } from './palettes'

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
        <SelectValue>{(value: string) => paletteLabel(value)}</SelectValue>
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
