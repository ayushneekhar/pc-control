import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Card, Empty, Icon, List, ListRow, Pill, Screen, Section, Tone } from '../components/ui';
import {
  createSeerrRequest,
  getSeerrDetails,
  getSeerrRequests,
  searchSeerr,
  SeerrItem,
  SeerrRequestItem,
  SeerrStatus,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { ago } from '../lib/format';
import { radius, screenPad, space, useTheme } from '../theme';

const CACHE_REQUESTS = 'seerr:requests';

function statusMeta(st: SeerrStatus): { label: string; tone: Tone } | null {
  switch (st) {
    case 'available':
      return { label: 'Available', tone: 'ok' };
    case 'grabbing':
      return { label: 'Grabbing', tone: 'accent' };
    case 'partial':
      return { label: 'Partial', tone: 'warn' };
    case 'requested':
      return { label: 'Requested', tone: 'dim' };
    case 'declined':
      return { label: 'Declined', tone: 'bad' };
    default:
      return null;
  }
}

function isoAge(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  return ago(ms / 1000);
}

function Poster({ uri }: { uri: string | null }) {
  const t = useTheme();
  if (!uri) {
    return (
      <View style={[st.poster, { backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }]}>
        <Icon name="movie-outline" size={20} color={t.textFaint} />
      </View>
    );
  }
  return <Image source={{ uri }} style={[st.poster, { backgroundColor: t.cardAlt }]} resizeMode="cover" />;
}

function RowSkeleton() {
  const t = useTheme();
  return (
    <Card style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}>
      <View style={[st.poster, { backgroundColor: t.cardAlt }]} />
      <View style={{ flex: 1, gap: 8 }}>
        <View style={[st.skel, { width: '70%', height: 15, backgroundColor: t.cardAlt }]} />
        <View style={[st.skel, { width: '40%', height: 12, backgroundColor: t.cardAlt }]} />
      </View>
      <View style={[st.skel, { width: 68, height: 26, borderRadius: radius.pill, backgroundColor: t.cardAlt }]} />
    </Card>
  );
}

function SeasonSheet({
  item,
  loading,
  submitting,
  selected,
  onToggle,
  onSelectAll,
  onSubmit,
  onClose,
}: {
  item: SeerrItem | null;
  loading: boolean;
  submitting: boolean;
  selected: number[];
  onToggle: (sn: number) => void;
  onSelectAll: () => void;
  onSubmit: () => void;
  onClose: () => void;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const seasons = item?.seasons ?? [];
  const availableToPick = seasons.filter((s) => s.status === 'none');
  const allPicked = availableToPick.length > 0 && availableToPick.every((s) => selected.includes(s.seasonNumber));

  return (
    <Modal
      visible={!!item}
      animationType="slide"
      onRequestClose={onClose}
      transparent
      statusBarTranslucent
      navigationBarTranslucent
    >
      <View style={[st.sheetWrap, { backgroundColor: t.overlay }]}>
        <Pressable style={{ flex: 1 }} onPress={onClose} />
        <View style={[st.sheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4) }]}>
          <View style={[st.grabber, { backgroundColor: t.border }]} />
          <View style={st.sheetHead}>
            <Text style={{ color: t.text, fontSize: 18, fontWeight: '600', flex: 1 }} numberOfLines={1}>
              {item?.title ?? ''}
            </Text>
            {availableToPick.length > 1 ? (
              <Pressable onPress={onSelectAll} hitSlop={8}>
                <Text style={{ color: t.accent, fontSize: 13, fontWeight: '500' }}>
                  {allPicked ? 'Clear' : 'All'}
                </Text>
              </Pressable>
            ) : null}
          </View>
          {loading ? (
            <View style={{ padding: space(6), alignItems: 'center' }}>
              <ActivityIndicator size="small" color={t.accent} />
            </View>
          ) : (
            <ScrollView contentContainerStyle={{ paddingHorizontal: screenPad, paddingBottom: space(3) }}>
              <List>
                {seasons.map((s, idx) => {
                  const locked = s.status !== 'none';
                  const on = selected.includes(s.seasonNumber);
                  const badge = statusMeta(s.status);
                  return (
                    <ListRow
                      key={s.seasonNumber}
                      title={s.name}
                      subtitle={s.episodeCount ? `${s.episodeCount} eps` : undefined}
                      last={idx === seasons.length - 1}
                      chevron={false}
                      onPress={locked ? undefined : () => onToggle(s.seasonNumber)}
                      trailing={
                        locked && badge ? (
                          <Pill label={badge.label} tone={badge.tone} />
                        ) : (
                          <Icon
                            name={on ? 'checkbox-marked' : 'checkbox-blank-outline'}
                            size={22}
                            color={on ? t.accent : t.textFaint}
                          />
                        )
                      }
                    />
                  );
                })}
              </List>
            </ScrollView>
          )}
          <View style={{ flexDirection: 'row', gap: space(2), paddingHorizontal: screenPad }}>
            <Button label="Cancel" kind="subtle" style={{ flex: 1 }} disabled={submitting} onPress={onClose} />
            <Button
              label="Request"
              icon="plus"
              style={{ flex: 1 }}
              busy={submitting}
              disabled={loading || selected.length === 0}
              onPress={onSubmit}
            />
          </View>
        </View>
      </View>
    </Modal>
  );
}

