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
import { radius, screenPad, space, Theme, useTheme } from '../theme';

export type IconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

/** Monochrome outline glyphs only; a filled name falls back to its -outline sibling when there is one. */
export function Icon({ name, size = 20, color }: { name: string; size?: number; color?: string }) {
  const t = useTheme();
  const outline = `${name}-outline`;
  const usable = outline in MaterialCommunityIcons.glyphMap ? outline : name;
  const n = (usable in MaterialCommunityIcons.glyphMap ? usable : 'web') as IconName;
  return <MaterialCommunityIcons name={n} size={size} color={color ?? t.textDim} />;
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
      <View style={{ flex: 1, gap: 2 }}>
        {subtitle ? (
          typeof subtitle === 'string' ? (
            <Text style={[styles.subtitle, { color: t.textDim }]}>{subtitle}</Text>
          ) : (
            subtitle
          )
        ) : null}
        <Text style={[styles.title, { color: t.text }]}>{title}</Text>
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
      <View style={{ paddingHorizontal: screenPad, gap: space(3) }}>{children}</View>
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
  const base = [styles.card, { backgroundColor: t.card }, style];
  if (!onPress && !onLongPress) return <View style={base}>{children}</View>;
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [...base, { overflow: 'hidden' }, pressed && { opacity: 0.9 }]}
    >
      {children}
    </Pressable>
  );
}

export function List({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const t = useTheme();
  return <View style={[styles.list, { backgroundColor: t.card }, style]}>{children}</View>;
}

export function ListRow({
  leading,
  icon,
  title,
  subtitle,
  value,
  trailing,
  right,
  onPress,
  onLongPress,
  last = false,
  danger = false,
  chevron,
}: {
  leading?: ReactNode;
  icon?: string;
  title: string;
  subtitle?: ReactNode;
  value?: ReactNode;
  trailing?: ReactNode;
  right?: ReactNode;
  onPress?: () => void;
  onLongPress?: () => void;
  last?: boolean;
  danger?: boolean;
  chevron?: boolean;
}) {
  const t = useTheme();
  const showChevron = chevron ?? (!!onPress && trailing == null && value == null && right == null);
  const body = (
    <>
      {leading}
      {icon ? <Icon name={icon} size={20} color={danger ? t.bad : t.textDim} /> : null}
      <View style={{ flex: 1, gap: 1 }}>
        <Text style={[styles.rowTitle, { color: danger ? t.bad : t.text }]} numberOfLines={1}>
          {title}
        </Text>
        {subtitle != null ? (
          typeof subtitle === 'string' ? (
            <Text style={[styles.rowSubtitle, { color: t.textFaint }]} numberOfLines={1}>
              {subtitle}
            </Text>
          ) : (
            subtitle
          )
        ) : null}
      </View>
      {right}
      {trailing != null ? (
        typeof trailing === 'string' || typeof trailing === 'number' ? (
          <Text style={[styles.rowValue, { color: t.textDim }]}>{trailing}</Text>
        ) : (
          trailing
        )
      ) : null}
      {showChevron ? <Text style={[styles.chevron, { color: t.textFaint }]}>›</Text> : null}
    </>
  );
  if (!onPress && !onLongPress)
    return (
      <View style={[styles.row, !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: t.border }]}>
        {body}
      </View>
    );
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [
        styles.row,
        !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: t.border },
        pressed && { opacity: 0.82 },
      ]}
    >
      {body}
    </Pressable>
  );
}

export type StatItem = { label: string; value: ReactNode; unit?: string };

