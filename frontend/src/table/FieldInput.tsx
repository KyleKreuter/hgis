import { forwardRef } from 'react'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { toInputValue, toWireValue, type FieldKind } from './fieldKind'

const NULL_OPTION = '__null__'

/**
 * The bare input control for one attribute value, generated from `data_type` via
 * `kindOf`. No label, no NULL caption, no eraser button -- those are layout decisions
 * that differ between the attribute form (a stacked field list) and a table cell (26px
 * of height, no room for a caption). Both wrap this.
 *
 * Extracted from `editing/AttributeForm.tsx` (plan section D.4) so the table's cell
 * editor and the form share one mapping instead of two that could drift apart.
 */
export const FieldInput = forwardRef<HTMLInputElement, FieldInputProps>(function FieldInput(
  { id, kind, value, onChange, className, autoFocus },
  ref,
) {
  const isNull = value === null || value === undefined

  if (kind === 'boolean') {
    // Three states, not a switch: a nullable boolean has one more value than a switch can
    // show, and guessing which of the two it means is exactly the wrong move.
    return (
      <Select
        value={isNull ? NULL_OPTION : String(value)}
        onValueChange={(next) => {
          if (!next) return
          onChange(next === NULL_OPTION ? null : next === 'true')
        }}
      >
        <SelectTrigger id={id} className={cn('h-7 w-full text-xs', className)}>
          <SelectValue>
            {(current: string) =>
              current === NULL_OPTION ? 'NULL' : current === 'true' ? 'ja' : 'nein'
            }
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="true">ja</SelectItem>
          <SelectItem value="false">nein</SelectItem>
          <SelectItem value={NULL_OPTION}>NULL</SelectItem>
        </SelectContent>
      </Select>
    )
  }

  if (kind === 'readonly') {
    return (
      <Input
        id={id}
        value={isNull ? '' : String(value)}
        readOnly
        className={cn('h-7 text-xs', className)}
      />
    )
  }

  const inputType =
    kind === 'integer' || kind === 'decimal'
      ? 'number'
      : kind === 'date'
        ? 'date'
        : kind === 'time'
          ? 'time'
          : kind === 'timestamp'
            ? 'datetime-local'
            : 'text'

  return (
    <Input
      ref={ref}
      id={id}
      type={inputType}
      // A timestamp column stores seconds, so its input has to offer them -- left at the
      // default the browser only shows minutes and would zero the seconds of every row
      // the editor is opened on.
      step={
        kind === 'integer' || kind === 'time' || kind === 'timestamp'
          ? 1
          : kind === 'decimal'
            ? 'any'
            : undefined
      }
      value={isNull ? '' : toInputValue(value, kind)}
      className={cn('h-7 text-xs', isNull && 'placeholder:italic', className)}
      placeholder={isNull ? 'NULL' : undefined}
      autoFocus={autoFocus}
      onChange={(event) => {
        const raw = event.target.value
        // An emptied field means NULL, not "". The eraser button on the form is the
        // explicit route, this is the one people take by habit -- both have to land on
        // the same value.
        if (raw === '') return onChange(null)
        onChange(kind === 'integer' || kind === 'decimal' ? Number(raw) : toWireValue(raw, kind))
      }}
    />
  )
})

interface FieldInputProps {
  id?: string
  kind: FieldKind
  value: unknown
  onChange: (value: unknown) => void
  /** Overrides the default `h-7` sizing -- the table's rows are 26px, the form's are not. */
  className?: string
  autoFocus?: boolean
}
