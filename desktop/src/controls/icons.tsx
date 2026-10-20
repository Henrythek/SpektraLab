// icons.tsx — the bar's and the rails' glyphs as inline SVG (the Mac uses SF
// Symbols, which do not exist here; these are drawn to the same shapes).

import type { SVGProps } from 'react';

type P = SVGProps<SVGSVGElement> & { size?: number };
const base = (size = 16, p: P) => ({
  width: size,
  height: size,
  viewBox: '0 0 16 16',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  ...p,
});

export const SidebarLeft = ({ size, ...p }: P) => (
  <svg {...base(size ?? 15, p)}>
    <rect x="1.5" y="2.5" width="13" height="11" rx="2" />
    <line x1="6" y1="2.5" x2="6" y2="13.5" />
  </svg>
);
export const SidebarRight = ({ size, ...p }: P) => (
  <svg {...base(size ?? 15, p)}>
    <rect x="1.5" y="2.5" width="13" height="11" rx="2" />
    <line x1="10" y1="2.5" x2="10" y2="13.5" />
  </svg>
);
export const Import = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <path d="M5 6.5h-2.5v7h11v-7H11" />
    <path d="M8 1.5v8M5.5 7l2.5 2.5L10.5 7" />
  </svg>
);
export const Export = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <path d="M5 6.5h-2.5v7h11v-7H11" />
    <path d="M8 10V1.5M5.5 4L8 1.5 10.5 4" />
  </svg>
);
export const Cursor = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <path d="M4 2l8.5 6.5-4 .6 2.3 4.4-1.6.8-2.3-4.5L4 12.5z" fill="currentColor" stroke="none" />
  </svg>
);
export const Hand = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <path d="M5 8V3.5a1 1 0 012 0V7m0-4.5a1 1 0 012 0V7m0-3.5a1 1 0 012 0V8m0-2.5a1 1 0 012 0V10c0 2.5-2 4.5-4.5 4.5S5.5 13 4 11L2.6 8.8a1 1 0 011.6-1.2L5 8.7" />
  </svg>
);
export const Crop = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <path d="M4 1.5V12h10.5M1.5 4H12v10.5" />
  </svg>
);
export const ZoomOut = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <circle cx="6.8" cy="6.8" r="4.8" />
    <path d="M10.3 10.3l4 4M4.6 6.8h4.4" />
  </svg>
);
export const ZoomIn = ({ size, ...p }: P) => (
  <svg {...base(size, p)}>
    <circle cx="6.8" cy="6.8" r="4.8" />
    <path d="M10.3 10.3l4 4M4.6 6.8h4.4M6.8 4.6v4.4" />
  </svg>
);
export const BeforeAfter = ({ size, ...p }: P) => (
  <svg width={size ?? 25} height={16} viewBox="0 0 25 16" fill="none" stroke="currentColor" strokeWidth={1.2} {...p}>
    <rect x="1" y="1.5" width="23" height="13" rx="1.5" />
    <rect x="12.5" y="1.5" width="11.5" height="13" fill="currentColor" />
  </svg>
);
export const ChevronLeft = ({ size, ...p }: P) => (
  <svg {...base(size ?? 12, p)}>
    <path d="M10 2.5L4.5 8l5.5 5.5" />
  </svg>
);
export const ChevronRight = ({ size, ...p }: P) => (
  <svg {...base(size ?? 12, p)}>
    <path d="M6 2.5L11.5 8 6 13.5" />
  </svg>
);
export const Disclosure = ({ size, ...p }: P) => (
  <svg width={size ?? 12} height={7} viewBox="0 0 12 7" fill="none" stroke="currentColor" strokeWidth={1.1} {...p}>
    <path d="M1 1h10L6 6z" />
  </svg>
);
export const Reset = ({ size, ...p }: P) => (
  <svg {...base(size ?? 10, p)}>
    <path d="M3 8a5 5 0 105-5H5.5M5.5 1l-2 2 2 2" />
  </svg>
);
export const Ellipsis = ({ size, ...p }: P) => (
  <svg width={size ?? 14} height={4} viewBox="0 0 14 4" fill="currentColor" {...p}>
    <circle cx="2" cy="2" r="1.4" />
    <circle cx="7" cy="2" r="1.4" />
    <circle cx="12" cy="2" r="1.4" />
  </svg>
);
export const RotateLeft = ({ size, ...p }: P) => (
  <svg {...base(size ?? 13, p)}>
    <rect x="5" y="6" width="9" height="8" rx="1" />
    <path d="M2 8V5a3 3 0 013-3h3M6 0l2 2-2 2" />
  </svg>
);
export const RotateRight = ({ size, ...p }: P) => (
  <svg {...base(size ?? 13, p)}>
    <rect x="2" y="6" width="9" height="8" rx="1" />
    <path d="M14 8V5a3 3 0 00-3-3H8M10 0L8 2l2 2" />
  </svg>
);
export const FlipH = ({ size, ...p }: P) => (
  <svg {...base(size ?? 13, p)}>
    <path d="M8 1v14M6 3L1.5 13H6zM10 3l4.5 10H10z" />
  </svg>
);
export const FlipV = ({ size, ...p }: P) => (
  <svg {...base(size ?? 13, p)}>
    <path d="M1 8h14M3 6L13 1.5V6zM3 10l10 4.5V10z" />
  </svg>
);
// The board language's marks: the sparkle on a lane or card header, the
// Board/Editor switch's two glyphs, the theme switch.
export const Spark = ({ size, ...p }: P) => (
  <svg width={size ?? 11} height={size ?? 11} viewBox="0 0 12 12" fill="currentColor" {...p}>
    <path d="M6 0.5c.35 2.6 1.1 3.9 2.2 4.6.8.5 1.9.8 3.3.9-1.4.1-2.5.4-3.3.9C7.1 7.6 6.35 8.9 6 11.5c-.35-2.6-1.1-3.9-2.2-4.6C3 6.4 1.9 6.1.5 6c1.4-.1 2.5-.4 3.3-.9C4.9 4.4 5.65 3.1 6 .5z" />
  </svg>
);
export const BoardIcon = ({ size, ...p }: P) => (
  <svg {...base(size ?? 14, p)}>
    <rect x="1.5" y="2" width="3.6" height="12" rx="1" />
    <rect x="6.2" y="2" width="3.6" height="8" rx="1" />
    <rect x="10.9" y="2" width="3.6" height="10" rx="1" />
  </svg>
);
export const EditorIcon = ({ size, ...p }: P) => (
  <svg {...base(size ?? 14, p)}>
    <rect x="1.5" y="2.5" width="13" height="11" rx="1.8" />
    <path d="M3.5 11l3-3.5 2 2 1.5-1.5 2.5 3" />
  </svg>
);
export const Sun = ({ size, ...p }: P) => (
  <svg {...base(size ?? 15, p)}>
    <circle cx="8" cy="8" r="3" />
    <path d="M8 1v1.6M8 13.4V15M1 8h1.6M13.4 8H15M3 3l1.1 1.1M11.9 11.9L13 13M3 13l1.1-1.1M11.9 4.1L13 3" />
  </svg>
);
export const Moon = ({ size, ...p }: P) => (
  <svg {...base(size ?? 15, p)}>
    <path d="M13.5 9.6A6 6 0 016.4 2.5a6 6 0 107.1 7.1z" />
  </svg>
);
export const Check = ({ size, ...p }: P) => (
  <svg {...base(size ?? 12, p)}>
    <path d="M3 8.5l3 3 7-7" />
  </svg>
);