export function StatStrip({ items, onPress }: { items: StatItem[]; onPress?: () => void }) {
  const t = useTheme();
  const body = (
    <View style={{ flexDirection: 'row' }}>
      {items.map((it, i) => (
        <View
          key={`${it.label}-${i}`}
          style={[
            { flex: 1, paddingHorizontal: space(4), gap: 2 },
            i > 0 && { borderLeftWidth: StyleSheet.hairlineWidth, borderLeftColor: t.border },
          ]}
        >
          <Text style={[styles.statLabel, { color: t.textFaint }]} numberOfLines={1}>
            {it.label}
          </Text>
          <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 1 }}>
            {typeof it.value === 'string' || typeof it.value === 'number' ? (
              <Text
                style={[styles.statValue, { color: t.text }]}
                numberOfLines={1}
                adjustsFontSizeToFit
                minimumFontScale={0.7}
              >
                {it.value}
              </Text>
            ) : (
              it.value
            )}
            {it.unit ? (
              <Text
                style={[styles.statUnit, { color: t.textDim }]}
                numberOfLines={1}
                adjustsFontSizeToFit
                minimumFontScale={0.7}
              >
                {it.unit}
              </Text>
            ) : null}
          </View>
        </View>
      ))}
    </View>
  );
  const style = { backgroundColor: t.card, borderRadius: radius.md, paddingVertical: space(3.5), overflow: 'hidden' as const };
  return onPress ? (
    <Pressable
      onPress={onPress}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [style, pressed && { opacity: 0.9 }]}
    >
      {body}
    </Pressable>
  ) : (
    <View style={style}>{body}</View>
  );
}

export function Section({ label, right, children }: { label: string; right?: ReactNode; children?: ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ gap: space(2), marginTop: space(2) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: space(0.5) }}>
        <Text style={[styles.section, { color: t.textDim, flex: 1 }]}>{label}</Text>
        {right}
      </View>
      {children}
    </View>
  );
}

export type Tone = 'ok' | 'warn' | 'bad' | 'accent' | 'dim' | 'faint';

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
    case 'faint':
      return [t.textFaint, t.cardAlt];
    default:
      return [t.textDim, t.cardAlt];
  }
}

export function StatusDot({ tone = 'faint', size = 7 }: { tone?: Tone; size?: number }) {
  const t = useTheme();
  const [fg] = toneColors(t, tone);
  return <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: fg }} />;
}

export function StatusRing({ tone = 'ok', icon }: { tone?: Tone; icon?: string }) {
  const t = useTheme();
  const [fg] = toneColors(t, tone);
  return (
    <View style={[styles.ring, { borderColor: fg }]}>
      <Icon name={icon ?? (tone === 'bad' ? 'alert-outline' : tone === 'warn' ? 'alert-outline' : 'check')} size={18} color={fg} />
    </View>
  );
}

export function Pill({
  label,
  tone = 'dim',
  icon,
  dot = false,
}: {
  label: string;
  tone?: Tone;
  icon?: string;
  dot?: boolean;
}) {
  const t = useTheme();
  const [fg, bg] = toneColors(t, tone);
  return (
    <View style={[styles.pill, { backgroundColor: bg }]}>
      {icon ? <Icon name={icon} size={13} color={fg} /> : dot ? <StatusDot tone={tone} size={6} /> : null}
      <Text style={[styles.pillText, { color: fg }]} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  style,
}: {
  options: { id: T; label: string; icon?: string }[];
  value: T;
  onChange: (id: T) => void;
  style?: StyleProp<ViewStyle>;
}) {
  const t = useTheme();
  return (
    <View style={[styles.segmented, { backgroundColor: t.card }, style]} accessibilityRole="tablist">
      {options.map((o) => {
        const on = o.id === value;
        return (
          <Pressable
            key={o.id}
            onPress={() => onChange(o.id)}
            accessibilityRole="tab"
            accessibilityState={{ selected: on }}
            android_ripple={{ color: t.ripple, borderless: false }}
            style={[styles.segment, on && { backgroundColor: t.cardAlt }]}
          >
            {o.icon ? <Icon name={o.icon} size={15} color={on ? t.text : t.textDim} /> : null}
            <Text style={[styles.segmentText, { color: on ? t.text : t.textDim }]} numberOfLines={1}>
              {o.label}
            </Text>
          </Pressable>
        );
      })}
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
    primary: [t.accent, t.onAccent],
    ok: [t.accent, t.onAccent],
    danger: [t.badSoft, t.bad],
    subtle: [t.cardAlt, t.text],
    ghost: ['transparent', t.accent],
  } as const;
  const [bg, fg] = map[kind];
  const off = disabled || busy;
  return (
    <Pressable
      onPress={onPress}
      disabled={off}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [
        styles.button,
        compact && styles.buttonCompact,
        { backgroundColor: bg, opacity: off ? 0.45 : pressed ? 0.85 : 1, overflow: 'hidden' },
        style,
      ]}
    >
      {busy ? (
        <ActivityIndicator size="small" color={fg} />
      ) : icon ? (
        <Icon name={icon} size={compact ? 16 : 19} color={fg} />
      ) : null}
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
        <Text style={{ color: fg, fontWeight: '600', fontSize: 14.5 }}>{title}</Text>
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
        <Text
          selectable
          style={[
            { color: t.text, fontSize: 13.5, fontFamily: mono ? t.mono : undefined, flexShrink: 1, textAlign: 'right' },
            !mono && { fontVariant: ['tabular-nums'] },
          ]}
        >
          {v}
        </Text>
      ) : (
        v
      )}
    </View>
  );
}

