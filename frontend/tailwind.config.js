/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'media',
  theme: {
    extend: {
      colors: {
        brand: { DEFAULT: '#12A388', dark: '#0E7F6A', soft: '#E4F6F2' },
        navy: { DEFAULT: '#0B1A33', soft: '#16294A' },
        amber: { brand: '#E8B64B' },
      },
      fontFamily: { sans: ['Inter', 'Noto Sans Tamil', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'] },
    },
  },
  plugins: [],
};
