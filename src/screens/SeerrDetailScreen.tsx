import { useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { useCallback, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Linking,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  CollectionSheet,
  formatCompactCount,
  formatDate,
  formatMoney,
  formatRuntime,
  isRequestableMovie,
  isRequestableSeason,
  isoAge,
  PersonAvatar,
  Poster,
  statusMeta,
} from '../components/SeerrShared';
import { Banner, Button, Card, Icon, KV, List, ListRow, Pill, Section } from '../components/ui';
import {
  createSeerrRequest,
  getSeerrCollection,
  getSeerrDetails,
  SeerrCastMember,
  SeerrCollection,
  SeerrCollectionRef,
  SeerrCrewMember,
  SeerrItem,
  SeerrRatings,
  SeerrStatus,
} from '../lib/api';
import { radius, screenPad, space, useTheme } from '../theme';

export type SeerrDetailParams = {
  mediaType: 'movie' | 'tv';
  id: number;
  seed?: Partial<SeerrItem>;
};

function RatingsStrip({ ratings }: { ratings?: SeerrRatings }) {
  const t = useTheme();
  if (!ratings) return null;
  const cells: {
    key: string;
    label: string;
    value: string;
    sub?: string | null;
    fg: string;
    bg: string;
    icon: string;
  }[] = [];

  if (ratings.tmdb && ratings.tmdb.score > 0) {
    cells.push({
      key: 'tmdb',
      label: 'TMDB',
      value: ratings.tmdb.score.toFixed(1),
      sub: ratings.tmdb.count ? formatCompactCount(ratings.tmdb.count) : '/ 10',
      fg: t.text,
      bg: t.cardAlt,
      icon: 'star',
    });
  }
  if (ratings.imdb && ratings.imdb.score > 0) {
    cells.push({
      key: 'imdb',
      label: 'IMDb',
      value: ratings.imdb.score.toFixed(1),
      sub: ratings.imdb.count ? formatCompactCount(ratings.imdb.count) : '/ 10',
      fg: t.warn,
      bg: t.warnSoft,
      icon: 'star',
    });
  }
  if (ratings.rt?.criticsScore != null) {
    const fresh = ratings.rt.criticsScore >= 60;
    cells.push({
      key: 'rt-critics',
      label: 'RT Critics',
      value: `${ratings.rt.criticsScore}%`,
      sub: ratings.rt.criticsRating || (fresh ? 'Fresh' : 'Rotten'),
      fg: fresh ? t.ok : t.bad,
      bg: fresh ? t.okSoft : t.badSoft,
      icon: fresh ? 'check-decagram' : 'alert-decagram',
    });
  }
  if (ratings.rt?.audienceScore != null) {
    const good = ratings.rt.audienceScore >= 60;
    cells.push({
      key: 'rt-audience',
      label: 'RT Audience',
      value: `${ratings.rt.audienceScore}%`,
      sub: ratings.rt.audienceRating || null,
      fg: good ? t.ok : t.bad,
      bg: good ? t.okSoft : t.badSoft,
      icon: 'account-group',
    });
  }

  if (!cells.length) return null;

  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={{ gap: space(2), paddingRight: space(1) }}
    >
      {cells.map((c) => (
        <View key={c.key} style={[st.ratingCard, { backgroundColor: t.card }]}>
          <View style={st.ratingTop}>
            <View style={[st.ratingBadge, { backgroundColor: c.bg }]}>
              <Icon name={c.icon} size={13} color={c.fg} />
              <Text style={[st.num, st.ratingVal, { color: c.fg }]}>{c.value}</Text>
            </View>
          </View>
          <Text style={[st.ratingLabel, { color: t.textDim }]} numberOfLines={1}>
            {c.label}
          </Text>
          {c.sub ? (
            <Text style={[st.num, st.ratingSub, { color: t.textFaint }]} numberOfLines={1}>
              {c.sub}
            </Text>
          ) : null}
        </View>
      ))}
    </ScrollView>
  );
}