export function Bar({ pct, color, height = 4 }: { pct: number; color?: string; height?: number }) {
  const t = useTheme();
  const c = color ?? (pct > 85 ? t.warn : t.text);
  return (
    <View style={{ height, borderRadius: height, backgroundColor: t.cardAlt, overflow: 'hidden' }}>
      <View style={{ width: `${Math.max(0, Math.min(100, pct))}%`, height, borderRadius: height, backgroundColor: c }} />
    </View>
  );
}

export const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'flex-end', paddingHorizontal: screenPad, paddingBottom: space(4), gap: space(3) },
  back: { width: 36, height: 40, alignItems: 'center', justifyContent: 'center' },
  title: { fontSize: 30, fontWeight: '600', letterSpacing: -0.5 },
  subtitle: { fontSize: 12.5, marginTop: 2 },
  card: { borderRadius: radius.md, padding: space(4) },
  list: { borderRadius: radius.md, overflow: 'hidden' },
  section: { fontSize: 12.5, fontWeight: '500' },
  row: {
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(3.5),
    paddingHorizontal: space(4),
    paddingVertical: space(2.5),
  },
  rowTitle: { fontSize: 15, fontWeight: '500' },
  rowSubtitle: { fontSize: 12.5 },
  rowValue: { fontSize: 13.5, fontVariant: ['tabular-nums'] },
  chevron: { fontSize: 20, fontWeight: '400' },
  statLabel: { fontSize: 11.5 },
  statValue: { fontSize: 20, fontWeight: '500', fontVariant: ['tabular-nums'] },
  statUnit: { fontSize: 12 },
  ring: { width: 38, height: 38, borderRadius: 19, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  pill: { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 9, paddingVertical: 4, borderRadius: radius.pill, alignSelf: 'flex-start' },
  pillText: { fontSize: 12, fontWeight: '500' },
  segmented: { flexDirection: 'row', padding: 3, borderRadius: radius.sm, gap: 2 },
  segment: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    height: 36,
    borderRadius: 10,
    paddingHorizontal: 6,
    overflow: 'hidden',
  },
  segmentText: { fontSize: 13, fontWeight: '500' },
  button: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: space(4),
    height: 44,
    borderRadius: radius.sm,
  },
  buttonCompact: { height: 36, paddingHorizontal: space(3), borderRadius: 10 },
  buttonText: { fontSize: 15, fontWeight: '600' },
  banner: { flexDirection: 'row', gap: space(3), padding: space(4), borderRadius: radius.md, alignItems: 'flex-start' },
  empty: { alignItems: 'center', gap: space(2), paddingVertical: space(8), paddingHorizontal: space(6) },
  emptyIcon: { width: 56, height: 56, borderRadius: 28, alignItems: 'center', justifyContent: 'center', marginBottom: space(1) },
  kv: { flexDirection: 'row', justifyContent: 'space-between', gap: space(4), paddingVertical: 5 },
});
