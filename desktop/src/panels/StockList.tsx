// StockList.tsx — the film and paper lists: small uppercase group headers
// with a count, rows as compact cards, a cover where the catalogue has one and
// a family swatch where it has not, and the orange CINE pill.

export interface StockRow {
  id: string;
  name: string;
  header?: boolean;
  /** A header's count of rows. */
  count?: number;
  cine?: boolean;
  cover?: string | null;
  /** negative | positive | mono | cine | paper: the swatch drawn without a cover. */
  swatch?: string;
  disabled?: boolean;
  reason?: string | null;
}

export function StockList({ rows, selected, onSelect, testId }: { rows: StockRow[]; selected: string; onSelect: (id: string) => void; testId?: string }) {
  return (
    <div role="listbox" data-testid={testId} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {rows.map((r) =>
        r.header ? (
          <div key={r.id} className="stock-group">
            <span style={{ flex: 1 }}>{r.name}</span>
            {r.count !== undefined && <span className="count">{r.count}</span>}
          </div>
        ) : (
          <div
            key={r.id}
            role="option"
            aria-selected={r.id === selected}
            aria-disabled={r.disabled}
            className={'stock-row' + (r.id === selected ? ' selected' : '') + (r.disabled ? ' disabled' : '')}
            title={r.disabled ? (r.reason ?? undefined) : r.name}
            onClick={() => !r.disabled && onSelect(r.id)}
            data-stock={r.id}
          >
            {r.cover ? <img className="cover" src={r.cover} alt="" /> : r.swatch ? <span className={'swatch ' + r.swatch} /> : null}
            <span className="name">{r.name}</span>
            {r.cine && <span className="cine-pill">CINE</span>}
          </div>
        ),
      )}
    </div>
  );
}
