/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      fontFamily: {
        // IBM Plex Sans is the default body — readable for prose.
        sans: ['IBM Plex Sans', 'system-ui', '-apple-system', 'sans-serif'],
        // Mono reserved for data: numerics, paths, timestamps, IDs, code.
        mono: ['JetBrains Mono', 'ui-monospace', 'monospace'],
        // Serif reserved for display: page titles, brand mark.
        serif: ['IBM Plex Serif', 'Georgia', 'serif'],
      },
      colors: {
        // ── Canvas (cool gray, real cards on gray) ─────────
        ink: {
          DEFAULT: '#09090b',     /* darker — max contrast body */
          2: '#18181b',
          3: '#27272a',
        },
        paper: {
          DEFAULT: '#e5e7eb',     /* page canvas — visible gray */
          2: '#eef0f3',           /* slightly lighter zone */
          3: '#d4d4d8',           /* rules, dividers */
          rule: '#d4d4d8',
          card: '#ffffff',        /* white card surface */
        },
        graphite: {
          DEFAULT: '#3f3f46',     /* secondary text — was 52525b, darker now */
          2: '#52525b',           /* muted text — was 71717a, darker now */
          3: '#71717a',           /* very muted — captions only */
        },
        gold: {
          DEFAULT: '#c8a55a',
          dim: '#a68a3e',
          light: '#e2c97d',
          soft: '#fef9ec',
          softer: '#fbf3dc',
        },
        ok:    { DEFAULT: '#16a34a', soft: '#dcfce7', softer: '#bbf7d0' },
        warn:  { DEFAULT: '#d97706', soft: '#fef3c7', softer: '#fde68a' },
        err:   { DEFAULT: '#dc2626', soft: '#fee2e2', softer: '#fecaca' },
        info:  { DEFAULT: '#2563eb', soft: '#dbeafe', softer: '#bfdbfe' },
        data:  { DEFAULT: '#0891b2', soft: '#cffafe', softer: '#a5f3fc' },
        magic: { DEFAULT: '#9333ea', soft: '#f3e8ff', softer: '#e9d5ff' },
        sidebar: {
          DEFAULT: '#18181b',
          hover: '#27272a',
          active: '#3f3f46',
          text: '#a1a1aa',
          'text-active': '#fafafa',
        },
        brand: { DEFAULT: '#c8a55a', light: '#e2c97d', dim: '#a68a3e' },
        surface: { DEFAULT: '#e5e7eb', raised: '#ffffff' },
      },
      fontSize: {
        display: ['2rem',     { lineHeight: '2.5rem',  letterSpacing: '-0.015em' }], // 32/40
        heading: ['1rem',     { lineHeight: '1.5rem',  letterSpacing: '0' }],         // 16/24
        body:    ['0.9375rem',{ lineHeight: '1.5rem' }],                              // 15/24
        label:   ['0.75rem',  { lineHeight: '1rem',    letterSpacing: '0.08em'  }],  // 12/16
        caption: ['0.8125rem',{ lineHeight: '1.125rem' }],                            // 13/18
      },
      borderColor: { DEFAULT: '#d4d4d8', light: '#e4e4e7' },
      borderRadius: { DEFAULT: '6px', none: '0', sm: '4px', md: '6px', lg: '10px', xl: '14px' },
      boxShadow: {
        card:    '0 1px 2px rgba(9, 9, 11, 0.04), 0 1px 3px rgba(9, 9, 11, 0.06)',
        'card-hover': '0 2px 4px rgba(9, 9, 11, 0.06), 0 4px 8px rgba(9, 9, 11, 0.08)',
        focus:   '0 0 0 2px #dbeafe, 0 0 0 3px #2563eb',
      },
      transitionDuration: { DEFAULT: '120ms' },
    },
  },
  plugins: [],
}