function CastCard({ member, onPress }: { member: SeerrCastMember; onPress: () => void }) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [st.castCard, { backgroundColor: t.card }, pressed && { opacity: 0.88 }]}
    >
      {member.profilePath ? (
        <Image source={{ uri: member.profilePath }} style={[st.castImg, { backgroundColor: t.cardAlt }]} resizeMode="cover" />
      ) : (
        <View style={[st.castImg, { backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }]}>
          <Icon name="account" size={32} color={t.textFaint} />
        </View>
      )}
      <View style={st.castBody}>
        <Text style={[st.castName, { color: t.text }]} numberOfLines={2}>
          {member.name}
        </Text>
        {member.character ? (
          <Text style={[st.castRole, { color: t.textFaint }]} numberOfLines={2}>
            {member.character}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}

function RecCard({ item, onPress }: { item: SeerrItem; onPress: () => void }) {
  const t = useTheme();
  const badge = statusMeta(item.status);
  return (
    <Pressable
      onPress={onPress}
      android_ripple={{ color: t.ripple, foreground: true }}
      style={({ pressed }) => [st.recCard, { backgroundColor: t.card }, pressed && { opacity: 0.88 }]}
    >
      <View style={st.recImgWrap}>
        {item.poster ? (
          <Image source={{ uri: item.poster }} style={[st.recImg, { backgroundColor: t.cardAlt }]} resizeMode="cover" />
        ) : (
          <View style={[st.recImg, { backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }]}>
            <Icon name={item.mediaType === 'tv' ? 'television' : 'movie'} size={26} color={t.textFaint} />
          </View>
        )}
        {badge ? (
          <View style={[st.recStatusDot, { backgroundColor: t.mediaChip, borderColor: t.mediaChipBorder }]}>
            <Text style={[st.recStatusText, { color: t.onMedia }]} numberOfLines={1}>
              {badge.label}
            </Text>
          </View>
        ) : null}
      </View>
      <View style={st.recBody}>
        <Text style={[st.recTitle, { color: t.text }]} numberOfLines={2}>
          {item.title}
        </Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}>
          {item.year ? (
            <Text style={[st.num, { color: t.textFaint, fontSize: 11.5 }]}>{item.year}</Text>
          ) : null}
          {item.voteAverage ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
              <Icon name="star" size={11} color={t.warn} />
              <Text style={[st.num, { color: t.textDim, fontSize: 11.5 }]}>{item.voteAverage.toFixed(1)}</Text>
            </View>
          ) : null}
        </View>
      </View>
    </Pressable>
  );
}

