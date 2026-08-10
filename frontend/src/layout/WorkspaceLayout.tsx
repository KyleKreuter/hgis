import type { ReactNode } from 'react'
import { useDefaultLayout } from 'react-resizable-panels'
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from '@/components/ui/resizable'

interface WorkspaceLayoutProps {
  toolbar: ReactNode
  leftDock: ReactNode
  map: ReactNode
  attributes: ReactNode
}

/**
 * The QGIS style dock: layer tree on the left, map in the middle, attribute table
 * docked at the bottom.
 *
 * The layout owns the viewport height. Every panel scrolls internally; the page
 * itself never scrolls, which is why body carries overflow-hidden.
 *
 * Panel sizes survive reloads. In react-resizable-panels v4 that is no longer an
 * autoSaveId prop but the useDefaultLayout hook, which hands back defaultLayout
 * plus the change callbacks to spread onto the group.
 */
export function WorkspaceLayout({
  toolbar,
  leftDock,
  map,
  attributes,
}: WorkspaceLayoutProps) {
  const horizontalLayout = useDefaultLayout({
    id: 'hgis-workspace-h',
    storage: typeof window === 'undefined' ? undefined : window.localStorage,
    onlySaveAfterUserInteractions: true,
  })

  const verticalLayout = useDefaultLayout({
    id: 'hgis-workspace-v',
    storage: typeof window === 'undefined' ? undefined : window.localStorage,
    onlySaveAfterUserInteractions: true,
  })

  return (
    <div className="flex h-dvh flex-col overflow-hidden">
      <header className="flex h-10 shrink-0 items-center gap-2 border-b bg-card px-2">
        {toolbar}
      </header>

      <ResizablePanelGroup orientation="horizontal" className="flex-1" {...horizontalLayout}>
        {/*
          Sizes are strings on purpose. In react-resizable-panels v4 a bare number
          means pixels, a string means percent -- the inverse of v3, where numbers
          were percentages.
        */}
        <ResizablePanel id="dock" defaultSize="20%" minSize="12%" maxSize="40%">
          {leftDock}
        </ResizablePanel>

        <ResizableHandle withHandle />

        <ResizablePanel id="main" defaultSize="80%">
          <ResizablePanelGroup orientation="vertical" {...verticalLayout}>
            <ResizablePanel id="map" defaultSize="65%" minSize="20%">
              {map}
            </ResizablePanel>

            <ResizableHandle withHandle />

            <ResizablePanel id="attributes" defaultSize="35%" minSize="8%" collapsible>
              {attributes}
            </ResizablePanel>
          </ResizablePanelGroup>
        </ResizablePanel>
      </ResizablePanelGroup>
    </div>
  )
}
