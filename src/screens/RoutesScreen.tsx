import { useFocusEffect } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { Linking, Pressable, StyleSheet, Text, View } from 'react-native';
import { Banner, Card, Icon, Screen } from '../components/ui';
import { get, Route } from '../lib/api';
import { radius, space, useTheme } from '../theme';

const PALETTE = ['#7C9CFF', '#F59E8B', '#5FD3B3', '#E7B75F', '#C79BFF', '#6CC6F0', '#FF8FB1', '#9CD46B'];

function tint(id: string) {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

function RouteCard({ r }: { r: Route }) {
  const t = useTheme();
  const c = tint(r.name);
  return (
    <Pressable
      onPress={() => Linking.openURL(r.url)}
      android_ripple={{ color: t.accentSoft, foreground: true }}
      style={({ pressed }) => [st.tile, { backgroundColor: t.card, borderColor: t.border, opacity: pressed ? 0.9 : 1 }]}
    >
      <View style={[st.tileIcon, { backgroundColor: c + (t.dark ? '24' : '1F') }]}>
        <Icon name={r.icon} size={24} color={c} />
      </View>
      <View style={{ gap: 2 }}>
        <Text style={{ color: t.text, fontSize: 15.5, fontWeight: '700' }} numberOfLines={1}>
          {r.name}
        </Text>
        <Text style={{ color: t.textDim, fontSize: 12.5 }} numberOfLines={1}>
          {r.subtitle || r.path}
        </Text>
        <Text style={{ color: t.textFaint, fontSize: 11.5, fontFamily: t.mono }} numberOfLines={1}>
          {r.port === 443 ? r.path : `:${r.port}`}
        </Text>
      </View>
    </Pressable>
  );
}

export default function RoutesScreen() {
  const t = useTheme();
  const [routes, setRoutes] = useState<Route[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      const res = await get<{ routes: Route[] }>('/pc/api/routes');
      setRoutes(res.routes);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  return (
    <Screen
      title="Routes"
      subtitle="Everything served on the tailnet"
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await load();
        setRefreshing(false);
      }}
    >
      {err ? <Banner tone="bad" title="Can't load routes">{err}</Banner> : null}
      {routes ? (
        <View style={st.grid}>
          {routes.map((r) => (
            <View key={r.id} style={st.cell}>
              <RouteCard r={r} />
            </View>
          ))}
        </View>
      ) : !err ? (
        <Card>
          <Text style={{ color: t.textDim }}>Loading…</Text>
        </Card>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', marginHorizontal: -space(1.5) },
  cell: { width: '50%', padding: space(1.5) },
  tile: { borderRadius: radius.lg, borderWidth: StyleSheet.hairlineWidth, padding: space(4), gap: space(3), overflow: 'hidden', minHeight: 132 },
  tileIcon: { width: 44, height: 44, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
});
