import type { LayerField } from '@/api/layers'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { columnNameOfField, fieldIdOfColumn, sourceNameOfField } from './classification'
import { ColorInput, NumberInput, Row } from './controls'
import { defaultLabels } from './defaults'
import type { LabelStyle } from './types'

interface LabelEditorProps {
  labels: LabelStyle | null | undefined
  fields: LayerField[]
  onChange: (labels: LabelStyle | null, options?: { defer?: boolean }) => void
}

export function LabelEditor({ labels, fields, onChange }: LabelEditorProps) {
  const enabled = Boolean(labels?.enabled)
  // Kept around while switched off so turning labels back on restores the field and
  // colours instead of starting over. The column name, not the source name -- see
  // `fields.ts` for why every `field` in a style is the column name.
  const current = labels ?? defaultLabels(fields[0]?.columnName ?? '')

  return (
    <>
      <div className="flex items-center gap-2">
        <Checkbox
          id="labels-enabled"
          checked={enabled}
          disabled={fields.length === 0}
          onCheckedChange={(checked) => onChange({ ...current, enabled: checked === true })}
        />
        <Label htmlFor="labels-enabled" className="text-xs font-normal">
          Beschriftung anzeigen
        </Label>
      </div>

      {fields.length === 0 && (
        <p className="text-xs text-muted-foreground">Dieser Layer hat keine Felder zum Beschriften.</p>
      )}

      {enabled && (
        <>
          <Row label="Feld">
            <Select
              value={fieldIdOfColumn(fields, current.field)}
              onValueChange={(value) => value && onChange({ ...current, field: columnNameOfField(fields, value) })}
            >
              <SelectTrigger size="sm" className="w-full">
                <SelectValue placeholder="Feld wählen">
                  {(value: string) => sourceNameOfField(fields, value) || 'Feld wählen'}
                </SelectValue>
              </SelectTrigger>
              <SelectContent>
                {fields.map((field) => (
                  <SelectItem key={field.id} value={field.id}>
                    {field.sourceName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Row>

          <Row label="Schrift">
            <ColorInput
              value={current.color}
              onChange={(color) => onChange({ ...current, color }, { defer: true })}
              ariaLabel="Schriftfarbe"
            />
            <NumberInput
              label="Grösse"
              value={current.size}
              min={6}
              max={48}
              onChange={(size) => onChange({ ...current, size })}
            />
          </Row>

          <Row label="Kontur">
            <ColorInput
              value={current.haloColor}
              onChange={(haloColor) => onChange({ ...current, haloColor }, { defer: true })}
              ariaLabel="Konturfarbe"
            />
            <NumberInput
              label="Breite"
              value={current.haloWidth}
              min={0}
              max={5}
              step={0.5}
              onChange={(haloWidth) => onChange({ ...current, haloWidth })}
            />
          </Row>

          <Row label="Ab Zoom">
            <NumberInput
              label=""
              value={current.minZoom}
              min={0}
              max={22}
              onChange={(minZoom) => onChange({ ...current, minZoom })}
            />
            <div className="flex items-center gap-2">
              <Checkbox
                id="labels-overlap"
                checked={current.allowOverlap}
                onCheckedChange={(checked) => onChange({ ...current, allowOverlap: checked === true })}
              />
              <Label htmlFor="labels-overlap" className="text-xs font-normal text-muted-foreground">
                Überlappung erlauben
              </Label>
            </div>
          </Row>
        </>
      )}
    </>
  )
}
