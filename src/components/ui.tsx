import { MaterialCommunityIcons } from '@expo/vector-icons';
import React, { ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleProp,
  StyleSheet,
  Text,
  TextStyle,
  View,
  ViewStyle,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, space, Theme, useTheme } from '../theme';

export type IconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

export function Icon({ name, size = 20, color }: { name: string; size?: number; color?: string }) {
  const t = useTheme();
  const n = (name in MaterialCommunityIcons.glyphMap ? name : 'web') as IconName;
  return <MaterialCommunityIcons name={n} size={size} color={color ?? t.text} />;
}

export function Screen({
  title,
  subtitle,
  right,
  onBack,
  children,
  onRefresh,
  refreshing = false,
  scroll = true,
}: {
  title: string;
  subtitle?: ReactNode;
  right?: ReactNode;
  onBack?: () => void;
  children: ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  scroll?: boolean;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const header = (
    <View style={[styles.header, { paddingTop: insets.top + space(4) }]}>
      {onBack ? (
        <Pressable onPress={onBack} hitSlop={8} accessibilityLabel="Back" style={styles.back}>
          <Icon name="arrow-left" size={24} color={t.text} />
        </Pressable>
      ) : null}
      <View style={{ flex: 1 }}>
        <Text style={[styles.title, { color: t.text }]}>{title}</Text>
        {subtitle ? (
          typeof subtitle === 'string' ? (
            <Text style={[styles.subtitle, { color: t.textDim }]}>{subtitle}</Text>
          ) : (
            subtitle
          )
        ) : null}
      </View>
      {right}
    </View>
  );
  if (!scroll)
    return (
      <View style={{ flex: 1, backgroundColor: t.bg }}>
        {header}
        {children}
      </View>
    );
  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: t.bg }}
      contentContainerStyle={{ paddingBottom: space(8) }}
      refreshControl={
        onRefresh ? (
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            tintColor={t.accent}
            colors={[t.accent]}
            progressBackgroundColor={t.card}
            progressViewOffset={insets.top}
          />
        ) : undefined
      }
    >
      {header}
      <View style={{ paddingHorizontal: space(4), gap: space(3) }}>{children}</View>
    </ScrollView>
  );
}

export function Card({
  children,
  style,
  onPress,
  onLongPress,
}: {
  children: ReactNode;
  style?: StyleProp<ViewStyle>;
  onPress?: () => void;
  onLongPress?: () => void;
}) {
  const t = useTheme();
  const base = [styles.card, { backgroundColor: t.card, borderColor: t.border }, style];
  if (!onPress && !onLongPress) return <View style={base}>{children}</View>;
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      android_ripple={{ color: t.accentSoft, foreground: true }}
      style={({ pressed }) => [...base, { overflow: 'hidden' }, pressed && { opacity: 0.9 }]}
    >
      {children}
    </Pressable>
  );
}

export function Section({ label, right, children }: { label: string; right?: ReactNode; children?: ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ gap: space(2), marginTop: space(2) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: space(1) }}>
        <Text style={[styles.section, { color: t.textFaint, flex: 1 }]}>{label.toUpperCase()}</Text>
        {right}
      </View>
      {children}
    </View>
  );
}

type Tone = 'ok' | 'warn' | 'bad' | 'accent' | 'dim';

function toneColors(t: Theme, tone: Tone) {
  switch (tone) {
    case 'ok':
      return [t.ok, t.okSoft];
    case 'warn':
      return [t.warn, t.warnSoft];
    case 'bad':
      return [t.bad, t.badSoft];
    case 'accent':
      return [t.accent, t.accentSoft];
    default:
      return [t.textDim, t.cardAlt];
  }
}

export function Pill({ label, tone = 'dim', icon }: { label: string; tone?: Tone; icon?: string }) {
  const t = useTheme();
  const [fg, bg] = toneColors(t, tone);
  return (
    <View style={[styles.pill, { backgroundColor: bg }]}>
      {icon ? <Icon name={icon} size={13} color={fg} /> : <View style={[styles.dot, { backgroundColor: fg }]} />}
      <Text style={[styles.pillText, { color: fg }]}>{label}</Text>
    </View>
  );
}

export function Button({
  label,
  onPress,
  icon,
  kind = 'primary',
  busy = false,
  disabled = false,
  style,
  compact = false,
}: {
  label: string;
  onPress: () => void;
  icon?: string;
  kind?: 'primary' | 'danger' | 'ok' | 'subtle' | 'ghost';
  busy?: boolean;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  compact?: boolean;
}) {
  const t = useTheme();
  const map = {
    primary: [t.accent, t.dark ? '#0B0D12' : '#FFFFFF', 'transparent'],
    ok: [t.ok, t.dark ? '#06210F' : '#FFFFFF', 'transparent'],
    danger: [t.badSoft, t.bad, 'transparent'],
    subtle: [t.cardAlt, t.text, t.border],
    ghost: ['transparent', t.accent, 'transparent'],
  } as const;
  const [bg, fg, border] = map[kind];
  const off = disabled || busy;
  return (
    <Pressable
      onPress={onPress}
      disabled={off}
      android_ripple={{ color: 'rgba(127,127,127,0.25)', foreground: true }}
      style={({ pressed }) => [
        styles.button,
        compact && styles.buttonCompact,
        { backgroundColor: bg, borderColor: border, opacity: off ? 0.5 : pressed ? 0.85 : 1 },
        style,
      ]}
    >
      {busy ? <ActivityIndicator size="small" color={fg} /> : icon ? <Icon name={icon} size={compact ? 16 : 19} color={fg} /> : null}
      <Text style={[styles.buttonText, compact && { fontSize: 13.5 }, { color: fg }]}>{label}</Text>
    </Pressable>
  );
}

