import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { House, LandPlot, Loader2, MapPin, Route, Search, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { MIN_QUERY_LENGTH, usePlaceSearch, type Place, type PlaceKind } from '@/api/places'
import { moveHighlight } from './placeSearchNav'

/** Typing pause before a search runs -- same value and reasoning as `table/FilterBar`. */
const DEBOUNCE_MS = 300

/**
 * Shown next to every hit (CONTRACT.md "Ortssuche": "Die Herkunft steht dabei"). Text,
 * not just an icon -- an official Hamburg street and an OpenStreetMap guess carry a
 * different kind of trust, and that difference has to be legible without a hover.
 */
const SOURCE_LABEL: Record<Place['source'], string> = {
  hamburg: 'Hamburg amtlich',
  photon: 'OpenStreetMap',
}

/**
 * `House` for an address, not a second pin: `MapPin` already stands for "a place
 * somewhere" and `Route` for the street the address sits on -- the one thing a user has
 * to read off a hit at a glance is whether they got the whole street or the single
 * building, and two pin-shaped icons would put exactly that distinction into the label
 * only. A house outline shares no silhouette with any of the three.
 */
const KIND_ICON: Record<PlaceKind, typeof MapPin> = {
  street: Route,
  district: LandPlot,
  place: MapPin,
  address: House,
}

const KIND_LABEL: Record<PlaceKind, string> = {
  street: 'Straße',
  district: 'Ortsteil',
  place: 'Ort',
  address: 'Adresse',
}

function optionId(listboxId: string, index: number): string {
  return `${listboxId}-option-${index}`
}

interface PlaceSearchControlProps {
  /** A hit was chosen -- the caller flies the map there and drops a marker. */
  onSelect: (place: Place) => void
  /** The field was emptied out (the clear button) -- the caller removes the marker too. */
  onClear: () => void
}

/**
 * The search field for the Ortssuche (CONTRACT.md), floating over the map.
 *
 * Placed on the map itself rather than in the workspace header: it is a way of looking
 * at the map, the same role the zoom stack and the compass play, not a project-level
 * action like importing a layer -- those live in the header and stay there. Top centre
 * is the one spot free of the app's other floating pieces: the zoom/compass/reset
 * column and the basemap/export controls sit in the right corners, the measurement
 * readout appears top left while a measurement is running.
 *
 * Built from plain elements rather than a listbox primitive -- none exists among the
 * base-ui components already in use here (`select.tsx` wraps a native `<select>`-like
 * widget, not an open-vocabulary combobox). The result rows are deliberately not
 * separately focusable: focus stays on the input the entire time, the highlighted row
 * is tracked as an index and only ever communicated visually and via
 * `aria-activedescendant`, exactly as the WAI-ARIA combobox pattern describes it.
 *
 * That does *not* mean a click on a row leaves the input's focus alone on its own --
 * a real browser blurs whatever is currently focused on `mousedown`, for any new click
 * target, whether or not that target can itself take focus. A first version of this
 * component assumed the opposite ("clicking a non-focusable row never moves focus away
 * from the input") and shipped with the mouse path broken: `onBlur` below closed the
 * panel before the row's own `click` -- which fires after `mouseup` -- had anything left
 * to land on, so choosing a hit with the mouse silently did nothing. `PlaceOption`'s
 * `onMouseDown={(event) => event.preventDefault()}` is what keeps focus on the input
 * through the click and is why `onBlur` here is still safe to leave unconditional.
 * jsdom's plain `fireEvent.click` does not reproduce the browser's blur-on-mousedown
 * behaviour and would have hidden this; `@testing-library/user-event`'s `click()` does,
 * see the regression test in `PlaceSearchControl.test.tsx`.
 */
export function PlaceSearchControl({ onSelect, onClear }: PlaceSearchControlProps) {
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [open, setOpen] = useState(false)
  // -1 means the input itself, not any row -- see `moveHighlight`.
  const [highlighted, setHighlighted] = useState(-1)
  const inputRef = useRef<HTMLInputElement>(null)
  const listboxId = useId()

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [query])

  // Gated on `open`, not only on length: without this, choosing a result set `query`
  // to the chosen name -- a *different* string from what produced the open list -- and
  // the debounce above would fire a search for it a moment later, purely to populate a
  // dropdown nothing shows anymore.
  const trimmedQuery = query.trim()
  // The debounce has not caught up with the latest keystroke yet -- distinct from a
  // finished search that genuinely found nothing, which is the one case allowed to say
  // "Kein Ort gefunden": without this, the panel showed that message for the ~300 ms
  // between crossing three characters and the first request actually going out.
  const debouncePending = trimmedQuery !== debouncedQuery.trim()
  const search = usePlaceSearch(open ? debouncedQuery : '')
  const places = search.data?.places ?? []
  const showPanel = open && trimmedQuery.length >= MIN_QUERY_LENGTH
  const loading = debouncePending || search.isFetching
  // Deliberately not `search.error.message`: unlike a curated field-validation message
  // (`table/FilterBar`, the edit form), nothing this endpoint can fail with is meant for
  // a user to read. The one business error the contract defines (`q` under two
  // characters) never reaches the network -- `MIN_QUERY_LENGTH` above is stricter than
  // that. Everything else is a technical failure, and the technical text can be exactly
  // that technical: with the endpoint not deployed yet, a 404 answered
  // "Die Ressource 'api/places' existiert nicht" -- accurate, and unusable in a search
  // box, since it names an internal path and reads like a crash rather than an outage.
  const errorMessage = search.isError ? 'Die Ortssuche ist gerade nicht erreichbar.' : null

  // The previous highlight belonged to the previous result set -- row 4 of nine
  // pointing at whatever row 4 of a fresh three-row answer happens to be would move
  // the selection to a place the user never looked at.
  useEffect(() => {
    setHighlighted(-1)
    // `search.data`, not `places`: the latter is a fresh `?? []` fallback array on
    // every render, which would reset the highlight on every keystroke and mouse
    // hover alike, not only when a new answer actually arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search.data])

  function selectPlace(place: Place) {
    onSelect(place)
    setQuery(place.name)
    // Keeps the debounce effect above from re-firing a search for the now-selected
    // name (see the comment on `search`) -- query and debouncedQuery agree immediately.
    setDebouncedQuery(place.name)
    setOpen(false)
    setHighlighted(-1)
  }

  function handleChange(value: string) {
    setQuery(value)
    setOpen(value.trim().length >= MIN_QUERY_LENGTH)
  }

  function handleClear() {
    setQuery('')
    setDebouncedQuery('')
    setOpen(false)
    setHighlighted(-1)
    onClear()
    inputRef.current?.focus()
  }

  function handleFocus() {
    if (trimmedQuery.length >= MIN_QUERY_LENGTH) setOpen(true)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      // Reopens rather than doing nothing: dismissing the list with Escape must not be
      // a dead end that forces retyping just to see it again.
      if (!open) {
        if (trimmedQuery.length >= MIN_QUERY_LENGTH) {
          event.preventDefault()
          setOpen(true)
        }
        return
      }
      event.preventDefault()
      setHighlighted((current) => moveHighlight(current, places.length, event.key === 'ArrowDown' ? 'down' : 'up'))
      return
    }
    if (event.key === 'Enter') {
      if (places.length === 0) return
      event.preventDefault()
      // No row highlighted yet -- Enter still has to do something useful on the very
      // first press, so it takes the top hit, the one the server itself ranked first.
      selectPlace(places[highlighted >= 0 ? highlighted : 0])
      return
    }
    if (event.key === 'Escape') {
      if (!open) return
      event.preventDefault()
      setOpen(false)
      setHighlighted(-1)
    }
  }

  return (
    <div
      // z-20, not the z-10 every other top control uses: `MeasurementReadout` sits at
      // top left with a generous max-width (`calc(100cqw-6rem)`) that was sized against
      // the top-right button stack, not against a top-centre control that did not exist
      // yet. Measured on screen: its own hint text ("Klicken Sie, um Punkte zu
      // setzen...") already reaches to about the middle of an ordinary map panel, well
      // into where this field sits. Same fix `MapCanvas` already applies to the
      // attribution notice versus the scale bar for exactly the same reason -- the
      // element the user is actively reading or typing into wins the overlap, the
      // passive status readout underneath it gives way.
      className="absolute top-2 left-1/2 z-20 w-72 max-w-[calc(100cqw-1rem)] -translate-x-1/2 @max-xs:w-56"
    >
      <div className="relative">
        {/* The spinner replaces the magnifier on the left, not the clear button on the
            right. It used to sit on the right, where it swapped places with the X on
            every single keystroke -- and the two were never the same shape: measured in
            the browser, the X sits at x=1078 and is 24px wide, the spinner sat at
            x=1082 and was 14px. So each keypress moved the icon 4px sideways, shrank it
            by 10px and set it spinning, then put the X back. While typing, that flickers
            continuously. On the left there is nothing to displace: the magnifier is
            decoration, it and the spinner share position and size, and the swap is a
            change of glyph rather than a jump. */}
        {showPanel && loading ? (
          <Loader2 className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 animate-spin text-muted-foreground" />
        ) : (
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
        )}
        <Input
          ref={inputRef}
          role="combobox"
          aria-expanded={showPanel}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={highlighted >= 0 ? optionId(listboxId, highlighted) : undefined}
          aria-label="Straße oder Ort suchen"
          value={query}
          placeholder="Straße oder Ort suchen"
          onChange={(event) => handleChange(event.target.value)}
          onFocus={handleFocus}
          onBlur={() => setOpen(false)}
          onKeyDown={handleKeyDown}
          autoComplete="off"
          className="bg-background pr-8 pl-8 shadow-sm"
        />
        {query && (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            // A plain X, deliberately motionless -- shaped like the toolbars' own close
            // buttons (`EditToolbar`, `MeasurementToolbar`: ghost, `icon-sm`, `size-7`).
            //
            // Centred with `inset-y-0 my-auto` rather than `top-1/2 -translate-y-1/2`:
            // that -50% base offset met the primitive's `active:translate-y-px` on every
            // press, leaving `transition-all` to interpolate a percentage against a
            // pixel -- a visible wobble the toolbars never show, since they carry no
            // base transform. `transition-none` and the neutralised `active:` offset
            // then go one step further than the toolbars do, because this button sits
            // inside a field the user is typing into, where the eye is already busy.
            className="absolute inset-y-0 right-1 my-auto size-7 transition-none active:translate-y-0"
            aria-label="Suche löschen"
            // Fires before the input's onBlur only in event order, not in effect --
            // both run, and clearing after closing is harmless either way.
            onClick={handleClear}
          >
            <X className="size-3.5" />
          </Button>
        )}
      </div>

      {showPanel && (
        <div className="absolute inset-x-0 top-full z-10 mt-1 overflow-hidden rounded-lg border bg-popover text-popover-foreground shadow-md ring-1 ring-foreground/10">
          {errorMessage ? (
            <p className="p-2 text-xs text-destructive">{errorMessage}</p>
          ) : places.length === 0 ? (
            loading ? (
              <p className="p-2 text-xs text-muted-foreground">Suche läuft…</p>
            ) : (
              <p className="p-2 text-xs text-muted-foreground">Kein Ort gefunden.</p>
            )
          ) : (
            <ul id={listboxId} role="listbox" aria-label="Trefferliste" className="max-h-80 overflow-y-auto p-1">
              {places.map((place, index) => (
                <PlaceOption
                  key={`${place.source}-${place.lng}-${place.lat}-${index}`}
                  id={optionId(listboxId, index)}
                  place={place}
                  highlighted={index === highlighted}
                  onSelect={() => selectPlace(place)}
                  onHover={() => setHighlighted(index)}
                />
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

function PlaceOption({
  id,
  place,
  highlighted,
  onSelect,
  onHover,
}: {
  id: string
  place: Place
  highlighted: boolean
  onSelect: () => void
  onHover: () => void
}) {
  const Icon = KIND_ICON[place.kind]
  return (
    <li id={id} role="option" aria-selected={highlighted}>
      {/* A plain div, not a button, so it never becomes a *tab stop* -- keyboard
          selection goes entirely through the input's own onKeyDown (see the component
          comment). That does not make it focus-proof on a click, though: a real browser
          blurs whatever is currently focused on `mousedown` for ANY new click target,
          focusable or not (focus falls back to `document.body` here). Without
          `preventDefault` on mousedown, that blur runs the input's `onBlur` and closes
          the panel before the `click` that follows `mouseup` has a row left to land on
          -- `onSelect` below would then never fire. jsdom's `fireEvent.click` skips this
          entirely, which is what let the bug ship; `@testing-library/user-event`'s
          `click()` reproduces it, see `PlaceSearchControl.test.tsx`. */}
      <div
        onClick={onSelect}
        onMouseDown={(event) => event.preventDefault()}
        onMouseEnter={onHover}
        className={cn(
          'flex cursor-default items-start gap-2 rounded px-2 py-1.5 text-xs',
          highlighted ? 'bg-accent' : 'hover:bg-accent/50',
        )}
      >
        <span title={KIND_LABEL[place.kind]} className="mt-0.5 shrink-0">
          <Icon className="size-3.5 text-muted-foreground" aria-hidden="true" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
            <span className="font-medium">{place.name}</span>
            <Badge variant="secondary" className="shrink-0 font-normal">
              {SOURCE_LABEL[place.source]}
            </Badge>
          </span>
          {/* Never truncated (CONTRACT.md: "er darf nie wegfallen oder abgeschnitten
              werden, solange Platz ist") -- for three same-named streets this is the
              only line that tells them apart, so it wraps instead of clipping. */}
          {place.context && <span className="block text-muted-foreground">{place.context}</span>}
        </span>
      </div>
    </li>
  )
}
