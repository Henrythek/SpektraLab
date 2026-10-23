// filmEdge.ts — what the app owes the engine for the film edge (RFC-032) and
// the date back (RFC-031): a port of `Session+FilmEdge.swift`'s resolvers.
//
// The engine takes the frame it is handed as the gate, and refuses one whose
// shape is not the gate's. So while the film edge is on the crop is part of
// the negative: the host cuts the decode by it (`open`/`redecode` `frame`),
// the print is the whole film canvas, and the canvas draws it with no
// geometry of its own (`canvasGeometry`). The crop is held at the gate's
// aspect (`fitCropToGate`).

import type { FileMetadata } from './protocol';
import { type FilmParams } from './params';
import { type Geometry, type Size, GEOMETRY_DEFAULT } from './geometry';

/** Each format's gate, mm: [along the film, across it] (`overscan.cpp` kFormats). */
export const GATE_MM: Record<string, readonly [number, number]> = {
  '135': [36.25, 24.3],
  '135_half': [18, 24],
  '135_xpan': [65, 24],
  '120_645': [41.5, 56],
  '120_6x6': [56, 56],
  '120_6x7': [69.5, 56],
  '120_6x8': [76, 56],
  '120_6x9': [84, 56],
  '120_6x12': [112, 56],
  '120_6x17': [168, 56],
};

export const gaugeOf = (format: string) => (format.startsWith('120') ? '120' : '135');

/** The gate's long ÷ short side. */
export function gateAspect(format: string): number {
  const g = GATE_MM[format] ?? GATE_MM['135']!;
  return Math.max(g[0], g[1]) / Math.min(g[0], g[1]);
}

/** The film edge shows: the host can draw it and it is on. */
export const filmShows = (p: FilmParams, overscanSupported: boolean) => overscanSupported && p.filmEdge.active;

/** What the canvas, the export and the thumbnails apply: none while the print is the film. */
export const canvasGeometry = (g: Geometry, film: boolean): Geometry => (film ? GEOMETRY_DEFAULT : g);

/** The host's `frame` (HOST-PROTOCOL.md `open`): the decode cut by the crop. */
export function frameWire(g: Geometry) {
  return { crop: g.crop, angle: g.angle, quarter_turns: g.quarterTurns, flip_h: g.flipH, flip_v: g.flipV };
}

/**
 * The crop held at the gate's shape: the largest crop of the gate's aspect
 * inside the current one, about its centre, the same way up. A crop already
 * within 0.5 % of the gate is left alone (the engine allows 5 %).
 */
export function fitCropToGate(g: Geometry, native: Size, format: string): Geometry {
  const W = Math.max(native.width, 1);
  const H = Math.max(native.height, 1);
  const cw = g.crop.width * W;
  const ch = g.crop.height * H;
  const k = gateAspect(format);
  const landscape = cw >= ch;
  const want = landscape ? k : 1 / k;
  if (Math.abs(cw / ch / want - 1) < 0.005) return { ...g, lockedRatio: want };
  let w = cw;
  let h = ch;
  if (cw / ch > want) w = ch * want;
  else h = cw / want;
  const cx = g.crop.x + g.crop.width / 2;
  const cy = g.crop.y + g.crop.height / 2;
  const crop = { x: cx - w / W / 2, y: cy - h / H / 2, width: w / W, height: h / H };
  return { ...g, crop, aspect: 'free', lockedRatio: want, intendedSize: null };
}

/**
 * The stock's edge print as the film carries it (`Session.edgeText`): the
 * film's own words where they are not its marketing name, read off real
 * strips; otherwise the name, without a push note, in capitals.
 */
const OWN_WORDS: Record<string, readonly [string, string]> = {
  kodak_gold_200: ['KODAK GB 200-7', 'KODAK 200'],
  kodak_portra_800: ['KODAK PORTRA 800-3', 'KODAK PORTRA 800'],
  kodak_portra_800_push1: ['KODAK PORTRA 800-3', 'KODAK PORTRA 800'],
  kodak_portra_800_push2: ['KODAK PORTRA 800-3', 'KODAK PORTRA 800'],
  kodak_ultramax_400: ['KODAK GC 400', 'KODAK GC 400'],
  kodak_ektachrome_100: ['KODAK E100', 'KODAK E100'],
  fujifilm_c200: ['FUJI 200', 'FUJI 200'],
  fujifilm_xtra_400: ['S-400', 'FUJI S-400'],
  fujifilm_pro_400h: ['PRO400H', 'FUJI PRO400H'],
  fujifilm_provia_100f: ['FUJI RDPIII', 'FUJI RDPIII'],
  fujifilm_velvia_100: ['RVP100', 'FUJI RVP100'],
  kodak_vision3_50d: ['EASTMAN 5203', 'EASTMAN 5203'],
  kodak_vision3_250d: ['EASTMAN 5207', 'EASTMAN 5207'],
  kodak_vision3_200t: ['EASTMAN 5213', 'EASTMAN 5213'],
  kodak_vision3_500t: ['EASTMAN 5219', 'EASTMAN 5219'],
  kodak_tri_x_400: ['KODAK 400TX', 'KODAK 400TX'],
  kodak_tmax_100: ['KODAK 100TMX', 'KODAK 100TMX'],
  fujifilm_neopan_acros_100_ii: ['FUJI 100 ACROS II', 'FUJI 100 ACROS II'],
  ilford_hp5_plus_400: ['ILFORD HP5 PLUS', 'ILFORD HP5 PLUS'],
};

export function edgeText(stockId: string, stockName: string | undefined, gauge: string): string {
  const own = OWN_WORDS[stockId];
  if (own) return gauge === '120' ? own[1] : own[0];
  if (!stockName) return '';
  const open = stockName.indexOf('(');
  return (open >= 0 ? stockName.slice(0, open) : stockName).trim().toUpperCase();
}

/** `'26 10 19` (japan) or `10 19 '26` (us), from EXIF's DateTimeOriginal; "" without one. */
export function dateText(datetime: string | undefined, order: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(datetime ?? '');
  if (!m) return '';
  const yy = `'${m[1]!.slice(2)}`;
  const month = String(Number(m[2]));
  const day = String(Number(m[3]));
  return order === 'us' ? `${month} ${day} ${yy}` : `${yy} ${month} ${day}`;
}

/**
 * Everything filled in on the frame's behalf before the params go to the
 * engine, as a pure function (`Session.resolvedFilmEdge`): the edge print,
 * the gate's f-number and the date. Resolving twice is resolving once.
 */
export function resolveFilmEdge(p: FilmParams, stockName: string | undefined, metadata: FileMetadata | null): FilmParams {
  const fe = { ...p.filmEdge, edgeText: edgeText(p.filmStock, stockName, gaugeOf(p.filmEdge.format)), fNumber: metadata?.aperture ?? 0 };
  const db = { ...p.dateBack, text: dateText(metadata?.datetime_original, p.dateBack.order) };
  return { ...p, filmEdge: fe, dateBack: db };
}
