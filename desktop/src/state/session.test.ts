// The store's edit doors, against a stand-in host and platform: what makes an
// undo step and what does not.

import { beforeEach, describe, expect, it, vi } from 'vitest';

const calls: string[] = [];
vi.mock('../host/client', () => {
  class HostCallError extends Error {
    code = 'internal';
  }
  const h = {
    setParams: vi.fn(async () => {
      calls.push('set_params');
      return {};
    }),
    render: vi.fn(async (_sid: string, tier: string) => {
      calls.push(`render:${tier}`);
      return { width: 4, height: 4, data: new Uint8Array(64), colorSpace: 'sRGB', tier, reprinted: false, ms: 1 };
    }),
    redecode: vi.fn(async (_sid: string, decode: object | null, frame?: object | null) => {
      calls.push('redecode:' + JSON.stringify(frame ? { decode, frame } : decode));
      return { session: 's1', width: 6000, height: 4000, kind: 'raw', metadata: { orientation: 1 }, params: {} };
    }),
  };
  return { HostCallError, host: () => h };
});
vi.mock('../platform', () => ({
  platform: () => ({ log: () => {}, sidecarSave: async () => '' }),
  baseName: (p: string) => p.split(/[\\/]/).pop() ?? p,
  dirName: (p: string) => p,
}));

import { recomputeFilmFormat, sessionStore, setDecode, setFilmEdge, setGeometry, setParams, setTool, undo } from './session';
import type { HelloResult } from '@shared/protocol';
import { settingsStore } from './settings';
import { newSidecar } from '@shared/sidecar';

// Undo steps coalesce within 500 ms; each case starts well past the last one.
let clock = 0;
beforeEach(() => {
  clock += 10_000;
  vi.spyOn(performance, 'now').mockReturnValue(clock);
  calls.length = 0;
  sessionStore.setState({
    selection: '/x/a.cr2',
    engineSession: 's1',
    nativeSize: { width: 6000, height: 4000 },
    sidecar: newSidecar(),
    undo: [],
    redo: [],
    batchExporting: false,
  });
  settingsStore.getState().set('recalculateEffectsAfterCrop', true);
});

describe('the derived film format', () => {
  it('is written without an undo step', () => {
    const before = sessionStore.getState().sidecar.params.filmFormatMM;
    sessionStore.setState({ sidecar: { ...newSidecar(), params: { ...newSidecar().params, filmFormatMM: before + 7 } } });
    recomputeFilmFormat();
    const s = sessionStore.getState();
    expect(s.sidecar.params.filmFormatMM).toBeCloseTo(before, 3);
    expect(s.undo).toHaveLength(0);
  });

  it('rides along with the crop that moved it: one undo step, and undo restores both', async () => {
    const mm0 = sessionStore.getState().sidecar.params.filmFormatMM;
    setGeometry((g) => ({ ...g, crop: { x: 0.25, y: 0.25, width: 0.5, height: 0.5 } }));
    const s = sessionStore.getState();
    expect(s.undo).toHaveLength(1);
    expect(s.sidecar.params.filmFormatMM).not.toBeCloseTo(mm0, 3);
    undo();
    const u = sessionStore.getState();
    expect(u.sidecar.geometry.crop.width).toBe(1);
    expect(u.sidecar.params.filmFormatMM).toBeCloseTo(mm0, 3);
    await vi.waitFor(() => expect(calls).toContain('render:live'));
  });

  it('a real edit still makes an undo step', () => {
    setParams((p) => ({ ...p, exposureCompensationEV: 1 }));
    expect(sessionStore.getState().undo).toHaveLength(1);
  });
});

describe('white balance at decode (R2)', () => {
  const hello = { methods: ['open', 'redecode', 'render'] } as unknown as HelloResult;

  it('a decode edit is one undo step and re-decodes the session, then renders', async () => {
    sessionStore.setState({ hello, frameKind: 'raw' });
    setDecode((d) => ({ ...d, whiteBalance: 'Custom', temperature: 3200, tint: 0 }));
    expect(sessionStore.getState().undo).toHaveLength(1);
    await vi.waitFor(() => expect(calls).toContain('redecode:{"white_balance":{"mode":"custom","temperature_k":3200,"tint":0}}'));
    await vi.waitFor(() => expect(calls.indexOf('render:live')).toBeGreaterThan(calls.findIndex((c) => c.startsWith('redecode'))));
    calls.length = 0;
    undo();
    expect(sessionStore.getState().sidecar.decode.whiteBalance).toBe('As Shot');
    await vi.waitFor(() => expect(calls).toContain('redecode:{"white_balance":{"mode":"as_shot"}}'));
  });

  it('a raster frame is not re-decoded', async () => {
    sessionStore.setState({ hello, frameKind: 'tiff', selection: '/x/a.tif' });
    setDecode((d) => ({ ...d, whiteBalance: 'Custom', temperature: 3200 }));
    await vi.waitFor(() => expect(calls).toContain('render:live'));
    expect(calls.some((c) => c.startsWith('redecode'))).toBe(false);
  });
});

describe('the film edge', () => {
  const hello = { methods: ['open', 'redecode', 'render'] } as unknown as HelloResult;

  it('turning it on holds the crop at the gate and cuts the negative by it, in one undo step', async () => {
    sessionStore.setState({ hello, frameKind: 'raw', tool: 'select' });
    setFilmEdge({ active: true, format: '120_6x7' });
    const s = sessionStore.getState();
    expect(s.undo).toHaveLength(1);
    const c = s.sidecar.geometry.crop;
    // 6000 x 4000 held at 69.5 x 56: the full height, centred
    expect((c.width * 6000) / (c.height * 4000)).toBeCloseTo(69.5 / 56, 4);
    expect(c.height).toBe(1);
    expect(c.x + c.width / 2).toBeCloseTo(0.5, 6);
    await vi.waitFor(() => expect(calls.some((x) => x.startsWith('redecode:') && x.includes('"frame"'))).toBe(true));
    await vi.waitFor(() => expect(calls.indexOf('render:live')).toBeGreaterThan(calls.findIndex((x) => x.includes('"frame"'))));
    // the crop tool stays off: the canvas shows the film, not the picture
    setTool('crop');
    expect(sessionStore.getState().tool).toBe('select');
    calls.length = 0;
    undo();
    expect(sessionStore.getState().sidecar.params.filmEdge.active).toBe(false);
    expect(sessionStore.getState().sidecar.geometry.crop.width).toBe(1);
    // off again: the whole decode back
    await vi.waitFor(() => expect(calls.some((x) => x.startsWith('redecode:') && !x.includes('"frame"'))).toBe(true));
  });
});

describe('the busy indicator', () => {
  it('is up from an edit until its render lands, and down after a failed one', async () => {
    setParams((p) => ({ ...p, exposureCompensationEV: 0.5 }));
    expect(sessionStore.getState().rendering).toBe(true);
    await vi.waitFor(() => expect(calls).toContain('render:live'));
    await vi.waitFor(() => expect(sessionStore.getState().rendering).toBe(false));

    const { host } = await import('../host/client');
    vi.mocked(host().render).mockRejectedValueOnce(new Error('gone'));
    setParams((p) => ({ ...p, exposureCompensationEV: 1.5 }));
    expect(sessionStore.getState().rendering).toBe(true);
    await vi.waitFor(() => expect(sessionStore.getState().rendering).toBe(false));
  });
});
