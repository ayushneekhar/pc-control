import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useRef, useState } from 'react';
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
  getSeerrCollection,
  getSeerrDetails,
  getSeerrRequests,
  searchSeerr,
  SeerrCollection,
  SeerrCollectionRef,
  SeerrItem,
  SeerrRequestItem,
  SeerrStatus,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { ago } from '../lib/format';
import { radius, screenPad, space, useTheme } from '../theme';

const CACHE_REQUESTS = 'seerr:requests';
const SEARCH_DEBOUNCE_MS = 350;
const MIN_AUTO_SEARCH_LEN = 3;

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
    case 'deleted':
      return { label: 'Deleted', tone: 'faint' };
    case 'blocklisted':
      return { label: 'Blocklisted', tone: 'warn' };
    case 'failed':
      return { label: 'Failed', tone: 'bad' };
    case 'declined':
      return { label: 'Declined', tone: 'bad' };
    default:
      return null;
  }
}

function isRequestableMovie(status: SeerrStatus): boolean {
  return status === 'none' || status === 'deleted';
}

function isRequestableSeason(status: SeerrStatus): boolean {
  return status === 'none' || status === 'deleted';
}

function isoAge(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  return ago(ms / 1000);
}

function Poster({
  uri,
  size = 'sm',
  kind = 'movie',
}: {
  uri: string | null;
  size?: 'sm' | 'md';
  kind?: 'movie' | 'tv' | 'collection';
}) {
  const t = useTheme();
  const dim = size === 'md' ? st.posterMd : st.poster;
  if (!uri) {
    const iconName = kind === 'collection' ? 'layers' : kind === 'tv' ? 'television' : 'movie';
    return (
      <View style={[dim, { backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }]}>
        <Icon name={iconName} size={size === 'md' ? 24 : 20} color={t.textFaint} />
      </View>
    );
  }
  return <Image source={{ uri }} style={[dim, { backgroundColor: t.cardAlt }]} resizeMode="cover" />;
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
  const availableToPick = seasons.filter((s) => isRequestableSeason(s.status));
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
                  const locked = !isRequestableSeason(s.status);
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
                          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                            {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                            <Icon
                              name={on ? 'checkbox-marked' : 'checkbox-blank-outline'}
                              size={22}
                              color={on ? t.accent : t.textFaint}
                            />
                          </View>
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

function MovieDetailSheet({
  item,
  loading,
  submitting,
  onRequest,
  onOpenCollection,
  onClose,
}: {
  item: SeerrItem | null;
  loading: boolean;
  submitting: boolean;
  onRequest: () => void;
  onOpenCollection: (col: SeerrCollectionRef) => void;
  onClose: () => void;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const badge = item ? statusMeta(item.status) : null;
  const canRequest = item ? isRequestableMovie(item.status) : false;

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
          <ScrollView contentContainerStyle={{ paddingHorizontal: screenPad, paddingTop: space(3), paddingBottom: space(3), gap: space(3) }}>
            {item ? (
              <>
                <View style={{ flexDirection: 'row', gap: space(3), alignItems: 'flex-start' }}>
                  <Poster uri={item.poster} size="md" kind="movie" />
                  <View style={{ flex: 1, gap: space(1.5) }}>
                    <Text style={{ color: t.text, fontSize: 18, fontWeight: '600' }}>{item.title}</Text>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5), flexWrap: 'wrap' }}>
                      <Pill label="Movie" tone="faint" />
                      {item.year ? (
                        <Text style={[st.num, { color: t.textFaint, fontSize: 12.5 }]}>{item.year}</Text>
                      ) : null}
                      {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                    </View>
                  </View>
                </View>

                {item.overview ? (
                  <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 20 }}>{item.overview}</Text>
                ) : loading ? (
                  <View style={{ paddingVertical: space(2), alignItems: 'center' }}>
                    <ActivityIndicator size="small" color={t.accent} />
                  </View>
                ) : null}

                {item.collection ? (
                  <Card
                    onPress={() => onOpenCollection(item.collection!)}
                    style={{ flexDirection: 'row', alignItems: 'center', gap: space(3), padding: space(3) }}
                  >
                    <Poster uri={item.collection.poster} size="sm" kind="collection" />
                    <View style={{ flex: 1, gap: 2 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        Part of {item.collection.name}
                      </Text>
                      <Text style={{ color: t.textFaint, fontSize: 12.5 }}>View collection</Text>
                    </View>
                    <Text style={{ color: t.textFaint, fontSize: 20, fontWeight: '400' }}>›</Text>
                  </Card>
                ) : null}
              </>
            ) : null}
          </ScrollView>

          <View style={{ flexDirection: 'row', gap: space(2), paddingHorizontal: screenPad }}>
            <Button label="Close" kind="subtle" style={{ flex: 1 }} disabled={submitting} onPress={onClose} />
            {canRequest ? (
              <Button
                label="Request"
                icon="plus"
                style={{ flex: 1 }}
                busy={submitting}
                disabled={submitting}
                onPress={onRequest}
              />
            ) : null}
          </View>
        </View>
      </View>
    </Modal>
  );
}

