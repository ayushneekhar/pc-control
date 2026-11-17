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
  onAccent: string;
  ok: string;
  okSoft: string;
  warn: string;
  warnSoft: string;
  bad: string;
  badSoft: string;
  mono: string;
  /** Dimmed backdrop behind sheets and dialogs. */
  overlay: string;
  /** Feed/camera surfaces and their overlays (always dark, both themes). */
  media: string;
  onMedia: string;
  onMediaDim: string;
  mediaChip: string;
  mediaChipBorder: string;
  /** Neutral press highlight. */
  ripple: string;
};

const dark: Theme = {
  dark: true,
  bg: '#000000',
  card: '#141414',
  cardAlt: '#1F1F1F',
  border: '#262626',
  text: '#F5F5F5',
  textDim: '#A8A8A8',
  textFaint: '#707070',
  accent: '#3DDC97',
  accentSoft: 'rgba(61,220,151,0.16)',
  onAccent: '#000000',
  ok: '#3DDC97',
  okSoft: 'rgba(61,220,151,0.16)',
  warn: '#F2B544',
  warnSoft: 'rgba(242,181,68,0.15)',
  bad: '#F2685A',
  badSoft: 'rgba(242,104,90,0.15)',
  mono: 'monospace',
  overlay: 'rgba(0,0,0,0.55)',
  media: '#000000',
  onMedia: '#FFFFFF',
  onMediaDim: 'rgba(255,255,255,0.72)',
  mediaChip: 'rgba(0,0,0,0.55)',
  mediaChipBorder: 'rgba(255,255,255,0.18)',
  ripple: 'rgba(255,255,255,0.08)',
};

const light: Theme = {
  dark: false,
  bg: '#F4F4F4',
  card: '#FFFFFF',
  cardAlt: '#EBEBEB',
  border: '#E2E2E2',
  text: '#111111',
  textDim: '#555555',
  textFaint: '#8A8A8A',
  accent: '#159A63',
  accentSoft: 'rgba(21,154,99,0.12)',
  onAccent: '#000000',
  ok: '#159A63',
  okSoft: 'rgba(21,154,99,0.12)',
  warn: '#B87800',
  warnSoft: 'rgba(184,120,0,0.14)',
  bad: '#D2392B',
  badSoft: 'rgba(210,57,43,0.12)',
  mono: 'monospace',
  overlay: 'rgba(0,0,0,0.45)',
  media: '#000000',
  onMedia: '#FFFFFF',
  onMediaDim: 'rgba(255,255,255,0.72)',
  mediaChip: 'rgba(0,0,0,0.55)',
  mediaChipBorder: 'rgba(255,255,255,0.18)',
  ripple: 'rgba(0,0,0,0.08)',
};

export function useTheme(): Theme {
  return useColorScheme() === 'light' ? light : dark;
}

export const radius = { sm: 12, md: 16, lg: 16, pill: 999 };
export const space = (n: number) => n * 4;
export const screenPad = 18;
