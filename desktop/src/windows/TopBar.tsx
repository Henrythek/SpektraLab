// TopBar.tsx — the stage's own bar. Left to right:
// ◧ · breadcrumb (SpektraLab / the open photo, or Film stocks on the board) ·
// [import export] · [select crop] · Process · Original · … status … ·
// before/after · [− zoom % +] · [Board | Editor] · theme · ◨
// The breadcrumb and the Board/Editor switch come from the board-of-cards
// reference the layout follows; selection is orange (Capture One's convention).

import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { BeforeAfter, BoardIcon, Crop, Cursor, EditorIcon, Export, Import, Moon, SidebarLeft, SidebarRight, Sun, ZoomIn, ZoomOut } from '../controls/icons';
import { isChinese, t, tz } from '../i18n';
import { currentFitZoom, setOriginal, setPages, setTool, solveNow, toggleCompare, useSession, zoomStep, zoomTo, zoomToFit } from '../state/session';
import { settingsStore, useSettings } from '../state/settings';
import { openDialog } from './actions';
import { baseName } from '../platform';

export function TopBar() {
  const leftCollapsed = useSettings((s) => s.leftCollapsed);
  const rightCollapsed = useSettings((s) => s.rightCollapsed);
  const workspace = useSettings((s) => s.workspace);
  const theme = useSettings((s) => s.theme);
  const tool = useSession((s) => s.tool);
  const selection = useSession((s) => s.selection);
  const engine = useSession((s) => s.engineSession);
  const developing = useSession((s) => s.developing);
  const original = useSession((s) => s.showingOriginal);
  const comparing = useSession((s) => s.comparing);
  const status = useSession((s) => s.status);
  const view = useSession((s) => s.view);
  const native = useSession((s) => s.nativeSize);
  const hello = useSession((s) => s.hello);
  const zoomLocked = tool === 'crop';
  const fitZoom = useSession((s) => s.fitZoom);
  const pct = Math.round((view.fit ? fitZoom : view.zoom) * 100);
  const board = workspace === 'board';
  const info = selection ? [native ? `${native.width}×${native.height}` : '', hello?.mock ? tz('mock host', '模拟主机') : 'sRGB'].filter(Boolean).join(' · ') : '';
  const setWorkspace = (w: 'editor' | 'board') => settingsStore.getState().set('workspace', w);
  return (
    <div className="topbar" data-testid="topbar">
      <button className="icon-btn dim" title={leftCollapsed ? t('helpDevelopRailShow') : t('helpDevelopRailHide')} onClick={() => settingsStore.getState().set('leftCollapsed', !leftCollapsed)}>
        <SidebarLeft />
      </button>
      <div className="crumbs">
        <span className="crumb">SpektraLab</span>
        <span className="crumb-sep">/</span>
        <span className="here" title={selection ?? undefined}>
          {board ? tz('Film stocks', '胶片库') : selection ? baseName(selection) : tz('No photo open', '未打开照片')}
        </span>
      </div>
      <span style={{ width: 10 }} />
      {!board && (
        <>
          <div className="toolgroup">
            <button className="icon-btn" title={t('helpOpen') + ' (Ctrl+O)'} onClick={() => void openDialog()} data-testid="open-button">
              <Import />
            </button>
            <button className="icon-btn" title={t('helpExport') + ' (Ctrl+E)'} disabled={!selection} onClick={() => setPages({ exportOpen: true })} data-testid="export-button">
              <Export />
            </button>
          </div>
          <div className="toolgroup">
            <button className={'icon-btn ' + (tool === 'select' ? 'active' : 'dim')} title={t('helpSelect') + ' (V)'} onClick={() => setTool('select')}>
              <Cursor />
            </button>
            <button className={'icon-btn ' + (tool === 'crop' ? 'active' : 'dim')} title={tz('Crop (C)', '裁剪（C）')} onClick={() => setTool(tool === 'crop' ? 'select' : 'crop')} data-testid="crop-tool">
              <Crop />
            </button>
          </div>
          <span style={{ width: 4 }} />
          <button
            className={'action-pill' + (engine && !developing ? ' active' : '')}
            disabled={!engine || developing}
            onClick={() => void solveNow()}
            title={tz('Auto-exposure and the enlarger filter pack for this paper — print this frame.', '自动曝光并为这张相纸求解放大机滤色——印放这张照片。')}
            data-testid="process"
          >
            {t('actionSolve')}
          </button>
          <button className={'action-pill' + (original ? ' active' : '')} disabled={!selection} onClick={() => setOriginal(!original)} title={tz('Show the camera’s own rendering, before any film simulation (Space, while held).', '显示相机自身的渲染，未经胶片模拟（按住空格键）。')}>
            {t('actionOriginal')}
          </button>
        </>
      )}
      <span className="bar-status" data-testid="status" title={status || info}>
        {developing ? '⏳ ' : ''}
        {status || info}
      </span>
      {!board && (
        <>
          <button className={'icon-btn' + (comparing ? ' active' : '')} style={{ width: 36 }} disabled={!selection} onClick={toggleCompare} title={t('helpBeforeAfter') + ' (Y)'}>
            <BeforeAfter />
          </button>
          <div className="toolgroup">
            <button className="icon-btn" disabled={zoomLocked || !selection} title={t('helpZoomOut') + ' (Ctrl+−)'} onClick={() => zoomStep(-1, currentFitZoom)}>
              <ZoomOut />
            </button>
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild disabled={zoomLocked}>
                <button className="zoom-pill" title={view.fit ? tz('Fitted to the window (,)', '适合窗口（,）') : tz('Zoom', '缩放')} data-testid="zoom-pill">
                  {selection ? `${pct} %` : '—'}
                </button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content className="menu" sideOffset={6}>
                  <DropdownMenu.Item className="menu-item" onSelect={zoomToFit}>
                    <span className="check">{view.fit ? '✓' : ''}</span>
                    {t('helpFit')}
                  </DropdownMenu.Item>
                  {[0.25, 0.5, 1, 2, 4].map((f) => (
                    <DropdownMenu.Item key={f} className="menu-item" onSelect={() => zoomTo(f)}>
                      <span className="check">{!view.fit && view.zoom === f ? '✓' : ''}</span>
                      {f * 100} %
                    </DropdownMenu.Item>
                  ))}
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
            <button className="icon-btn" disabled={zoomLocked || !selection} title={t('helpZoomIn') + ' (Ctrl++)'} onClick={() => zoomStep(1, currentFitZoom)}>
              <ZoomIn />
            </button>
          </div>
        </>
      )}
      <div className="seg" role="tablist" aria-label={tz('Workspace', '工作区')}>
        <button className={board ? 'on' : ''} role="tab" aria-selected={board} onClick={() => setWorkspace('board')} title={tz('Every film stock as a board (B)', '以看板浏览全部胶片（B）')} data-testid="workspace-board">
          <BoardIcon />
          {tz('Board', '看板')}
        </button>
        <button className={!board ? 'on' : ''} role="tab" aria-selected={!board} onClick={() => setWorkspace('editor')} title={tz('The picture (E)', '画面（E）')} data-testid="workspace-editor">
          <EditorIcon />
          {tz('Editor', '编辑')}
        </button>
      </div>
      <button
        className="icon-btn dim lang-btn"
        title={isChinese() ? 'Switch to English' : '切换到简体中文'}
        onClick={() => settingsStore.getState().set('language', isChinese() ? 'english' : 'simplifiedChinese')}
        data-testid="language-toggle"
      >
        {isChinese() ? 'EN' : '中'}
      </button>
      <button
        className="icon-btn dim"
        title={theme === 'dark' ? tz('Light appearance', '浅色外观') : tz('Dark appearance', '深色外观')}
        onClick={() => settingsStore.getState().set('theme', theme === 'dark' ? 'light' : 'dark')}
        data-testid="theme-toggle"
      >
        {theme === 'dark' ? <Sun /> : <Moon />}
      </button>
      <button className="icon-btn dim" title={rightCollapsed ? t('helpEditRailShow') : t('helpEditRailHide')} onClick={() => settingsStore.getState().set('rightCollapsed', !rightCollapsed)}>
        <SidebarRight />
      </button>
    </div>
  );
}
