/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['DM Sans', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'monospace'],
      },
      colors: {
        brand: {
          DEFAULT: '#c8a55a',
          light: '#e2c97d',
          dim: '#a68a3e',
        },
        surface: {
          DEFAULT: '#fafaf8',
          raised: '#ffffff',
        },
        sidebar: {
          DEFAULT: '#141617',
          hover: '#1e2023',
          active: '#262a2d',
        },
      },
      borderColor: {
        DEFAULT: '#e8e5df',
      },
      borderRadius: {
        DEFAULT: '8px',
      },
    },
  },
  plugins: [],
}