export default function SeerrScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SeerrItem[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [requests, setRequests] = useState<SeerrRequestItem[] | null>(() => cacheGet<SeerrRequestItem[]>(CACHE_REQUESTS));
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  // TV season picker state
  const [tvItem, setTvItem] = useState<SeerrItem | null>(null);
  const [tvLoading, setTvLoading] = useState(false);
  const [tvSelected, setTvSelected] = useState<number[]>([]);
  const [tvSubmitting, setTvSubmitting] = useState(false);

  const loadRequests = useCallback(async () => {
    try {
      const res = await getSeerrRequests();
      cacheSet(CACHE_REQUESTS, res.requests);
      setRequests(res.requests);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadRequests();
    }, [loadRequests]),
  );

  const runSearch = async (text?: string) => {
    const q = (text ?? query).trim();
    if (!q) {
      setResults(null);
      return;
    }
    setSearching(true);
    setErr(null);
    try {
      const res = await searchSeerr(q);
      setResults(res.results);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setSearching(false);
    }
  };

  const requestItem = async (item: SeerrItem) => {
    if (item.mediaType === 'tv') {
      setTvItem(item);
      setTvLoading(true);
      setTvSelected([]);
      try {
        const det = await getSeerrDetails('tv', item.id);
        setTvItem(det);
        const pickable = (det.seasons ?? []).filter((s) => s.status === 'none').map((s) => s.seasonNumber);
        setTvSelected(pickable);
      } catch (e) {
        setTvItem(null);
        setErr((e as Error).message);
      } finally {
        setTvLoading(false);
      }
      return;
    }

    const key = `${item.mediaType}:${item.id}`;
    setBusyId(key);
    setErr(null);
    try {
      await createSeerrRequest('movie', item.id);
      setResults((prev) =>
        prev ? prev.map((x) => (x.id === item.id && x.mediaType === 'movie' ? { ...x, status: 'requested' } : x)) : prev,
      );
      void loadRequests();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  const submitTvRequest = async () => {
    if (!tvItem || tvSelected.length === 0) return;
    setTvSubmitting(true);
    setErr(null);
    try {
      await createSeerrRequest('tv', tvItem.id, tvSelected);
      setResults((prev) =>
        prev ? prev.map((x) => (x.id === tvItem.id && x.mediaType === 'tv' ? { ...x, status: 'requested' } : x)) : prev,
      );
      setTvItem(null);
      void loadRequests();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setTvSubmitting(false);
    }
  };

  const toggleSeason = (sn: number) => {
    setTvSelected((prev) => (prev.includes(sn) ? prev.filter((x) => x !== sn) : [...prev, sn].sort((a, b) => a - b)));
  };

  const toggleAllSeasons = () => {
    const pickable = (tvItem?.seasons ?? []).filter((s) => s.status === 'none').map((s) => s.seasonNumber);
    const allPicked = pickable.every((sn) => tvSelected.includes(sn));
    setTvSelected(allPicked ? [] : pickable);
  };

  return (
    <Screen
      title="Seerr"
      onBack={() => nav.goBack()}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await loadRequests();
        if (query.trim()) await runSearch();
        setRefreshing(false);
      }}
    >
      {err ? <Banner tone="bad" title="Seerr">{err}</Banner> : null}

      <View style={[st.searchBox, { backgroundColor: t.card, borderColor: t.border }]}>
        <Icon name="magnify" size={20} color={t.textFaint} />
        <TextInput
          value={query}
          onChangeText={(v) => {
            setQuery(v);
            if (!v.trim()) setResults(null);
          }}
          onSubmitEditing={() => void runSearch()}
          returnKeyType="search"
          placeholder="Search movies or TV"
          placeholderTextColor={t.textFaint}
          selectionColor={t.accent}
          style={[st.searchInput, { color: t.text }]}
        />
        {searching ? (
          <ActivityIndicator size="small" color={t.accent} />
        ) : query ? (
          <Pressable
            onPress={() => {
              setQuery('');
              setResults(null);
            }}
            hitSlop={8}
            accessibilityLabel="Clear search"
          >
            <Icon name="close-circle" size={18} color={t.textFaint} />
          </Pressable>
        ) : null}
      </View>

      {results !== null ? (
        <Section label={`Results (${results.length})`}>
          {results.length ? (
            <View style={{ gap: space(2.5) }}>
              {results.map((item) => {
                const badge = statusMeta(item.status);
                const canRequest = item.status === 'none' || (item.mediaType === 'tv' && item.status === 'partial');
                const key = `${item.mediaType}:${item.id}`;
                return (
                  <Card key={key} style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}>
                    <Poster uri={item.poster} />
                    <View style={{ flex: 1, gap: 4 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        {item.title}
                      </Text>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5), flexWrap: 'wrap' }}>
                        <Pill label={item.mediaType === 'tv' ? 'TV' : 'Movie'} tone="faint" />
                        {item.year ? <Text style={{ color: t.textFaint, fontSize: 12 }}>{item.year}</Text> : null}
                        {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                      </View>
                    </View>
                    {canRequest ? (
                      <Button
                        label="Request"
                        kind="primary"
                        compact
                        busy={busyId === key}
                        onPress={() => void requestItem(item)}
                      />
                    ) : null}
                  </Card>
                );
              })}
            </View>
          ) : (
            <Card>
              <Empty icon="magnify" title="No matches" />
            </Card>
          )}
        </Section>
      ) : (
        <Section label="Recent requests">
          {!requests ? (
            <View style={{ gap: space(2.5) }}>
              <RowSkeleton />
              <RowSkeleton />
              <RowSkeleton />
            </View>
          ) : requests.length ? (
            <View style={{ gap: space(2.5) }}>
              {requests.map((r) => {
                const badge = statusMeta(r.status);
                const seasonTag =
                  r.mediaType === 'tv' && r.seasons.length
                    ? `S${r.seasons.join(', S')}`
                    : r.mediaType === 'tv'
                      ? 'TV'
                      : 'Movie';
                return (
                  <Card key={r.id} style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}>
                    <Poster uri={r.poster} />
                    <View style={{ flex: 1, gap: 4 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        {r.title}
                      </Text>
                      <Text style={{ color: t.textFaint, fontSize: 12 }} numberOfLines={1}>
                        {seasonTag}
                        {r.year ? ` · ${r.year}` : ''}
                        {r.requestedBy ? ` · ${r.requestedBy}` : ''}
                        {r.createdAt ? ` · ${isoAge(r.createdAt)}` : ''}
                      </Text>
                    </View>
                    {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                  </Card>
                );
              })}
            </View>
          ) : (
            <Card>
              <Empty icon="movie-search-outline" title="No requests yet" />
            </Card>
          )}
        </Section>
      )}

      <SeasonSheet
        item={tvItem}
        loading={tvLoading}
        submitting={tvSubmitting}
        selected={tvSelected}
        onToggle={toggleSeason}
        onSelectAll={toggleAllSeasons}
        onSubmit={() => void submitTvRequest()}
        onClose={() => !tvSubmitting && setTvItem(null)}
      />
    </Screen>
  );
}

const st = StyleSheet.create({
  searchBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: space(3.5),
    height: 46,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
  },
  searchInput: { flex: 1, fontSize: 15, height: '100%' },
  poster: { width: 44, height: 64, borderRadius: 8 },
  skel: { borderRadius: 4 },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '78%', borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg, paddingTop: space(2) },
  grabber: { width: 38, height: 4, borderRadius: 2, alignSelf: 'center' },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: screenPad,
    paddingTop: space(3),
    paddingBottom: space(2),
  },
});
