import { describe, expect, it } from 'vitest';
import { GEOMETRY_DEFAULT } from './geometry';
import { canvasGeometry, dateText, edgeText, fitCropToGate, frameWire, gateAspect } from './filmEdge';

describe('the film edge resolvers', () => {
  it('holds the crop at the gate, the same way up, inside the old crop', () => {
    const native = { width: 6000, height: 4000 };
    const g = fitCropToGate(GEOMETRY_DEFAULT, native, '120_6x6');
    expect(g.crop.width * 6000).toBeCloseTo(g.crop.height * 4000, 6);
    expect(g.crop.height).toBe(1);
    const portrait = fitCropToGate({ ...GEOMETRY_DEFAULT, crop: { x: 0.3, y: 0, width: 0.4, height: 1 } }, native, '135');
    const w = portrait.crop.width * 6000, h = portrait.crop.height * 4000;
    expect(h / w).toBeCloseTo(gateAspect('135'), 6);
    expect(portrait.crop.x + portrait.crop.width / 2).toBeCloseTo(0.5, 9);
    // already the gate's shape: left alone
    const again = fitCropToGate(portrait, native, '135');
    expect(again.crop).toEqual(portrait.crop);
  });

  it('prints the film’s own words, else the name in capitals', () => {
    expect(edgeText('kodak_gold_200', 'Kodak Gold 200', '135')).toBe('KODAK GB 200-7');
    expect(edgeText('kodak_gold_200', 'Kodak Gold 200', '120')).toBe('KODAK 200');
    expect(edgeText('kodak_portra_400', 'Kodak Portra 400', '135')).toBe('KODAK PORTRA 400');
    expect(edgeText('kodak_portra_800_push1', 'Kodak Portra 800 (Push 1)', '135')).toBe('KODAK PORTRA 800-3');
    expect(edgeText('x', 'Some Film (Push 2)', '135')).toBe('SOME FILM');
  });

  it('dates the frame from DateTimeOriginal', () => {
    expect(dateText('2026-10-09T17:03:22', 'japan')).toBe("'26 10 9");
    expect(dateText('2026-10-09T17:03:22', 'us')).toBe("10 9 '26");
    expect(dateText(undefined, 'japan')).toBe('');
  });

  it('draws the film whole and sends the crop as the frame', () => {
    const g = { ...GEOMETRY_DEFAULT, crop: { x: 0.1, y: 0, width: 0.8, height: 1 }, quarterTurns: 1 };
    expect(canvasGeometry(g, true)).toBe(GEOMETRY_DEFAULT);
    expect(canvasGeometry(g, false)).toBe(g);
    expect(frameWire(g)).toEqual({ crop: g.crop, angle: 0, quarter_turns: 1, flip_h: false, flip_v: false });
  });
});
