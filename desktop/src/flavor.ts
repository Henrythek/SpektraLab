// flavor.ts — which interface skin this build carries.
//
//   classic  the v3 drawing (`styles/theme.css`), the shipped SpektraLab.
//   claude   the Claude Design System skin (`styles/theme-claude.css`):
//            warm surface ladder, clay accent, pill tracks, spring easing.
//
// Chosen at build time with VITE_UI_FLAVOR (set by `SPEKTRALAB_FLAVOR=claude
// node scripts/package.mjs …`). The dev server also takes `?ui=claude` so the
// two can be compared side by side. A classic build never matches any rule in
// theme-claude.css: every selector there is scoped to `:root[data-ui='claude']`.

export type UiFlavor = 'classic' | 'claude';

function resolve(): UiFlavor {
  if (import.meta.env.DEV && typeof location !== 'undefined') {
    const q = new URLSearchParams(location.search).get('ui');
    if (q === 'claude' || q === 'classic') return q;
  }
  return import.meta.env.VITE_UI_FLAVOR === 'claude' ? 'claude' : 'classic';
}

export const UI_FLAVOR: UiFlavor = resolve();

/** The canvas surround (letterbox grey), 0..1. Classic: the drawing's #5f5f5f;
 *  Claude: CDS surface-0 (#0b0b0b), the base canvas under the surface-1 rails. */
export const CANVAS_SURROUND = UI_FLAVOR === 'claude' ? 0x0b / 255 : 0x5f / 255;

/** The window title's product name. */
export const APP_NAME = UI_FLAVOR === 'claude' ? 'SpektraLab Claude UI' : 'SpektraLab';
