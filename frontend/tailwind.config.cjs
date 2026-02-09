/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        buaa: {
          light: '#3b82f6',
          DEFAULT: '#0052D9',
          dark: '#003eb3',
        },
        primary: {
          50: '#f5f7ff',
          100: '#ebf0ff',
          200: '#d6e0ff',
          300: '#b8c9ff',
          400: '#8fa8ff',
          500: '#667eea',
          600: '#5568d3',
          700: '#4451b8',
          800: '#343b94',
          900: '#2a2f6f',
        },
        secondary: {
          500: '#f093fb',
          600: '#d977e5',
        },
      },
      animation: {
        'float': 'float 20s ease-in-out infinite',
        'pulse-ring': 'pulse-ring 1.5s ease-out infinite',
        'slide-in': 'slideIn 0.3s ease',
      },
      keyframes: {
        float: {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '25%': { transform: 'translate(50px, -50px) scale(1.1)' },
          '50%': { transform: 'translate(-30px, 50px) scale(0.9)' },
          '75%': { transform: 'translate(30px, 30px) scale(1.05)' },
        },
        'pulse-ring': {
          '0%': { transform: 'scale(0.8)', opacity: '1' },
          '100%': { transform: 'scale(1.4)', opacity: '0' },
        },
        slideIn: {
          'from': { opacity: '0', transform: 'translateY(20px)' },
          'to': { opacity: '1', transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [],
}

