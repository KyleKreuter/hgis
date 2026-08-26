import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { renderWithQueryClient } from '@/test/render'
import { MapContext } from '../MapContext'
import { OSM_ATTRIBUTION } from '../basemap'
import { MapImageDialog } from './MapImageDialog'

/**
 * The dialog is where the size the user picked meets the size the graphics card allows.
 * Rendering itself needs WebGL and is measured in the browser instead; what is under test
 * here is the arithmetic the user sees before anything is drawn, and the refusal that
 * has to appear while picking rather than after clicking.
 */

/** jsdom has no canvas at all, so every test says what WebGL is supposed to answer. */
function stubGl(maxRenderbufferSize: number | null) {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(((type: string) => {
    if (type !== 'webgl2' && type !== 'webgl') return null
    if (maxRenderbufferSize === null) return null
    return {
      MAX_RENDERBUFFER_SIZE: 0x84e8,
      getParameter: () => maxRenderbufferSize,
      getExtension: () => null,
    }
  }) as unknown as HTMLCanvasElement['getContext'])
}

function renderDialog() {
  return renderWithQueryClient(
    <MapContext value={{ mapRef: { current: null }, isLoaded: false, attribution: OSM_ATTRIBUTION }}>
      <MapImageDialog
        open
        onOpenChange={() => {}}
        projectName="Baumkataster"
        attribution={OSM_ATTRIBUTION}
      />
    </MapContext>,
  )
}

async function chooseOption(triggerName: string, optionName: string | RegExp) {
  const user = userEvent.setup()
  await user.click(screen.getByRole('combobox', { name: triggerName }))
  await user.click(await screen.findByRole('option', { name: optionName }))
}

describe('MapImageDialog', () => {
  beforeEach(() => {
    stubGl(16384)
  })

  test('füllt den Titel mit dem Projektnamen vor', () => {
    renderDialog()

    expect(screen.getByLabelText('Titel')).toHaveValue('Baumkataster')
  })

  test('nennt die Bildgröße für die Vorauswahl', () => {
    renderDialog()

    expect(screen.getByText(/1754 × 1240 Pixel/)).toBeInTheDocument()
  })

  test('rechnet die Bildgröße neu, wenn das Format wechselt', async () => {
    renderDialog()

    await chooseOption('Seitenformat', 'A4 hoch')

    await waitFor(() => expect(screen.getByText(/1240 × 1754 Pixel/)).toBeInTheDocument())
  })

  test('rechnet die Bildgröße neu, wenn die Auflösung wechselt', async () => {
    renderDialog()

    await chooseOption('Auflösung', /300 dpi/)

    await waitFor(() => expect(screen.getByText(/3508 × 2480 Pixel/)).toBeInTheDocument())
  })

  /**
   * CONTRACT.md 13.3: beyond what WebGL allows the export refuses and names the ceiling.
   * A quietly smaller image would be worse than no image.
   */
  test('lehnt ein zu großes Bild ab und nennt die größte mögliche Größe', async () => {
    stubGl(2048)
    renderDialog()

    await chooseOption('Auflösung', /300 dpi/)

    await waitFor(() => expect(screen.getByText(/2048 × 2048 Pixel/)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Bild erzeugen' })).toBeDisabled()
  })

  test('lässt eine Größe zu, die die Grafikkarte schafft', () => {
    renderDialog()

    expect(screen.getByRole('button', { name: 'Bild erzeugen' })).toBeEnabled()
  })

  test('bietet eine standardmäßig aktivierte Checkbox für die Legende', () => {
    renderDialog()

    const checkbox = screen.getByRole('checkbox', { name: 'Legende anzeigen' })
    expect(checkbox).toBeInTheDocument()
    expect(checkbox).toBeChecked()
  })
})