function CollectionSheet({
  collection,
  seedTitle,
  seedPoster,
  loading,
  error,
  busyId,
  batchSubmitting,
  onRequestMovie,
  onRequestAllMissing,
  onSelectMovie,
  onClose,
}: {
  collection: SeerrCollection | null;
  seedTitle: string;
  seedPoster: string | null;
  loading: boolean;
  error: string | null;
  busyId: string | null;
  batchSubmitting: boolean;
  onRequestMovie: (movie: SeerrItem) => void;
  onRequestAllMissing: () => void;
  onSelectMovie: (movie: SeerrItem) => void;
  onClose: () => void;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const visible = loading || !!collection || !!error;
  const parts = collection?.parts ?? [];
  const availCount = parts.filter((p) => p.status === 'available').length;
  const missingParts = parts.filter((p) => isRequestableMovie(p.status));
  const title = collection?.name || seedTitle || 'Collection';
  const poster = collection?.poster ?? seedPoster;

  const summary =
    parts.length > 0
      ? availCount > 0
        ? `${availCount} of ${parts.length} available`
        : `${parts.length} ${parts.length === 1 ? 'movie' : 'movies'}`
      : '';

  return (
    <Modal
      visible={visible}
      animationType="slide"
      onRequestClose={onClose}
      transparent
      statusBarTranslucent
      navigationBarTranslucent
    >
      <View style={[st.sheetWrap, { backgroundColor: t.overlay }]}>
        <Pressable style={{ flex: 1 }} onPress={onClose} />
        <View style={[st.sheet, st.collectionSheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4) }]}>
          <View style={[st.grabber, { backgroundColor: t.border }]} />
          <View style={{ paddingHorizontal: screenPad, paddingTop: space(3), paddingBottom: space(2), gap: space(2.5) }}>
            <View style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}>
              <Poster uri={poster} size="md" kind="collection" />
              <View style={{ flex: 1, gap: space(1) }}>
                <Text style={{ color: t.text, fontSize: 18, fontWeight: '600' }} numberOfLines={2}>
                  {title}
                </Text>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5), flexWrap: 'wrap' }}>
                  <Pill label="Collection" tone="accent" />
                  {summary ? (
                    <Text style={[st.num, { color: t.textDim, fontSize: 12.5, fontWeight: '500' }]}>{summary}</Text>
                  ) : null}
                </View>
              </View>
            </View>
            {collection?.overview ? (
              <Text style={{ color: t.textDim, fontSize: 13, lineHeight: 19 }} numberOfLines={3}>
                {collection.overview}
              </Text>
            ) : null}
            {error ? <Banner tone="bad" title="Collection">{error}</Banner> : null}
          </View>

          {loading && !collection ? (
            <View style={{ padding: space(6), alignItems: 'center' }}>
              <ActivityIndicator size="small" color={t.accent} />
            </View>
          ) : (
            <ScrollView contentContainerStyle={{ paddingHorizontal: screenPad, paddingBottom: space(3), gap: space(2) }}>
              {parts.map((movie) => {
                const badge = statusMeta(movie.status);
                const canReq = isRequestableMovie(movie.status);
                const key = `movie:${movie.id}`;
                return (
                  <Card
                    key={movie.id}
                    onPress={() => onSelectMovie(movie)}
                    style={{ flexDirection: 'row', gap: space(3), alignItems: 'center', padding: space(3) }}
                  >
                    <Poster uri={movie.poster} size="sm" kind="movie" />
                    <View style={{ flex: 1, gap: 4 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        {movie.title}
                      </Text>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5), flexWrap: 'wrap' }}>
                        {movie.year ? (
                          <Text style={[st.num, { color: t.textFaint, fontSize: 12 }]}>{movie.year}</Text>
                        ) : (
                          <Text style={{ color: t.textFaint, fontSize: 12 }}>TBA</Text>
                        )}
                        {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                      </View>
                    </View>
                    {canReq ? (
                      <Button
                        label="Request"
                        kind="primary"
                        compact
                        busy={busyId === key}
                        disabled={batchSubmitting}
                        onPress={() => onRequestMovie(movie)}
                      />
                    ) : null}
                  </Card>
                );
              })}
            </ScrollView>
          )}

          <View style={{ flexDirection: 'row', gap: space(2), paddingHorizontal: screenPad, paddingTop: space(1) }}>
            <Button label="Close" kind="subtle" style={{ flex: missingParts.length > 0 ? 0.55 : 1 }} disabled={batchSubmitting} onPress={onClose} />
            {missingParts.length > 0 ? (
              <Button
                label={`Request all missing (${missingParts.length})`}
                icon="plus"
                style={{ flex: 1 }}
                busy={batchSubmitting}
                disabled={loading || batchSubmitting}
                onPress={onRequestAllMissing}
              />
            ) : null}
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

  // Debounce & race-condition guard for search
  const searchSeqRef = useRef(0);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // TV season picker state
  const [tvItem, setTvItem] = useState<SeerrItem | null>(null);
  const [tvLoading, setTvLoading] = useState(false);
  const [tvSelected, setTvSelected] = useState<number[]>([]);
  const [tvSubmitting, setTvSubmitting] = useState(false);

  // Movie detail sheet state
  const [movieDetail, setMovieDetail] = useState<SeerrItem | null>(null);
  const [movieLoading, setMovieLoading] = useState(false);
  const [movieSubmitting, setMovieSubmitting] = useState(false);
  const movieSeqRef = useRef(0);

  // Collection detail sheet state
  const [collection, setCollection] = useState<SeerrCollection | null>(null);
  const [collectionSeed, setCollectionSeed] = useState<{ title: string; poster: string | null }>({
    title: '',
    poster: null,
  });
  const [collectionLoading, setCollectionLoading] = useState(false);
  const [collectionErr, setCollectionErr] = useState<string | null>(null);
  const [collectionBatchBusy, setCollectionBatchBusy] = useState(false);
  const collectionSeqRef = useRef(0);

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

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

  const syncMovieStatus = useCallback((movieId: number, status: SeerrStatus) => {
    setResults((prev) =>
      prev ? prev.map((x) => (x.id === movieId && x.mediaType === 'movie' ? { ...x, status } : x)) : prev,
    );
    setMovieDetail((prev) => (prev && prev.id === movieId && prev.mediaType === 'movie' ? { ...prev, status } : prev));
    setCollection((prev) =>
      prev
        ? {
            ...prev,
            parts: prev.parts.map((p) => (p.id === movieId ? { ...p, status } : p)),
          }
        : prev,
    );
  }, []);

  const runSearch = useCallback(
    async (text?: string) => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
      const q = (text ?? query).trim();
      if (!q) {
        searchSeqRef.current += 1;
        setSearching(false);
        setResults(null);
        return;
      }
      const seq = ++searchSeqRef.current;
      setSearching(true);
      setErr(null);
      try {
        const res = await searchSeerr(q);
        if (seq !== searchSeqRef.current) return;
        setResults(res.results);
      } catch (e) {
        if (seq !== searchSeqRef.current) return;
        setErr((e as Error).message);
      } finally {
        if (seq === searchSeqRef.current) {
          setSearching(false);
        }
      }
    },
    [query],
  );

  const handleQueryChange = (v: string) => {
    setQuery(v);
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
    const trimmed = v.trim();
    if (!trimmed) {
      searchSeqRef.current += 1;
      setSearching(false);
      setResults(null);
      return;
    }
    if (trimmed.length >= MIN_AUTO_SEARCH_LEN) {
      debounceRef.current = setTimeout(() => {
        debounceRef.current = null;
        void runSearch(trimmed);
      }, SEARCH_DEBOUNCE_MS);
    }
  };

  const clearSearch = () => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
    searchSeqRef.current += 1;
    setQuery('');
    setSearching(false);
    setResults(null);
  };

  const openTvSeasons = async (item: SeerrItem) => {
    setTvItem(item);
    setTvLoading(true);
    setTvSelected([]);
    try {
      const det = await getSeerrDetails('tv', item.id);
      setTvItem(det);
      const pickable = (det.seasons ?? []).filter((s) => isRequestableSeason(s.status)).map((s) => s.seasonNumber);
      setTvSelected(pickable);
    } catch (e) {
      setTvItem(null);
      setErr((e as Error).message);
    } finally {
      setTvLoading(false);
    }
  };

  const openMovieDetail = async (seed: SeerrItem) => {
    const seq = ++movieSeqRef.current;
    setMovieDetail(seed);
    setMovieLoading(true);
    try {
      const det = await getSeerrDetails('movie', seed.id);
      if (seq !== movieSeqRef.current) return;
      setMovieDetail(det);
    } catch (e) {
      if (seq !== movieSeqRef.current) return;
      setErr((e as Error).message);
    } finally {
      if (seq === movieSeqRef.current) {
        setMovieLoading(false);
      }
    }
  };

  const openCollection = async (collectionId: number, seed?: { title?: string; poster?: string | null }) => {
    const seq = ++collectionSeqRef.current;
    setMovieDetail(null);
    setCollection(null);
    setCollectionErr(null);
    setCollectionSeed({ title: seed?.title ?? '', poster: seed?.poster ?? null });
    setCollectionLoading(true);
    try {
      const col = await getSeerrCollection(collectionId);
      if (seq !== collectionSeqRef.current) return;
      setCollection(col);
    } catch (e) {
      if (seq !== collectionSeqRef.current) return;
      setCollectionErr((e as Error).message);
    } finally {
      if (seq === collectionSeqRef.current) {
        setCollectionLoading(false);
      }
    }
  };

  const requestMovie = async (movieId: number) => {
    const key = `movie:${movieId}`;
    setBusyId(key);
    setErr(null);
    try {
      await createSeerrRequest('movie', movieId);
      syncMovieStatus(movieId, 'requested');
      void loadRequests();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  const requestMovieFromDetail = async () => {
    if (!movieDetail || movieSubmitting) return;
    setMovieSubmitting(true);
    setErr(null);
    try {
      await createSeerrRequest('movie', movieDetail.id);
      syncMovieStatus(movieDetail.id, 'requested');
      void loadRequests();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setMovieSubmitting(false);
    }
  };

  const requestAllMissingInCollection = async () => {
    if (!collection || collectionBatchBusy) return;
    const missing = collection.parts.filter((p) => isRequestableMovie(p.status));
    if (!missing.length) return;
    setCollectionBatchBusy(true);
    setCollectionErr(null);
    const failed: string[] = [];
    for (const m of missing) {
      try {
        await createSeerrRequest('movie', m.id);
        syncMovieStatus(m.id, 'requested');
      } catch (e) {
        failed.push(`${m.title}: ${(e as Error).message}`);
      }
    }
    try {
      const refreshed = await getSeerrCollection(collection.id);
      setCollection(refreshed);
      for (const p of refreshed.parts) {
        syncMovieStatus(p.id, p.status);
      }
    } catch {
      // Keep optimistic updates if refresh fails
    }
    void loadRequests();
    if (failed.length) {
      setCollectionErr(failed.join('\n'));
    }
    setCollectionBatchBusy(false);
  };

  const requestItem = async (item: SeerrItem) => {
    if (item.mediaType === 'collection') {
      await openCollection(item.id, { title: item.title, poster: item.poster });
      return;
    }
    if (item.mediaType === 'tv') {
      await openTvSeasons(item);
      return;
    }
    await requestMovie(item.id);
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
    const pickable = (tvItem?.seasons ?? []).filter((s) => isRequestableSeason(s.status)).map((s) => s.seasonNumber);
    const allPicked = pickable.every((sn) => tvSelected.includes(sn));
    setTvSelected(allPicked ? [] : pickable);
  };

  const closeCollection = () => {
    if (collectionBatchBusy) return;
    collectionSeqRef.current += 1;
    setCollection(null);
    setCollectionLoading(false);
    setCollectionErr(null);
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
          onChangeText={handleQueryChange}
          onSubmitEditing={() => void runSearch()}
          returnKeyType="search"
          placeholder="Search movies, TV, or collections"
          placeholderTextColor={t.textFaint}
          selectionColor={t.accent}
          style={[st.searchInput, { color: t.text }]}
        />
        {searching ? (
          <ActivityIndicator size="small" color={t.accent} />
        ) : query ? (
          <Pressable onPress={clearSearch} hitSlop={8} accessibilityLabel="Clear search">
            <Icon name="close-circle" size={18} color={t.textFaint} />
          </Pressable>
        ) : null}
      </View>

      {results !== null ? (
        <Section label={`Results (${results.length})`}>
          {results.length ? (
            <View style={{ gap: space(2.5) }}>
              {results.map((item) => {
                const isCollection = item.mediaType === 'collection';
                const badge = isCollection ? null : statusMeta(item.status);
                const canRequest =
                  item.mediaType === 'movie'
                    ? isRequestableMovie(item.status)
                    : item.mediaType === 'tv'
                      ? item.status === 'none' || item.status === 'partial' || item.status === 'deleted'
                      : false;
                const key = `${item.mediaType}:${item.id}`;
                return (
                  <Card
                    key={key}
                    onPress={() => {
                      if (isCollection) {
                        void openCollection(item.id, { title: item.title, poster: item.poster });
                      } else if (item.mediaType === 'movie') {
                        void openMovieDetail(item);
                      } else {
                        void openTvSeasons(item);
                      }
                    }}
                    style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}
                  >
                    <Poster uri={item.poster} kind={item.mediaType} />
                    <View style={{ flex: 1, gap: 4 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        {item.title}
                      </Text>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5), flexWrap: 'wrap' }}>
                        {isCollection ? (
                          <Pill label="Collection" tone="accent" />
                        ) : (
                          <Pill label={item.mediaType === 'tv' ? 'TV' : 'Movie'} tone="faint" />
                        )}
                        {item.year ? (
                          <Text style={[st.num, { color: t.textFaint, fontSize: 12 }]}>{item.year}</Text>
                        ) : null}
                        {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                      </View>
                    </View>
                    {isCollection ? (
                      <Text style={{ color: t.textFaint, fontSize: 20, fontWeight: '400' }}>›</Text>
                    ) : canRequest ? (
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
                  <Card
                    key={r.id}
                    onPress={() => {
                      const seed: SeerrItem = {
                        id: r.tmdbId,
                        mediaType: r.mediaType,
                        title: r.title,
                        year: r.year,
                        poster: r.poster,
                        overview: '',
                        status: r.status,
                      };
                      if (r.mediaType === 'movie') {
                        void openMovieDetail(seed);
                      } else {
                        void openTvSeasons(seed);
                      }
                    }}
                    style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}
                  >
                    <Poster uri={r.poster} kind={r.mediaType} />
                    <View style={{ flex: 1, gap: 4 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        {r.title}
                      </Text>
                      <Text style={[st.num, { color: t.textFaint, fontSize: 12 }]} numberOfLines={1}>
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
              <Empty icon="movie-search" title="No requests yet" />
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

      <MovieDetailSheet
        item={movieDetail}
        loading={movieLoading}
        submitting={movieSubmitting}
        onRequest={() => void requestMovieFromDetail()}
        onOpenCollection={(col) => void openCollection(col.id, { title: col.name, poster: col.poster })}
        onClose={() => {
          if (movieSubmitting) return;
          movieSeqRef.current += 1;
          setMovieDetail(null);
          setMovieLoading(false);
        }}
      />

      <CollectionSheet
        collection={collection}
        seedTitle={collectionSeed.title}
        seedPoster={collectionSeed.poster}
        loading={collectionLoading}
        error={collectionErr}
        busyId={busyId}
        batchSubmitting={collectionBatchBusy}
        onRequestMovie={(m) => void requestMovie(m.id)}
        onRequestAllMissing={() => void requestAllMissingInCollection()}
        onSelectMovie={(m) => {
          closeCollection();
          void openMovieDetail(m);
        }}
        onClose={closeCollection}
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
  posterMd: { width: 56, height: 82, borderRadius: 10 },
  skel: { borderRadius: 4 },
  num: { fontVariant: ['tabular-nums'] },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '78%', borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg, paddingTop: space(2) },
  collectionSheet: { maxHeight: '85%' },
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
