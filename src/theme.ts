import { useColorScheme } from 'react-native';

export type Theme = {
  dark: boolean;
  bg: string;
  card: string;
  cardAlt: string;
  border: string;
  text: string;
  textDim: string;
  textFaint: string;
  accent: string;
  accentSoft: string;
  ok: string;
  okSoft: string;
  warn: string;
  warnSoft: string;
  bad: string;
  badSoft: string;
  mono: string;
};

const dark: Theme = {
  dark: true,
  bg: '#0B0D12',
  card: '#151922',
  cardAlt: '#1C2130',
  border: '#262C3B',
  text: '#ECEFF5',
  textDim: '#A3ABBD',
  textFaint: '#6B7387',
  accent: '#7C9CFF',
  accentSoft: 'rgba(124,156,255,0.14)',
  ok: '#4ADE80',
  okSoft: 'rgba(74,222,128,0.13)',
  warn: '#FBBF24',
  warnSoft: 'rgba(251,191,36,0.14)',
  bad: '#F87171',
  badSoft: 'rgba(248,113,113,0.14)',
  mono: 'monospace',
};

const light: Theme = {
  dark: false,
  bg: '#F4F5F8',
  card: '#FFFFFF',
  cardAlt: '#F0F2F7',
  border: '#E3E6EE',
  text: '#121620',
  textDim: '#525B70',
  textFaint: '#8A92A6',
  accent: '#3D63E0',
  accentSoft: 'rgba(61,99,224,0.10)',
  ok: '#15A34A',
  okSoft: 'rgba(21,163,74,0.10)',
  warn: '#C27C00',
  warnSoft: 'rgba(194,124,0,0.11)',
  bad: '#DC2626',
  badSoft: 'rgba(220,38,38,0.09)',
  mono: 'monospace',
};

export function useTheme(): Theme {
  return useColorScheme() === 'light' ? light : dark;
}

export const radius = { sm: 8, md: 14, lg: 20 };
export const space = (n: number) => n * 4;
