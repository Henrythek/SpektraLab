// EditorWindow.tsx — three panels and a filmstrip floating on the window's
// ground (ARCHITECTURE §7.1): Film and Print (left) | the stage — top bar over
// the canvas or the film board — with the filmstrip under it | Parameters
// (right).

import { Canvas } from '../canvas/Canvas';
import { FilmBoard } from '../panels/FilmBoard';
import { LeftRail } from '../panels/LeftRail';
import { RightRail } from '../panels/RightRail';
import { useSettings } from '../state/settings';
import { useSession } from '../state/session';
import { Filmstrip } from './Filmstrip';
import { TopBar } from './TopBar';
import { AboutDialog, HostFailure, SettingsDialog } from './Dialogs';
import { ExportPage } from '../export/ExportPage';

export function EditorWindow() {
  const left = useSettings((s) => s.leftCollapsed);
  const right = useSettings((s) => s.rightCollapsed);
  const strip = useSettings((s) => s.filmstripCollapsed);
  const board = useSettings((s) => s.workspace === 'board');
  const batch = useSession((s) => s.batchExporting);
  return (
    <>
      <div
        className="editor"
        style={{
          gridTemplateColumns: [left ? '' : 'var(--left-width)', 'minmax(0, 1fr)', right ? '' : 'var(--right-width)'].filter(Boolean).join(' '),
          pointerEvents: batch ? 'none' : undefined,
        }}
        data-testid="editor"
      >
        {!left && <LeftRail />}
        <main className="centre">
          <div className="stage">
            <TopBar />
            <div style={{ position: 'relative', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
              {/* The canvas stays mounted (and measured) under the board. */}
              <Canvas />
              {board && (
                <div style={{ position: 'absolute', inset: 0, display: 'flex', background: 'var(--ground)', zIndex: 2 }}>
                  <FilmBoard />
                </div>
              )}
            </div>
            <HostFailure />
          </div>
          {!strip && <Filmstrip />}
        </main>
        {!right && <RightRail />}
      </div>
      <SettingsDialog />
      <AboutDialog />
      <ExportPage />
    </>
  );
}