export function Banner({ tone = 'warn', icon, title, children }: { tone?: Tone; icon?: string; title: string; children?: ReactNode }) {
  const t = useTheme();
  const [fg, bg] = toneColors(t, tone);
  return (
    <View style={[styles.banner, { backgroundColor: bg }]}>
      <Icon name={icon ?? (tone === 'bad' ? 'alert-octagon' : tone === 'ok' ? 'check-circle' : 'information')} size={20} color={fg} />
      <View style={{ flex: 1, gap: 4 }}>
        <Text style={{ color: fg, fontWeight: '700', fontSize: 14.5 }}>{title}</Text>
        {typeof children === 'string' ? <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>{children}</Text> : children}
      </View>
    </View>
  );
}

export function Empty({ icon, title, body }: { icon: string; title: string; body?: string }) {
  const t = useTheme();
  return (
    <View style={styles.empty}>
      <View style={[styles.emptyIcon, { backgroundColor: t.cardAlt }]}>
        <Icon name={icon} size={26} color={t.textFaint} />
      </View>
      <Text style={{ color: t.text, fontWeight: '600', fontSize: 15 }}>{title}</Text>
      {body ? <Text style={{ color: t.textDim, textAlign: 'center', fontSize: 13.5, lineHeight: 19 }}>{body}</Text> : null}
    </View>
  );
}

export function Mono({
  children,
  style,
  selectable = true,
  numberOfLines,
}: {
  children: ReactNode;
  style?: StyleProp<TextStyle>;
  selectable?: boolean;
  numberOfLines?: number;
}) {
  const t = useTheme();
  return (
    <Text
      selectable={selectable}
      numberOfLines={numberOfLines}
      style={[{ fontFamily: t.mono, color: t.text, fontSize: 13, lineHeight: 19 }, style]}
    >
      {children}
    </Text>
  );
}

export function KV({ k, v, mono }: { k: string; v: ReactNode; mono?: boolean }) {
  const t = useTheme();
  return (
    <View style={styles.kv}>
      <Text style={{ color: t.textDim, fontSize: 13.5 }}>{k}</Text>
      {typeof v === 'string' || typeof v === 'number' ? (
        <Text selectable style={{ color: t.text, fontSize: 13.5, fontFamily: mono ? t.mono : undefined, flexShrink: 1, textAlign: 'right' }}>
          {v}
        </Text>
      ) : (
        v
      )}
    </View>
  );
}

export function Bar({ pct, color, height = 6 }: { pct: number; color?: string; height?: number }) {
  const t = useTheme();
  const c = color ?? (pct > 90 ? t.bad : pct > 70 ? t.warn : t.accent);
  return (
    <View style={{ height, borderRadius: height, backgroundColor: t.cardAlt, overflow: 'hidden' }}>
      <View style={{ width: `${Math.max(0, Math.min(100, pct))}%`, height, borderRadius: height, backgroundColor: c }} />
    </View>
  );
}

export const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'flex-end', paddingHorizontal: space(5), paddingBottom: space(4), gap: space(3) },
  back: { width: 36, height: 40, alignItems: 'center', justifyContent: 'center', marginLeft: -space(2) },
  title: { fontSize: 30, fontWeight: '800', letterSpacing: -0.6 },
  subtitle: { fontSize: 14, marginTop: 2 },
  card: { borderRadius: radius.lg, borderWidth: StyleSheet.hairlineWidth, padding: space(4) },
  section: { fontSize: 12, fontWeight: '700', letterSpacing: 0.8 },
  pill: { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 9, paddingVertical: 3.5, borderRadius: 99, alignSelf: 'flex-start' },
  pillText: { fontSize: 12, fontWeight: '700' },
  dot: { width: 6, height: 6, borderRadius: 3 },
  button: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: space(4),
    height: 46,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  buttonCompact: { height: 36, paddingHorizontal: space(3), borderRadius: radius.sm + 2 },
  buttonText: { fontSize: 15, fontWeight: '700' },
  banner: { flexDirection: 'row', gap: space(3), padding: space(4), borderRadius: radius.lg, alignItems: 'flex-start' },
  empty: { alignItems: 'center', gap: space(2), paddingVertical: space(8), paddingHorizontal: space(6) },
  emptyIcon: { width: 56, height: 56, borderRadius: 28, alignItems: 'center', justifyContent: 'center', marginBottom: space(1) },
  kv: { flexDirection: 'row', justifyContent: 'space-between', gap: space(4), paddingVertical: 5 },
});
