import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { validateBasemapUrlTemplate } from './basemap'

/** Not a real basemap id or URL, so it can never collide with a catalog entry or a
 * genuine `https://` value -- the sentinel every picker uses for "type your own URL". */
export const CUSTOM_BASEMAP_OPTION = '__custom-basemap-url__'

interface CustomBasemapUrlFieldProps {
  value: string
  onChange: (value: string) => void
  id?: string
  autoFocus?: boolean
  /**
   * Shows the error even before the field has been touched -- for the moment a submit
   * was attempted with the field still empty or invalid (CONTRACT.md phase 22's
   * `nameError` pattern in `CreateProjectDialog` does the same for the name field).
   */
  showError?: boolean
}

/**
 * One tile-URL input, shared by every picker that offers a free-text basemap
 * (`BasemapControl`, `LayerBasemapDialog`, `CreateProjectDialog`) -- see
 * `validateBasemapUrlTemplate` in `basemap.ts`. The server checks the same shape again
 * on save (VERTRAG.md "Setzen"); this only spares the round trip needed to learn that
 * `{z}` was forgotten.
 */
export function CustomBasemapUrlField({
  value,
  onChange,
  id = 'custom-basemap-url',
  autoFocus,
  showError = false,
}: CustomBasemapUrlFieldProps) {
  const [touched, setTouched] = useState(false)
  const error = validateBasemapUrlTemplate(value)
  const displayError = (touched || showError) && error

  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>Eigene Kachel-URL</Label>
      <Input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onBlur={() => setTouched(true)}
        placeholder="https://beispiel.test/{z}/{x}/{y}.png"
        autoFocus={autoFocus}
        aria-invalid={displayError ? true : undefined}
      />
      {displayError ? (
        <p className="text-xs text-destructive">{error}</p>
      ) : (
        <p className="text-xs text-muted-foreground">
          Muss mit https:// beginnen und {'{z}'}, {'{x}'}, {'{y}'} enthalten -- oder,
          für eine WMS-GetMap-Adresse, {'{bbox-epsg-3857}'}.
        </p>
      )}
    </div>
  )
}
