import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  CollectionSheet,
  isRequestableMovie,
  isoAge,
  Poster,
  statusMeta,
} from '../components/SeerrShared';
import { Banner, Button, Card, Empty, Icon, Pill, Screen, Section } from '../components/ui';
import {
  createSeerrRequest,
  getSeerrCollection,
  getSeerrRequests,
  searchSeerr,
  SeerrCollection,
  SeerrItem,
  SeerrRequestFilter,
  SeerrRequestItem,
  SeerrStatus,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { radius, space, useTheme } from '../theme';

const CACHE_REQUESTS = 'seerr:requests';
const CACHE_COUNTS = 'seerr:requestCounts';
const SEARCH_DEBOUNCE_MS = 350;
const MIN_AUTO_SEARCH_LEN = 3;

type FilterTab = { id: SeerrRequestFilter; label: string };

const BASE_FILTER_TABS: FilterTab[] = [
  { id: 'all', label: 'All' },
  { id: 'pending', label: 'Pending' },
  { id: 'processing', label: 'Processing' },
  { id: 'available', label: 'Available' },
  { id: 'partial', label: 'Partial' },
  { id: 'failed', label: 'Failed' },
  { id: 'deleted', label: 'Deleted' },
];

function RowSkeleton() {
  const t = useTheme();
  return (
    <Card style={{ flexDirection: 'row', gap: space(3), alignItems: 'center' }}>
      <View style={[st.posterSkel, { backgroundColor: t.cardAlt }]} />
      <View style={{ flex: 1, gap: 8 }}>
        <View style={[st.skel, { width: '70%', height: 15, backgroundColor: t.cardAlt }]} />
        <View style={[st.skel, { width: '40%', height: 12, backgroundColor: t.cardAlt }]} />
      </View>
      <View style={[st.skel, { width: 68, height: 26, borderRadius: radius.pill, backgroundColor: t.cardAlt }]} />
    </Card>
  );
}

export default function SeerrScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SeerrItem[] | null>(null);
  const [searching, setSearching] = useState(false);

  // Requests + status filter state
  const [statusFilter, setStatusFilter] = useState<SeerrRequestFilter>('all');
  const [requests, setRequests] = useState<SeerrRequestItem[] | null>(() => cacheGet<SeerrRequestItem[]>(CACHE_REQUESTS));
  const [counts, setCounts] = useState<Record<string, number> | null>(() => cacheGet<Record<string, number>>(CACHE_COUNTS));
  const [loadingFilter, setLoadingFilter] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  // Debounce & race-condition guards
  const searchSeqRef = useRef(0);
  const reqSeqRef = useRef(0);
  const statusFilterRef = useRef<SeerrRequestFilter>('all');
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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

  const loadRequests = useCallback(async (flt: SeerrRequestFilter, showSpinner = false) => {
    const seq = ++reqSeqRef.current;
    if (showSpinner) setLoadingFilter(true);
    try {
      const res = await getSeerrRequests(flt);
      if (seq !== reqSeqRef.current) return;
      if (flt === 'all') {
        cacheSet(CACHE_REQUESTS, res.requests);
        if (res.counts) {
          cacheSet(CACHE_COUNTS, res.counts);
          setCounts(res.counts);
        }
      }
      setRequests(res.requests);
      setErr(null);
    } catch (e) {
      if (seq !== reqSeqRef.current) return;
      setErr((e as Error).message);
    } finally {
      if (seq === reqSeqRef.current) setLoadingFilter(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadRequests(statusFilterRef.current, false);
    }, [loadRequests]),
  );

  const selectFilter = (next: SeerrRequestFilter) => {
    if (next === statusFilter) return;
    statusFilterRef.current = next;
    setStatusFilter(next);
    void loadRequests(next, true);
  };

  const filterTabs = useMemo(() => {
    const tabs = [...BASE_FILTER_TABS];
    if (counts?.declined && counts.declined > 0) {
      tabs.push({ id: 'declined', label: 'Declined' });
    }
    if (counts?.blocklisted && counts.blocklisted > 0) {
      tabs.push({ id: 'blocklisted', label: 'Blocklisted' });
    }
    return tabs;
  }, [counts]);

  const syncMovieStatus = useCallback((movieId: number, status: SeerrStatus) => {
    setResults((prev) =>
      prev ? prev.map((x) => (x.id === movieId && x.mediaType === 'movie' ? { ...x, status } : x)) : prev,
    );
    setCollection((prev) =>
      prev
        ? {
            ...prev,
            parts: prev.parts.map((p) => (p.id === movieId ? { ...p, status } : p)),
          }
        : prev,
    );
  }, []);

  const openDetail = useCallback(
    (mediaType: 'movie' | 'tv', id: number, seed?: Partial<SeerrItem>) => {
      nav.push('SeerrDetail', { mediaType, id, seed });
    },
    [nav],
  );

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

  const openCollection = async (collectionId: number, seed?: { title?: string; poster?: string | null }) => {
    const seq = ++collectionSeqRef.current;
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
      void loadRequests(statusFilter, false);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusyId(null);
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
    void loadRequests(statusFilter, false);
    if (failed.length) {
      setCollectionErr(failed.join('\n'));
    }
    setCollectionBatchBusy(false);
  };

  const closeCollection = () => {
    if (collectionBatchBusy) return;
    collectionSeqRef.current += 1;
    setCollection(null);
    setCollectionLoading(false);
    setCollectionErr(null);
  };

  const activeTabLabel = filterTabs.find((tab) => tab.id === statusFilter)?.label ?? 'All';

  return (
    <Screen
      title="Seerr"
      onBack={() => nav.goBack()}
      refreshing={refreshing}
      onRefresh={async () => {
        setRefreshing(true);
        await loadRequests(statusFilter, false);
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
                const canQuickRequestMovie = item.mediaType === 'movie' && isRequestableMovie(item.status);
                const key = `${item.mediaType}:${item.id}`;
                return (
                  <Card
                    key={key}
                    onPress={() => {
                      if (isCollection) {
                        void openCollection(item.id, { title: item.title, poster: item.poster });
                      } else {
                        openDetail(item.mediaType as 'movie' | 'tv', item.id, item);
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
                        {item.voteAverage ? (
                          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
                            <Icon name="star" size={12} color={t.warn} />
                            <Text style={[st.num, { color: t.textDim, fontSize: 12 }]}>
                              {item.voteAverage.toFixed(1)}
                            </Text>
                          </View>
                        ) : null}
                        {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                      </View>
                    </View>
                    {canQuickRequestMovie ? (
                      <Button
                        label="Request"
                        kind="primary"
                        compact
                        busy={busyId === key}
                        onPress={() => void requestMovie(item.id)}
                      />
                    ) : (
                      <Text style={{ color: t.textFaint, fontSize: 20, fontWeight: '400' }}>›</Text>
                    )}
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
        <View style={{ gap: space(2) }}>
          {/* Status filter chips */}
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={st.chipRow}
          >
            {filterTabs.map((tab) => {
              const selected = tab.id === statusFilter;
              const cnt = counts ? counts[tab.id] : undefined;
              return (
                <Pressable
                  key={tab.id}
                  onPress={() => selectFilter(tab.id)}
                  accessibilityRole="tab"
                  accessibilityState={{ selected }}
                  android_ripple={{ color: t.ripple, foreground: true }}
                  style={[
                    st.chip,
                    { backgroundColor: selected ? t.accentSoft : t.card },
                  ]}
                >
                  <Text
                    style={[
                      st.chipLabel,
                      { color: selected ? t.accent : t.textDim, fontWeight: selected ? '600' : '500' },
                    ]}
                    numberOfLines={1}
                  >
                    {tab.label}
                  </Text>
                  {typeof cnt === 'number' ? (
                    <Text
                      style={[
                        st.num,
                        st.chipCount,
                        { color: selected ? t.accent : t.textFaint },
                      ]}
                      numberOfLines={1}
                    >
                      {cnt}
                    </Text>
                  ) : null}
                </Pressable>
              );
            })}
          </ScrollView>

          <Section
            label={
              statusFilter === 'all'
                ? 'Requests'
                : `${activeTabLabel} requests`
            }
            right={
              loadingFilter ? (
                <ActivityIndicator size="small" color={t.accent} />
              ) : requests ? (
                <Text style={[st.num, { color: t.textFaint, fontSize: 12 }]}>{requests.length}</Text>
              ) : null
            }
          >
            {!requests ? (
              <View style={{ gap: space(2.5) }}>
                <RowSkeleton />
                <RowSkeleton />
                <RowSkeleton />
              </View>
            ) : requests.length ? (
              <View style={{ gap: space(2.5), opacity: loadingFilter ? 0.65 : 1 }}>
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
                      onPress={() =>
                        openDetail(r.mediaType, r.tmdbId, {
                          id: r.tmdbId,
                          mediaType: r.mediaType,
                          title: r.title,
                          year: r.year,
                          poster: r.poster,
                          overview: '',
                          status: r.status,
                        })
                      }
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
                <Empty
                  icon="movie-search"
                  title={statusFilter === 'all' ? 'No requests yet' : `No ${activeTabLabel.toLowerCase()} requests`}
                />
              </Card>
            )}
          </Section>
        </View>
      )}

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
          openDetail('movie', m.id, m);
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
  posterSkel: { width: 44, height: 64, borderRadius: 8 },
  skel: { borderRadius: 4 },
  num: { fontVariant: ['tabular-nums'] },
  chipRow: {
    flexDirection: 'row',
    gap: space(2),
    paddingVertical: 2,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 34,
    paddingHorizontal: 13,
    borderRadius: radius.pill,
    overflow: 'hidden',
  },
  chipLabel: {
    fontSize: 13,
  },
  chipCount: {
    fontSize: 12,
    fontWeight: '500',
  },
});
