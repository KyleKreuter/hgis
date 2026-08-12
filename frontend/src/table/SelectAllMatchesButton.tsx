import { useState } from 'react'
import { CheckSquare, Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { ApiError } from '@/api/client'
import { fetchFeatureFids } from '@/api/features'
import { useSelection } from '@/state/selection'
import type { FilterMode } from './filterMode'
import { needsSelectAllConfirmation } from './selectAllMatches'
import { SelectAllMatchesConfirmDialog } from './SelectAllMatchesConfirmDialog'

interface SelectAllMatchesButtonProps {
  layerId: string
  mode: FilterMode
  /** The committed filter/search text -- whichever `mode` is active, not the draft still being typed. */
  value: string
  /** Already known from the feature page's first response; no request of its own needed (CONTRACT.md). */
  totalCount: number
}

type Phase = { type: 'idle' } | { type: 'loading' } | { type: 'confirm' }

const GENERIC_ERROR = 'Das Programm konnte die Auswahl nicht laden'

/**
 * Bridges a filter/search restriction to the selection store -- what makes the
 * existing "Auswahl exportieren" menu entry usable for a filtered or searched set,
 * without the export itself knowing anything changed (CONTRACT.md).
 *
 * Hidden while the field is empty: an empty restriction would select the whole layer
 * under a button that reads as if it did something targeted.
 */
export function SelectAllMatchesButton({ layerId, mode, value, totalCount }: SelectAllMatchesButtonProps) {
  const [phase, setPhase] = useState<Phase>({ type: 'idle' })
  const select = useSelection((state) => state.select)

  const trimmed = value.trim()

  async function load() {
    setPhase({ type: 'loading' })
    try {
      const { fids } = await fetchFeatureFids({
        layerId,
        filter: mode === 'filter' ? trimmed : undefined,
        search: mode === 'search' ? trimmed : undefined,
      })
      select(layerId, fids, 'replace')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : GENERIC_ERROR)
    } finally {
      setPhase({ type: 'idle' })
    }
  }

  function handleClick() {
    if (needsSelectAllConfirmation(totalCount)) {
      setPhase({ type: 'confirm' })
    } else {
      void load()
    }
  }

  if (!trimmed) return null

  return (
    <>
      <Button
        variant="ghost"
        size="icon-sm"
        className="size-5 shrink-0"
        aria-label="Alle Treffer auswählen"
        title="Alle Treffer auswählen"
        disabled={phase.type === 'loading'}
        onClick={handleClick}
      >
        {phase.type === 'loading' ? (
          <Loader2 className="size-3 animate-spin" />
        ) : (
          <CheckSquare className="size-3" />
        )}
      </Button>

      {phase.type === 'confirm' && (
        <SelectAllMatchesConfirmDialog
          totalCount={totalCount}
          onConfirm={() => void load()}
          onCancel={() => setPhase({ type: 'idle' })}
        />
      )}
    </>
  )
}