export default function SeerrDetailScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const nav = useNavigation<any>();
  const route = useRoute<any>();
  const params = (route.params ?? {}) as SeerrDetailParams;
  const mediaType: 'movie' | 'tv' = params.mediaType === 'tv' ? 'tv' : 'movie';
  const id = Number(params.id || 0);

  const [item, setItem] = useState<SeerrItem | null>(() =>
    params.seed
      ? {
          id,
          mediaType,
          title: params.seed.title ?? '',
          year: params.seed.year ?? null,
          poster: params.seed.poster ?? null,
          overview: params.seed.overview ?? '',
          status: params.seed.status ?? 'none',
          backdrop: params.seed.backdrop ?? null,
          voteAverage: params.seed.voteAverage ?? null,
        }
      : null,
  );
  const [loading, setLoading] = useState(id > 0);
  const [refreshing, setRefreshing] = useState(false);
  const [err, setErr] = useState<string | null>(id > 0 ? null : 'Missing media ID');
  const [overviewExpanded, setOverviewExpanded] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [selectedSeasons, setSelectedSeasons] = useState<number[]>([]);

  // Collection sheet state
  const [collection, setCollection] = useState<SeerrCollection | null>(null);
  const [collectionSeed, setCollectionSeed] = useState<{ title: string; poster: string | null }>({
    title: '',
    poster: null,
  });
  const [collectionLoading, setCollectionLoading] = useState(false);
  const [collectionErr, setCollectionErr] = useState<string | null>(null);
  const [collectionBusyId, setCollectionBusyId] = useState<string | null>(null);
  const [collectionBatchBusy, setCollectionBatchBusy] = useState(false);
  const collectionSeqRef = useRef(0);

  const load = useCallback(async () => {
    if (!id) return;
    try {
      const det = await getSeerrDetails(mediaType, id);
      setItem(det);
      setErr(null);
      if (det.mediaType === 'tv') {
        const pickable = (det.seasons ?? [])
          .filter((s) => isRequestableSeason(s.status))
          .map((s) => s.seasonNumber);
        setSelectedSeasons(pickable);
      }
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [mediaType, id]);

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load]),
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const requestMovie = async () => {
    if (!item || submitting) return;
    setSubmitting(true);
    setErr(null);
    try {
      await createSeerrRequest('movie', item.id);
      setItem((prev) => (prev ? { ...prev, status: 'requested' } : prev));
      void load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const requestSeasons = async () => {
    if (!item || submitting || selectedSeasons.length === 0) return;
    setSubmitting(true);
    setErr(null);
    try {
      await createSeerrRequest('tv', item.id, selectedSeasons);
      setItem((prev) => {
        if (!prev) return prev;
        const picked = new Set(selectedSeasons);
        return {
          ...prev,
          status: prev.status === 'none' || prev.status === 'deleted' ? 'requested' : prev.status,
          seasons: (prev.seasons ?? []).map((s) =>
            picked.has(s.seasonNumber) ? { ...s, status: 'requested' as SeerrStatus } : s,
          ),
        };
      });
      setSelectedSeasons([]);
      void load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const toggleSeason = (sn: number) => {
    setSelectedSeasons((prev) =>
      prev.includes(sn) ? prev.filter((x) => x !== sn) : [...prev, sn].sort((a, b) => a - b),
    );
  };

  const pickableSeasons = useMemo(
    () => (item?.seasons ?? []).filter((s) => isRequestableSeason(s.status)),
    [item?.seasons],
  );
  const allPicked =
    pickableSeasons.length > 0 && pickableSeasons.every((s) => selectedSeasons.includes(s.seasonNumber));

  const toggleAllSeasons = () => {
    setSelectedSeasons(allPicked ? [] : pickableSeasons.map((s) => s.seasonNumber));
  };

  const openPerson = (person: { id: number; name: string; profilePath: string | null }) => {
    nav.push('SeerrPerson', {
      id: person.id,
      seed: { name: person.name, profilePath: person.profilePath },
    });
  };

  const openMedia = (target: SeerrItem) => {
    if (target.mediaType === 'collection') return;
    nav.push('SeerrDetail', {
      mediaType: target.mediaType,
      id: target.id,
      seed: target,
    });
  };

  const openCollection = async (col: SeerrCollectionRef) => {
    const seq = ++collectionSeqRef.current;
    setCollection(null);
    setCollectionErr(null);
    setCollectionSeed({ title: col.name, poster: col.poster });
    setCollectionLoading(true);
    try {
      const data = await getSeerrCollection(col.id);
      if (seq !== collectionSeqRef.current) return;
      setCollection(data);
    } catch (e) {
      if (seq !== collectionSeqRef.current) return;
      setCollectionErr((e as Error).message);
    } finally {
      if (seq === collectionSeqRef.current) setCollectionLoading(false);
    }
  };

  const closeCollection = () => {
    if (collectionBatchBusy) return;
    collectionSeqRef.current += 1;
    setCollection(null);
    setCollectionLoading(false);
    setCollectionErr(null);
  };

  const requestCollectionMovie = async (movie: SeerrItem) => {
    const key = `movie:${movie.id}`;
    setCollectionBusyId(key);
    setCollectionErr(null);
    try {
      await createSeerrRequest('movie', movie.id);
      setCollection((prev) =>
        prev
          ? {
              ...prev,
              parts: prev.parts.map((p) => (p.id === movie.id ? { ...p, status: 'requested' } : p)),
            }
          : prev,
      );
      if (movie.id === id && mediaType === 'movie') {
        setItem((prev) => (prev ? { ...prev, status: 'requested' } : prev));
      }
    } catch (e) {
      setCollectionErr((e as Error).message);
    } finally {
      setCollectionBusyId(null);
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
        setCollection((prev) =>
          prev
            ? {
                ...prev,
                parts: prev.parts.map((p) => (p.id === m.id ? { ...p, status: 'requested' } : p)),
              }
            : prev,
        );
        if (m.id === id && mediaType === 'movie') {
          setItem((prev) => (prev ? { ...prev, status: 'requested' } : prev));
        }
      } catch (e) {
        failed.push(`${m.title}: ${(e as Error).message}`);
      }
    }
    try {
      const refreshed = await getSeerrCollection(collection.id);
      setCollection(refreshed);
    } catch {
      // keep optimistic updates
    }
    if (failed.length) setCollectionErr(failed.join('\n'));
    setCollectionBatchBusy(false);
  };

  const badge = item ? statusMeta(item.status) : null;
  const runtimeStr = formatRuntime(item?.runtime);
  const metaBits: string[] = [];
  if (item?.year) metaBits.push(String(item.year));
  if (runtimeStr) metaBits.push(mediaType === 'tv' ? `${runtimeStr} / ep` : runtimeStr);
  if (item?.certification) metaBits.push(item.certification);
  if (mediaType === 'tv' && item?.numberOfSeasons) {
    metaBits.push(`${item.numberOfSeasons} ${item.numberOfSeasons === 1 ? 'season' : 'seasons'}`);
  }

  const showOriginalTitle =
    item?.originalTitle && item.originalTitle.toLowerCase() !== item.title.toLowerCase();
  const canRequestMovie = item?.mediaType === 'movie' && isRequestableMovie(item.status);
  const hasBackdrop = !!item?.backdrop;
  const overviewText = item?.overview?.trim() ?? '';
  const overviewLong = overviewText.length > 260;

  const kvRows = useMemo(() => {
    if (!item) return [];
    const rows: { k: string; v: string; url?: string }[] = [];
    if (item.mediaStatus) rows.push({ k: 'Status', v: item.mediaStatus });
    const rel = formatDate(item.releaseDate);
    if (rel) rows.push({ k: mediaType === 'tv' ? 'First aired' : 'Release date', v: rel });
    const lastAir = mediaType === 'tv' ? formatDate(item.lastAirDate) : null;
    if (lastAir) rows.push({ k: 'Last aired', v: lastAir });
    if (runtimeStr) rows.push({ k: mediaType === 'tv' ? 'Episode runtime' : 'Runtime', v: runtimeStr });
    if (mediaType === 'tv' && (item.numberOfSeasons || item.numberOfEpisodes)) {
      const parts: string[] = [];
      if (item.numberOfSeasons) parts.push(`${item.numberOfSeasons} ${item.numberOfSeasons === 1 ? 'season' : 'seasons'}`);
      if (item.numberOfEpisodes) parts.push(`${item.numberOfEpisodes} ${item.numberOfEpisodes === 1 ? 'episode' : 'episodes'}`);
      rows.push({ k: 'Length', v: parts.join(' · ') });
    }
    if (mediaType === 'tv' && item.networks?.length) {
      rows.push({ k: 'Network', v: item.networks.join(', ') });
    } else if (item.studios?.length) {
      rows.push({ k: 'Studio', v: item.studios.join(', ') });
    }
    if (item.spokenLanguages?.length) {
      rows.push({ k: 'Languages', v: item.spokenLanguages.join(', ') });
    }
    if (item.countries?.length) {
      rows.push({ k: 'Country', v: item.countries.join(', ') });
    }
    const budget = formatMoney(item.budget);
    if (budget) rows.push({ k: 'Budget', v: budget });
    const revenue = formatMoney(item.revenue);
    if (revenue) rows.push({ k: 'Revenue', v: revenue });
    if (item.imdbId) {
      rows.push({ k: 'IMDb', v: item.imdbId, url: `https://www.imdb.com/title/${item.imdbId}/` });
    }
    return rows;
  }, [item, mediaType, runtimeStr]);

  return (
    <View style={{ flex: 1, backgroundColor: t.bg }}>
      <ScrollView
        style={{ flex: 1, backgroundColor: t.bg }}
        contentContainerStyle={{ paddingBottom: insets.bottom + space(10) }}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => void onRefresh()}
            tintColor={t.accent}
            colors={[t.accent]}
            progressBackgroundColor={t.card}
            progressViewOffset={insets.top}
          />
        }
      >
        {hasBackdrop ? (
          <View style={[st.backdropWrap, { backgroundColor: t.cardAlt }]}>
            <Image source={{ uri: item!.backdrop! }} style={st.backdropImg} resizeMode="cover" />
            <Pressable
              onPress={() => nav.goBack()}
              hitSlop={10}
              accessibilityLabel="Back"
              style={[
                st.floatingBack,
                { top: insets.top + space(3), backgroundColor: t.mediaChip, borderColor: t.mediaChipBorder },
              ]}
            >
              <Icon name="arrow-left" size={22} color={t.onMedia} />
            </Pressable>
          </View>
        ) : (
          <View style={[st.plainTopBar, { paddingTop: insets.top + space(3) }]}>
            <Pressable onPress={() => nav.goBack()} hitSlop={8} accessibilityLabel="Back" style={st.backBtn}>
              <Icon name="arrow-left" size={24} color={t.text} />
            </Pressable>
            <Text style={{ color: t.textDim, fontSize: 14, fontWeight: '500', flex: 1 }} numberOfLines={1}>
              {mediaType === 'tv' ? 'TV show' : 'Movie'}
            </Text>
          </View>
        )}

        <View style={st.content}>
          {err ? <Banner tone="bad" title="Seerr">{err}</Banner> : null}

          {/* Hero Poster + Title */}
          <View style={[st.heroRow, hasBackdrop && st.heroWithBackdrop]}>
            <View style={[st.posterFrame, hasBackdrop && { borderColor: t.bg, borderWidth: 3 }]}>
              <Poster uri={item?.poster ?? null} size="lg" kind={mediaType} />
            </View>
            <View style={{ flex: 1, gap: space(1.5), paddingTop: hasBackdrop ? space(5) : 0 }}>
              <Text style={[st.title, { color: t.text }]}>{item?.title || (loading ? 'Loading…' : 'Untitled')}</Text>
              {showOriginalTitle ? (
                <Text style={{ color: t.textFaint, fontSize: 12.5 }} numberOfLines={2}>
                  {item?.originalTitle}
                </Text>
              ) : null}
              {metaBits.length > 0 ? (
                <Text style={[st.num, { color: t.textDim, fontSize: 13 }]}>{metaBits.join(' · ')}</Text>
              ) : null}
              <View style={st.pillRow}>
                <Pill label={mediaType === 'tv' ? 'TV' : 'Movie'} tone="faint" />
                {badge ? <Pill label={badge.label} tone={badge.tone} dot /> : null}
              </View>
            </View>
          </View>

          {item?.tagline ? (
            <Text style={[st.tagline, { color: t.textDim }]}>“{item.tagline}”</Text>
          ) : null}

          {/* Ratings */}
          <RatingsStrip ratings={item?.ratings} />

          {/* Primary Actions for Movies */}
          {mediaType === 'movie' && item ? (
            <View style={{ gap: space(2) }}>
              {canRequestMovie ? (
                <Button
                  label={item.status === 'deleted' ? 'Request again' : 'Request movie'}
                  icon="plus"
                  busy={submitting}
                  disabled={loading || submitting}
                  onPress={() => void requestMovie()}
                />
              ) : null}
              {item.mediaUrl && item.status === 'available' ? (
                <Button
                  label="Open in Jellyfin"
                  icon="play"
                  kind="subtle"
                  onPress={() => void Linking.openURL(item.mediaUrl!)}
                />
              ) : null}
            </View>
          ) : null}

          {/* Existing requests note */}
          {item?.requests && item.requests.length > 0 ? (
            <Card style={{ paddingVertical: space(2.5), paddingHorizontal: space(3.5), gap: space(1) }}>
              {item.requests.slice(0, 3).map((req) => (
                <View key={req.id} style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                  <Icon name="clock-outline" size={15} color={t.textFaint} />
                  <Text style={[st.num, { color: t.textDim, fontSize: 12.5, flex: 1 }]} numberOfLines={1}>
                    Requested{req.requestedBy ? ` by ${req.requestedBy}` : ''}
                    {req.seasons.length ? ` · S${req.seasons.join(', S')}` : ''}
                    {req.createdAt ? ` · ${isoAge(req.createdAt)}` : ''}
                  </Text>
                </View>
              ))}
            </Card>
          ) : null}

          {/* Genres */}
          {item?.genres && item.genres.length > 0 ? (
            <View style={st.pillRow}>
              {item.genres.map((g) => (
                <Pill key={g} label={g} tone="dim" />
              ))}
            </View>
          ) : null}

          {/* Loading spinner when full details haven't arrived yet */}
          {loading && !item?.cast && !item?.ratings ? (
            <View style={{ paddingVertical: space(4), alignItems: 'center' }}>
              <ActivityIndicator size="small" color={t.accent} />
            </View>
          ) : null}

          {/* Overview */}
          {overviewText ? (
            <Section label="Overview">
              <Card style={{ gap: space(2) }}>
                <Text
                  style={[st.overview, { color: t.textDim }]}
                  numberOfLines={overviewExpanded ? undefined : 5}
                >
                  {overviewText}
                </Text>
                {overviewLong ? (
                  <Pressable onPress={() => setOverviewExpanded((v) => !v)} hitSlop={8}>
                    <Text style={{ color: t.accent, fontSize: 13, fontWeight: '500' }}>
                      {overviewExpanded ? 'Show less' : 'Read more'}
                    </Text>
                  </Pressable>
                ) : null}
              </Card>
            </Section>
          ) : null}

          {/* TV Seasons (inline, directly selectable) */}
          {mediaType === 'tv' && item?.seasons && item.seasons.length > 0 ? (
            <Section
              label={`Seasons (${item.seasons.length})`}
              right={
                pickableSeasons.length > 1 ? (
                  <Pressable onPress={toggleAllSeasons} hitSlop={8}>
                    <Text style={{ color: t.accent, fontSize: 13, fontWeight: '500' }}>
                      {allPicked ? 'Clear' : 'Select all'}
                    </Text>
                  </Pressable>
                ) : null
              }
            >
              <View style={{ gap: space(2.5) }}>
                <List>
                  {item.seasons.map((s, idx) => {
                    const locked = !isRequestableSeason(s.status);
                    const on = selectedSeasons.includes(s.seasonNumber);
                    const sBadge = statusMeta(s.status);
                    const subParts: string[] = [];
                    if (s.episodeCount) subParts.push(`${s.episodeCount} eps`);
                    if (s.airDate) {
                      const y = s.airDate.slice(0, 4);
                      if (/^\d{4}$/.test(y)) subParts.push(y);
                    }
                    return (
                      <ListRow
                        key={s.seasonNumber}
                        title={s.name}
                        subtitle={subParts.length ? subParts.join(' · ') : undefined}
                        last={idx === item.seasons!.length - 1}
                        chevron={false}
                        onPress={locked ? undefined : () => toggleSeason(s.seasonNumber)}
                        trailing={
                          locked && sBadge ? (
                            <Pill label={sBadge.label} tone={sBadge.tone} />
                          ) : (
                            <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
                              {sBadge ? <Pill label={sBadge.label} tone={sBadge.tone} /> : null}
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
                {pickableSeasons.length > 0 ? (
                  <Button
                    label={
                      selectedSeasons.length > 0
                        ? `Request ${selectedSeasons.length} ${selectedSeasons.length === 1 ? 'season' : 'seasons'}`
                        : 'Select seasons to request'
                    }
                    icon="plus"
                    busy={submitting}
                    disabled={submitting || selectedSeasons.length === 0}
                    onPress={() => void requestSeasons()}
                  />
                ) : item.mediaUrl && item.status === 'available' ? (
                  <Button
                    label="Open in Jellyfin"
                    icon="play"
                    kind="subtle"
                    onPress={() => void Linking.openURL(item.mediaUrl!)}
                  />
                ) : null}
              </View>
            </Section>
          ) : null}

          {/* Collection card (Movies) */}
          {item?.collection ? (
            <Section label="Collection">
              <Card
                onPress={() => void openCollection(item.collection!)}
                style={{ flexDirection: 'row', alignItems: 'center', gap: space(3), padding: space(3) }}
              >
                <Poster uri={item.collection.poster} size="sm" kind="collection" />
                <View style={{ flex: 1, gap: 2 }}>
                  <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }} numberOfLines={2}>
                    {item.collection.name}
                  </Text>
                  <Text style={{ color: t.textFaint, fontSize: 12.5 }}>View collection & request missing</Text>
                </View>
                <Text style={{ color: t.textFaint, fontSize: 20, fontWeight: '400' }}>›</Text>
              </Card>
            </Section>
          ) : null}

          {/* Cast */}
          {item?.cast && item.cast.length > 0 ? (
            <Section label="Cast">
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ gap: space(2.5), paddingRight: space(1) }}
              >
                {item.cast.map((member) => (
                  <CastCard key={member.id} member={member} onPress={() => openPerson(member)} />
                ))}
              </ScrollView>
            </Section>
          ) : null}

          {/* Key Crew */}
          {item?.crew && item.crew.length > 0 ? (
            <Section label="Crew">
              <List>
                {item.crew.map((c: SeerrCrewMember, idx) => (
                  <ListRow
                    key={`${c.id}-${c.job}`}
                    leading={<PersonAvatar uri={c.profilePath} size={38} />}
                    title={c.name}
                    subtitle={c.job}
                    last={idx === item.crew!.length - 1}
                    onPress={() => openPerson(c)}
                  />
                ))}
              </List>
            </Section>
          ) : null}

          {/* Metadata KV */}
          {kvRows.length > 0 ? (
            <Section label="Details">
              <Card>
                {kvRows.map((row) =>
                  row.url ? (
                    <KV
                      key={row.k}
                      k={row.k}
                      v={
                        <Pressable onPress={() => void Linking.openURL(row.url!)} hitSlop={6}>
                          <Text style={{ color: t.accent, fontSize: 13.5, fontFamily: t.mono }}>{row.v}</Text>
                        </Pressable>
                      }
                    />
                  ) : (
                    <KV key={row.k} k={row.k} v={row.v} />
                  ),
                )}
              </Card>
            </Section>
          ) : null}

          {/* Recommendations */}
          {item?.recommendations && item.recommendations.length > 0 ? (
            <Section label="Recommended">
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={{ gap: space(2.5), paddingRight: space(1) }}
              >
                {item.recommendations.map((rec) => (
                  <RecCard key={`${rec.mediaType}:${rec.id}`} item={rec} onPress={() => openMedia(rec)} />
                ))}
              </ScrollView>
            </Section>
          ) : null}
        </View>
      </ScrollView>

      <CollectionSheet
        collection={collection}
        seedTitle={collectionSeed.title}
        seedPoster={collectionSeed.poster}
        loading={collectionLoading}
        error={collectionErr}
        busyId={collectionBusyId}
        batchSubmitting={collectionBatchBusy}
        onRequestMovie={(m) => void requestCollectionMovie(m)}
        onRequestAllMissing={() => void requestAllMissingInCollection()}
        onSelectMovie={(m) => {
          closeCollection();
          if (m.id !== id || mediaType !== 'movie') {
            openMedia(m);
          }
        }}
        onClose={closeCollection}
      />
    </View>
  );
}

const st = StyleSheet.create({
  backdropWrap: {
    width: '100%',
    height: 210,
    position: 'relative',
  },
  backdropImg: {
    width: '100%',
    height: '100%',
  },
  floatingBack: {
    position: 'absolute',
    left: screenPad,
    width: 38,
    height: 38,
    borderRadius: 19,
    borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center',
    justifyContent: 'center',
  },
  plainTopBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: screenPad,
    paddingBottom: space(2),
  },
  backBtn: {
    width: 36,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  content: {
    paddingHorizontal: screenPad,
    gap: space(3),
  },
  heroRow: {
    flexDirection: 'row',
    gap: space(3.5),
    alignItems: 'flex-start',
  },
  heroWithBackdrop: {
    marginTop: -28,
  },
  posterFrame: {
    borderRadius: 14,
    overflow: 'hidden',
  },
  title: {
    fontSize: 22,
    fontWeight: '600',
    letterSpacing: -0.3,
    lineHeight: 27,
  },
  tagline: {
    fontSize: 13.5,
    lineHeight: 19,
  },
  pillRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: space(1.5),
  },
  overview: {
    fontSize: 14,
    lineHeight: 21,
  },
  num: {
    fontVariant: ['tabular-nums'],
  },
  ratingCard: {
    borderRadius: radius.md,
    paddingHorizontal: space(3),
    paddingVertical: space(2.5),
    minWidth: 96,
    gap: 3,
  },
  ratingTop: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 2,
  },
  ratingBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.pill,
  },
  ratingVal: {
    fontSize: 14,
    fontWeight: '600',
  },
  ratingLabel: {
    fontSize: 11.5,
    fontWeight: '500',
  },
  ratingSub: {
    fontSize: 11,
  },
  castCard: {
    width: 108,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  castImg: {
    width: 108,
    height: 132,
  },
  castBody: {
    padding: space(2),
    gap: 2,
  },
  castName: {
    fontSize: 13,
    fontWeight: '600',
    lineHeight: 17,
  },
  castRole: {
    fontSize: 11.5,
    lineHeight: 15,
  },
  recCard: {
    width: 114,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  recImgWrap: {
    position: 'relative',
  },
  recImg: {
    width: 114,
    height: 168,
  },
  recStatusDot: {
    position: 'absolute',
    bottom: 6,
    left: 6,
    right: 6,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
    alignSelf: 'flex-start',
  },
  recStatusText: {
    fontSize: 10.5,
    fontWeight: '500',
  },
  recBody: {
    padding: space(2),
    gap: 4,
  },
  recTitle: {
    fontSize: 12.5,
    fontWeight: '600',
    lineHeight: 16,
  },
});
