import { ReactNode, useEffect, useMemo, useState } from 'react';
import { AppState, Dimensions, Platform, StatusBar } from 'react-native';
import { EdgeInsets, SafeAreaInsetsContext, useSafeAreaInsets } from 'react-native-safe-area-context';
import { PcNative } from '../../modules/pc-native';

const ZERO: EdgeInsets = { top: 0, bottom: 0, left: 0, right: 0 };

function readNative(): EdgeInsets {
  try {
    const n = PcNative?.windowInsets?.();
    if (n) return n;
  } catch {}
  // Fallback (old binary / activity not attached yet): at least clear the status bar.
  return Platform.OS === 'android' ? { ...ZERO, top: StatusBar.currentHeight ?? 0 } : ZERO;
}

const same = (a: EdgeInsets, b: EdgeInsets) =>
  a.top === b.top && a.bottom === b.bottom && a.left === b.left && a.right === b.right;

/**
 * Re-provides safe-area insets as max(safe-area-context, the window's own system-bar insets) per edge.
 * safe-area-context derives insets from where its provider view sits on screen, and on this app's
 * edge-to-edge Android build it reported 0 at the top (the clock drew over screen titles). The window's
 * insets are absolute, so taking the max can never under-pad. Everything below, including React
 * Navigation's tab bar, reads this context.
 */
export function SystemInsetsProvider({ children }: { children: ReactNode }) {
  const lib = useSafeAreaInsets();
  const [native, setNative] = useState<EdgeInsets>(readNative);

  useEffect(() => {
    const refresh = () => {
      const n = readNative();
      setNative((old) => (same(old, n) ? old : n));
    };
    // the decor view may not have insets on the very first frame; nav mode can change while backgrounded
    const timers = [0, 300, 1200].map((ms) => setTimeout(refresh, ms));
    const app = AppState.addEventListener('change', (s) => s === 'active' && refresh());
    const dims = Dimensions.addEventListener('change', refresh);
    return () => {
      timers.forEach(clearTimeout);
      app.remove();
      dims.remove();
    };
  }, []);

  const merged = useMemo<EdgeInsets>(
    () => ({
      top: Math.max(lib.top, native.top),
      bottom: Math.max(lib.bottom, native.bottom),
      left: Math.max(lib.left, native.left),
      right: Math.max(lib.right, native.right),
    }),
    [lib.top, lib.bottom, lib.left, lib.right, native],
  );

  return <SafeAreaInsetsContext.Provider value={merged}>{children}</SafeAreaInsetsContext.Provider>;
}
