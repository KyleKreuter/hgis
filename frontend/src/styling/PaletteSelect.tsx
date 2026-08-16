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
      {/*
       * min-w-11 (44px), not min-w-0: `min-w-0` alone lets the flex layout squeeze this
       * trigger narrower than its own unshrinkable content -- the chevron (16px),
       * padding (18px), border (2px) and the gap between them (6px), 42px together,
       * none of which can give any further. Next to `NumberInput` in the "Klassen" row
       * (`GraduatedEditor.tsx`), that meant the wrap on `Row`'s content div never
       * triggered (both items' computed minimum read as ~0, so the flex algorithm saw
       * room to fit them side by side) and this trigger was pressed down to ~22px
       * instead, with its own chevron sticking out past its edge. A real floor here
       * makes the row's own wrap trigger honestly, dropping this onto its own line
       * once there truly is no room, rather than crushing it below what it needs.
       */}
      <SelectTrigger size="sm" className="min-w-11 flex-1">
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
