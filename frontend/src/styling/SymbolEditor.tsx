import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { ColorInput, NumberInput, Row } from './controls'
import type { LayerSymbol } from './types'

interface SymbolEditorProps {
  symbol: LayerSymbol
  /** `defer` marks a change that is still in progress -- see `useStyleEditor`. */
  onChange: (symbol: LayerSymbol, options?: { defer?: boolean }) => void
}

/**
 * Edits one symbol. Which controls appear follows the symbol's `kind`, which the panel
 * derives from the layer's geometry type -- a polygon layer never offers a marker size.
 */
export function SymbolEditor({ symbol, onChange }: SymbolEditorProps) {
  if (symbol.kind === 'fill') {
    return (
      <>
        <Row label="Fläche">
          <ColorInput
            value={symbol.fillColor}
            onChange={(fillColor) => onChange({ ...symbol, fillColor }, { defer: true })}
            ariaLabel="Füllfarbe"
          />
          <Slider
            value={symbol.fillOpacity}
            min={0}
            max={1}
            step={0.05}
            onValueChange={(fillOpacity) => onChange({ ...symbol, fillOpacity }, { defer: true })}
            onValueCommitted={(fillOpacity) => onChange({ ...symbol, fillOpacity })}
            aria-label="Deckkraft der Füllung"
          />
          <span className="w-9 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
            {Math.round(symbol.fillOpacity * 100)} %
          </span>
        </Row>
        <Row label="Umriss">
          <ColorInput
            value={symbol.outlineColor}
            onChange={(outlineColor) => onChange({ ...symbol, outlineColor }, { defer: true })}
            ariaLabel="Umrissfarbe"
          />
          {/* MapLibre draws a fill outline as a hairline and offers no width for it. */}
          <span className="text-xs text-muted-foreground">1 px, nicht einstellbar</span>
        </Row>
      </>
    )
  }

  if (symbol.kind === 'line') {
    return (
      <>
        <Row label="Linie">
          <ColorInput
            value={symbol.color}
            onChange={(color) => onChange({ ...symbol, color }, { defer: true })}
            ariaLabel="Linienfarbe"
          />
          <NumberInput
            label="Breite"
            value={symbol.width}
            min={0}
            max={20}
            step={0.25}
            onChange={(width) => onChange({ ...symbol, width })}
          />
        </Row>
        <Row label="Strichart">
          <Select
            value={dashKeyOf(symbol.dashArray)}
            onValueChange={(value) =>
              onChange({ ...symbol, dashArray: DASH_PATTERNS[value as DashKey] })
            }
          >
            <SelectTrigger size="sm" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {DASH_LABELS.map(([key, label]) => (
                <SelectItem key={key} value={key}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Row>
      </>
    )
  }

  return (
    <>
      <Row label="Punkt">
        <ColorInput
          value={symbol.fillColor}
          onChange={(fillColor) => onChange({ ...symbol, fillColor }, { defer: true })}
          ariaLabel="Punktfarbe"
        />
        {/* Radius, not diameter -- that is how `size` reaches `circle-radius`. */}
        <NumberInput
          label="Radius"
          value={symbol.size}
          min={0}
          max={40}
          step={0.5}
          onChange={(size) => onChange({ ...symbol, size })}
        />
      </Row>
      <Row label="Rand">
        <ColorInput
          value={symbol.strokeColor}
          onChange={(strokeColor) => onChange({ ...symbol, strokeColor }, { defer: true })}
          ariaLabel="Randfarbe"
        />
        <NumberInput
          label="Breite"
          value={symbol.strokeWidth}
          min={0}
          max={10}
          step={0.5}
          onChange={(strokeWidth) => onChange({ ...symbol, strokeWidth })}
        />
      </Row>
    </>
  )
}

/** Lengths are multiples of the line width, which is how MapLibre reads a dash array. */
const DASH_PATTERNS = {
  solid: null,
  dashed: [3, 2],
  dotted: [1, 2],
  dashdot: [4, 2, 1, 2],
} satisfies Record<string, number[] | null>

type DashKey = keyof typeof DASH_PATTERNS

const DASH_LABELS: [DashKey, string][] = [
  ['solid', 'Durchgezogen'],
  ['dashed', 'Gestrichelt'],
  ['dotted', 'Gepunktet'],
  ['dashdot', 'Strichpunkt'],
]

function dashKeyOf(dashArray: number[] | null | undefined): DashKey {
  if (!dashArray || dashArray.length === 0) return 'solid'
  const match = (Object.keys(DASH_PATTERNS) as DashKey[]).find(
    (key) => JSON.stringify(DASH_PATTERNS[key]) === JSON.stringify(dashArray),
  )
  return match ?? 'dashed'
}
