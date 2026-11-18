import { forwardRef, ReactNode, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { Animated, PanResponder, StyleProp, ViewStyle } from 'react-native';

const MIN = 1;
const MAX = 5;
const DOUBLE_ZOOM = 2.5;
const GESTURE_SLOP = 4;

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

type Point = { x: number; y: number };
type Touch = { pageX: number; pageY: number; locationX: number; locationY: number };
type Mode = 'none' | 'pinch' | 'pan';

const distance = (touches: Touch[]) => Math.hypot(touches[0].pageX - touches[1].pageX, touches[0].pageY - touches[1].pageY);
const focal = (touches: Touch[]): Point => ({
  x: (touches[0].locationX + touches[1].locationX) / 2,
  y: (touches[0].locationY + touches[1].locationY) / 2,
});

export type PinchZoomHandle = {
  /** Toggle between 1x and 2.5x, keeping the tapped point under the finger. */
  toggleAt(x: number, y: number): void;
  reset(): void;
};

type Props = {
  children: ReactNode;
  enabled?: boolean;
  resetKey?: unknown;
  style?: StyleProp<ViewStyle>;
  /** A tap on the video while zoomed (the child pressable is bypassed then). */
  onTap?: (x: number, y: number, time: number) => void;
};

export const PinchZoom = forwardRef<PinchZoomHandle, Props>(function PinchZoom(
  { children, enabled = true, resetKey, style, onTap },
  ref,
) {
  const scale = useRef(new Animated.Value(1)).current;
  const translateX = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(0)).current;
  const scaleRef = useRef(1);
  const txRef = useRef(0);
  const tyRef = useRef(0);
  const sizeRef = useRef({ w: 0, h: 0 });
  const [size, setSize] = useState({ w: 0, h: 0 });
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;
  const onTapRef = useRef(onTap);
  onTapRef.current = onTap;
  const mode = useRef<Mode>('none');
  const lastDist = useRef(0);
  const lastFocal = useRef<Point>({ x: 0, y: 0 });
  const lastPage = useRef<Point>({ x: 0, y: 0 });
  const tapStart = useRef<{ x: number; y: number; pageX: number; pageY: number; time: number } | null>(null);

  const set = useCallback(
    (s: number, x: number, y: number) => {
      const { w, h } = sizeRef.current;
      const limX = Math.max(0, ((s - 1) * w) / 2);
      const limY = Math.max(0, ((s - 1) * h) / 2);
      const nx = clamp(x, -limX, limX);
      const ny = clamp(y, -limY, limY);
      scaleRef.current = s;
      txRef.current = nx;
      tyRef.current = ny;
      scale.setValue(s);
      translateX.setValue(nx);
      translateY.setValue(ny);
    },
    [scale, translateX, translateY],
  );

  const animate = useCallback(
    (s: number, x: number, y: number) => {
      const { w, h } = sizeRef.current;
      const limX = Math.max(0, ((s - 1) * w) / 2);
      const limY = Math.max(0, ((s - 1) * h) / 2);
      const nx = clamp(x, -limX, limX);
      const ny = clamp(y, -limY, limY);
      scaleRef.current = s;
      txRef.current = nx;
      tyRef.current = ny;
      Animated.parallel([
        Animated.spring(scale, { toValue: s, useNativeDriver: false, friction: 9, tension: 90 }),
        Animated.spring(translateX, { toValue: nx, useNativeDriver: false, friction: 9, tension: 90 }),
        Animated.spring(translateY, { toValue: ny, useNativeDriver: false, friction: 9, tension: 90 }),
      ]).start();
    },
    [scale, translateX, translateY],
  );

  const reset = useCallback(() => {
    mode.current = 'none';
    lastDist.current = 0;
    scale.stopAnimation();
    translateX.stopAnimation();
    translateY.stopAnimation();
    set(1, 0, 0);
  }, [scale, set, translateX, translateY]);

  const toggleAt = useCallback(
    (x: number, y: number) => {
      if (!enabledRef.current) return;
      if (scaleRef.current > 1.05) {
        animate(1, 0, 0);
        return;
      }
      const { w, h } = sizeRef.current;
      const fx = x - w / 2;
      const fy = y - h / 2;
      animate(DOUBLE_ZOOM, fx * (1 - DOUBLE_ZOOM), fy * (1 - DOUBLE_ZOOM));
    },
    [animate],
  );

  useImperativeHandle(ref, () => ({ toggleAt, reset }), [reset, toggleAt]);

  useEffect(() => {
    reset();
  }, [enabled, resetKey, reset]);

  useEffect(() => {
    if (size.w && size.h) reset();
  }, [size.h, size.w, reset]);

  const pan = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: (e) =>
        enabledRef.current && (e.nativeEvent.touches.length >= 2 || scaleRef.current > 1.001),
      onStartShouldSetPanResponderCapture: (e) =>
        enabledRef.current && (e.nativeEvent.touches.length >= 2 || scaleRef.current > 1.001),
      onMoveShouldSetPanResponder: (e, g) => {
        if (!enabledRef.current) return false;
        if (e.nativeEvent.touches.length >= 2) return true;
        return scaleRef.current > 1.001 && (Math.abs(g.dx) > GESTURE_SLOP || Math.abs(g.dy) > GESTURE_SLOP);
      },
      onMoveShouldSetPanResponderCapture: (e, g) => {
        if (!enabledRef.current) return false;
        if (e.nativeEvent.touches.length >= 2) return true;
        return scaleRef.current > 1.001 && (Math.abs(g.dx) > GESTURE_SLOP || Math.abs(g.dy) > GESTURE_SLOP);
      },
      onPanResponderTerminationRequest: () => false,
      onShouldBlockNativeResponder: () => true,
      onPanResponderGrant: (e) => {
        scale.stopAnimation();
        translateX.stopAnimation();
        translateY.stopAnimation();
        const touches = e.nativeEvent.touches as unknown as Touch[];
        if (touches.length >= 2) {
          mode.current = 'pinch';
          tapStart.current = null;
          lastDist.current = distance(touches);
          lastFocal.current = focal(touches);
        } else if (touches[0]) {
          mode.current = 'pan';
          lastPage.current = { x: touches[0].pageX, y: touches[0].pageY };
          tapStart.current = {
            x: touches[0].locationX,
            y: touches[0].locationY,
            pageX: touches[0].pageX,
            pageY: touches[0].pageY,
            time: e.nativeEvent.timestamp,
          };
        }
      },
      onPanResponderMove: (e) => {
        const touches = e.nativeEvent.touches as unknown as Touch[];
        if (tapStart.current) {
          if (touches.length !== 1) tapStart.current = null;
          else if (
            Math.abs(touches[0].pageX - tapStart.current.pageX) > GESTURE_SLOP ||
            Math.abs(touches[0].pageY - tapStart.current.pageY) > GESTURE_SLOP
          )
            tapStart.current = null;
        }
        if (touches.length >= 2) {
          const d = distance(touches);
          const f = focal(touches);
          if (mode.current !== 'pinch' || !lastDist.current) {
            mode.current = 'pinch';
            lastDist.current = d;
            lastFocal.current = f;
            return;
          }
          const s0 = scaleRef.current;
          const ns = clamp((s0 * d) / lastDist.current, MIN, MAX);
          const k = ns / s0;
          const nx = f.x - (lastFocal.current.x - txRef.current) * k;
          const ny = f.y - (lastFocal.current.y - tyRef.current) * k;
          set(ns, nx, ny);
          lastDist.current = d;
          lastFocal.current = f;
          return;
        }
        if (touches[0]) {
          if (mode.current !== 'pan') {
            mode.current = 'pan';
            lastPage.current = { x: touches[0].pageX, y: touches[0].pageY };
            return;
          }
          const dx = touches[0].pageX - lastPage.current.x;
          const dy = touches[0].pageY - lastPage.current.y;
          set(scaleRef.current, txRef.current + dx, tyRef.current + dy);
          lastPage.current = { x: touches[0].pageX, y: touches[0].pageY };
        }
      },
      onPanResponderRelease: (e) => {
        const tap = tapStart.current;
        tapStart.current = null;
        if (tap && e.nativeEvent.timestamp - tap.time < 500) onTapRef.current?.(tap.x, tap.y, e.nativeEvent.timestamp);
        if (scaleRef.current < 1.01) animate(1, 0, 0);
        else animate(scaleRef.current, txRef.current, tyRef.current);
        mode.current = 'none';
        lastDist.current = 0;
      },
      onPanResponderTerminate: () => {
        tapStart.current = null;
        if (scaleRef.current < 1.01) animate(1, 0, 0);
        else animate(scaleRef.current, txRef.current, tyRef.current);
        mode.current = 'none';
        lastDist.current = 0;
      },
    }),
  ).current;

  return (
    <Animated.View
      {...pan.panHandlers}
      style={[
        { overflow: 'hidden' },
        style,
        { transform: [{ translateX }, { translateY }, { scale }] },
      ]}
      onLayout={(e) => {
        const { width, height } = e.nativeEvent.layout;
        sizeRef.current = { w: width, h: height };
        setSize((old) => (old.w === width && old.h === height ? old : { w: width, h: height }));
      }}
    >
      {children}
    </Animated.View>
  );
});
