// FilmBoard.tsx — every film stock as a board of cards (the layout reference's
// Agent inbox): a lane per family with its count, a card per stock showing
// the stock's own edge print on a strip of film. A click opens the card in
// place with what the film is and prints on, and the two actions; "Use film"
// applies it and, with a photo open, goes back to the picture.

import { useState } from 'react';
import { Check, Spark } from '../controls/icons';
import { tz } from '../i18n';
import { setParams, useSession } from '../state/session';
import { settingsStore } from '../state/settings';
import { edgeText } from '@shared/filmEdge';
import type { Stock, StockCatalog } from '@shared/stocks';

type Family = 'negative' | 'positive' | 'mono' | 'cine';

export function familyOf(s: Stock): Family {
  if (s.use === 'cine') return 'cine';
  if (s.monochrome) return 'mono';
  return s.type === 'positive' ? 'positive' : 'negative';
}

const LANES: { family: Family; title: () => string }[] = [
  { family: 'negative', title: () => tz('Colour negative', '彩色负片') },
  { family: 'positive', title: () => tz('Slide', '反转片') },
  { family: 'mono', title: () => tz('Black & white', '黑白') },
  { family: 'cine', title: () => tz('Cinema', '电影胶片') },
];

function blurb(s: Stock, catalog: StockCatalog): string {
  const paper = s.targetPrint ? catalog.stock(s.targetPrint)?.name : null;
  switch (familyOf(s)) {
    case 'positive':
      return tz('Slide film, scanned directly: no print stage, deep blacks and saturated colour.', '反转片直接扫描：没有相纸阶段，黑位深，色彩饱和。');
    case 'mono':
      return paper ? tz(`Silver negative, printed on ${paper}.`, `银盐负片，印放在 ${paper} 上。`) : tz('Silver negative.', '银盐负片。');
    case 'cine':
      return paper ? tz(`Motion-picture negative, printed on ${paper}.`, `电影负片，印放在 ${paper} 上。`) : tz('Motion-picture negative.', '电影负片。');
    default:
      return paper ? tz(`Colour negative, printed on ${paper}.`, `彩色负片，印放在 ${paper} 上。`) : tz('Colour negative.', '彩色负片。');
  }
}

export function FilmBoard() {
  const catalog = useSession((s) => s.catalog);
  const inUse = useSession((s) => s.sidecar.params.filmStock);
  const selection = useSession((s) => s.selection);
  const [open, setOpen] = useState<string | null>(null);
  const films = catalog.films;
  const use = (s: Stock, withPaper: boolean) => {
    setParams((p) => ({
      ...p,
      filmStock: s.id,
      ...(withPaper && s.targetPrint ? { printStock: s.targetPrint, scanFilm: false, digitalIntermediate: false } : {}),
    }));
    setOpen(null);
    if (selection) settingsStore.getState().set('workspace', 'editor');
  };
  return (
    <div className="board" data-testid="film-board">
      {LANES.map((lane) => {
        const stocks = films.filter((f) => familyOf(f) === lane.family);
        return (
          <section className="lane" key={lane.family} data-lane={lane.family}>
            <header className="lane-header">
              <span className="spark" style={{ color: 'var(--spark)', display: 'inline-flex' }}>
                <Spark />
              </span>
              <span className="title">{lane.title()}</span>
              <span className="count">{stocks.length}</span>
            </header>
            {stocks.length === 0 && <div className="empty">{tz('Nothing here', '暂无')}</div>}
            {stocks.map((s) => (
              <FilmCard
                key={s.id}
                stock={s}
                catalog={catalog}
                inUse={s.id === inUse}
                open={open === s.id}
                onToggle={() => setOpen(open === s.id ? null : s.id)}
                onUse={(withPaper) => use(s, withPaper)}
              />
            ))}
          </section>
        );
      })}
    </div>
  );
}

function FilmCard({
  stock: s,
  catalog,
  inUse,
  open,
  onToggle,
  onUse,
}: {
  stock: Stock;
  catalog: StockCatalog;
  inUse: boolean;
  open: boolean;
  onToggle: () => void;
  onUse: (withPaper: boolean) => void;
}) {
  const family = familyOf(s);
  const cover = catalog.coverURL(s.id);
  const formats = (s.formats ?? []).map((f) => f.replace('35mm_motion', '35mm').replace('super8', 'Super 8'));
  const gauge = (s.formats ?? []).includes('135') ? '135' : '120';
  const paper = s.targetPrint ? catalog.stock(s.targetPrint) : null;
  const edge = edgeText(s.id, s.name, gauge);
  return (
    <div
      className={'film-card' + (inUse ? ' in-use' : '')}
      role="button"
      tabIndex={0}
      aria-expanded={open}
      onClick={onToggle}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onToggle();
        }
      }}
      data-stock={s.id}
    >
      <div className="head">
        <span className="spark">
          <Spark />
        </span>
        <span className="name">{s.name}</span>
        {cover && <img className="cover" src={cover} alt="" />}
      </div>
      <div className="blurb">{blurb(s, catalog)}</div>
      <div className={'edge-strip ' + family} aria-label={tz('Edge print', '片边字样')}>
        {edge} &nbsp;▸ 24 &nbsp;&nbsp;24A
      </div>
      <div className="meta">
        <span className="state">
          <span className="dot" />
          {inUse ? tz('In use', '使用中') : tz('Ready', '可用')}
        </span>
        <span>{formats.join(' · ')}</span>
      </div>
      {open && (
        <div className="detail" onClick={(e) => e.stopPropagation()}>
          <div className="model">{tz('Spectral profile', '光谱特性')}</div>
          <ul>
            <li>
              {tz('Type', '类型')}
              <span className="when">{LANES.find((l) => l.family === family)!.title()}</span>
            </li>
            <li>
              {tz('Prints on', '相纸')}
              <span className="when">{family === 'positive' ? tz('Scanned directly', '直接扫描') : (paper?.name ?? '—')}</span>
            </li>
            <li>
              {tz('Formats', '画幅')}
              <span className="when">{formats.join(', ') || '—'}</span>
            </li>
            <li>
              {tz('Film edge', '片边')}
              <span className="when">{edge || '—'}</span>
            </li>
          </ul>
          <div className="actions">
            {paper && family !== 'positive' && (
              <button className="btn" onClick={() => onUse(true)} title={tz('Use this film and the paper it is made to print on.', '使用这款胶片及其配套相纸。')}>
                {tz('With its paper', '连同配套相纸')}
              </button>
            )}
            <button className="btn primary" onClick={() => onUse(false)} data-testid="use-film">
              {inUse ? (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <Check /> {tz('In use', '使用中')}
                </span>
              ) : (
                tz('Use film', '使用胶片')
              )}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
