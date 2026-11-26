import { useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { useCallback, useMemo, useState } from 'react';
import { ActivityIndicator, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { computeAge, formatDate, Poster, statusMeta } from '../components/SeerrShared';
import { Banner, Card, Empty, Icon, Pill, Screen, Section, Segmented } from '../components/ui';
import { getSeerrPerson, SeerrPerson, SeerrPersonCredit } from '../lib/api';
import { space, useTheme } from '../theme';

export type SeerrPersonParams = {
  id: number;
  seed?: {
    name?: string;
    profilePath?: string | null;
    knownForDepartment?: string | null;
  };
};

type MediaFilter = 'all' | 'movie' | 'tv';
type SortMode = 'popular' | 'newest';

export default function SeerrPersonScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const route = useRoute<any>();
  const params = (route.params ?? {}) as SeerrPersonParams;
  const id = Number(params.id || 0);

  const [person, setPerson] = useState<SeerrPerson | null>(null);
  const [loading, setLoading] = useState(id > 0);
  const [refreshing, setRefreshing] = useState(false);
  const [err, setErr] = useState<string | null>(id > 0 ? null : 'Missing person ID');
  const [bioExpanded, setBioExpanded] = useState(false);
  const [filter, setFilter] = useState<MediaFilter>('all');
  const [sortMode, setSortMode] = useState<SortMode>('popular');

  const load = useCallback(async () => {
    if (!id) return;
    try {
      const data = await getSeerrPerson(id);
      setPerson(data);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [id]);

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

  const displayName = person?.name || params.seed?.name || (loading ? 'Loading…' : 'Person');
  const displayProfile = person?.profilePath ?? params.seed?.profilePath ?? null;
  const displayDept = person?.knownForDepartment ?? params.seed?.knownForDepartment ?? null;

  const credits = useMemo(() => person?.credits ?? [], [person?.credits]);
  const movieCount = useMemo(() => credits.filter((c) => c.mediaType === 'movie').length, [credits]);
  const tvCount = useMemo(() => credits.filter((c) => c.mediaType === 'tv').length, [credits]);

  const filteredCredits = useMemo(() => {
    const base = filter === 'all' ? credits : credits.filter((c) => c.mediaType === filter);
    if (sortMode === 'popular') return base;
    return [...base].sort((a, b) => {
      const da = a.releaseDate || '';
      const db = b.releaseDate || '';
      if (da !== db) return db.localeCompare(da);
      return b.popularity - a.popularity;
    });
  }, [credits, filter, sortMode]);

  const openCredit = (c: SeerrPersonCredit) => {
    nav.push('SeerrDetail', {
      mediaType: c.mediaType,
      id: c.id,
      seed: {
        id: c.id,
        mediaType: c.mediaType,
        title: c.title,
        year: c.year,
        poster: c.poster,
        overview: '',
        status: c.status,
        voteAverage: c.voteAverage,
      },
    });
  };

  const age = computeAge(person?.birthday, person?.deathday);
  const birthStr = formatDate(person?.birthday);
  const deathStr = formatDate(person?.deathday);
  const bio = person?.biography?.trim() ?? '';
  const bioLong = bio.length > 260;

  return (
    <Screen
      title={displayName}
      subtitle={displayDept || undefined}
      onBack={() => nav.goBack()}
      refreshing={refreshing}
      onRefresh={() => void onRefresh()}
    >
      {err ? <Banner tone="bad" title="Seerr">{err}</Banner> : null}

      {/* Profile Summary Card */}
      <Card style={{ flexDirection: 'row', gap: space(3.5), alignItems: 'flex-start' }}>
        {displayProfile ? (
          <Image
            source={{ uri: displayProfile }}
            style={[st.portrait, { backgroundColor: t.cardAlt }]}
            resizeMode="cover"
          />
        ) : (
          <View style={[st.portrait, { backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }]}>
            <Icon name="account" size={38} color={t.textFaint} />
          </View>
        )}
        <View style={{ flex: 1, gap: space(1.5) }}>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space(1.5), alignItems: 'center' }}>
            {displayDept ? <Pill label={displayDept} tone="accent" /> : null}
            {credits.length > 0 ? (
              <Text style={[st.num, { color: t.textFaint, fontSize: 12.5 }]}>
                {credits.length} {credits.length === 1 ? 'credit' : 'credits'}
              </Text>
            ) : null}
          </View>

          {birthStr ? (
            <View style={st.metaRow}>
              <Icon name="cake-variant" size={14} color={t.textFaint} />
              <Text style={[st.num, { color: t.textDim, fontSize: 13, flex: 1 }]}>
                {birthStr}
                {age != null && !deathStr ? ` (${age})` : ''}
              </Text>
            </View>
          ) : null}

          {deathStr ? (
            <View style={st.metaRow}>
              <Icon name="calendar" size={14} color={t.textFaint} />
              <Text style={[st.num, { color: t.textDim, fontSize: 13, flex: 1 }]}>
                Died {deathStr}
                {age != null ? ` (aged ${age})` : ''}
              </Text>
            </View>
          ) : null}

          {person?.placeOfBirth ? (
            <View style={st.metaRow}>
              <Icon name="map-marker" size={14} color={t.textFaint} />
              <Text style={{ color: t.textDim, fontSize: 13, flex: 1 }}>{person.placeOfBirth}</Text>
            </View>
          ) : null}
        </View>
      </Card>

      {loading && !person ? (
        <View style={{ paddingVertical: space(6), alignItems: 'center' }}>
          <ActivityIndicator size="small" color={t.accent} />
        </View>
      ) : null}

      {/* Biography */}
      {bio ? (
        <Section label="Biography">
          <Card style={{ gap: space(2) }}>
            <Text style={[st.bio, { color: t.textDim }]} numberOfLines={bioExpanded ? undefined : 5}>
              {bio}
            </Text>
            {bioLong ? (
              <Pressable onPress={() => setBioExpanded((v) => !v)} hitSlop={8}>
                <Text style={{ color: t.accent, fontSize: 13, fontWeight: '500' }}>
                  {bioExpanded ? 'Show less' : 'Read more'}
                </Text>
              </Pressable>
            ) : null}
          </Card>
        </Section>
      ) : null}

      {/* Filmography */}
      {person ? (
        <Section
          label={`Filmography (${filteredCredits.length})`}
          right={
            credits.length > 1 ? (
              <Pressable
                onPress={() => setSortMode((m) => (m === 'popular' ? 'newest' : 'popular'))}
                hitSlop={8}
                style={st.sortBtn}
              >
                <Icon name="sort" size={14} color={t.accent} />
                <Text style={{ color: t.accent, fontSize: 12.5, fontWeight: '500' }}>
                  {sortMode === 'popular' ? 'Popular' : 'Newest'}
                </Text>
              </Pressable>
            ) : null
          }
        >
          <Segmented<MediaFilter>
            value={filter}
            onChange={setFilter}
            options={[
              { id: 'all', label: `All (${credits.length})` },
              { id: 'movie', label: `Movies (${movieCount})` },
              { id: 'tv', label: `TV (${tvCount})` },
            ]}
          />

          {filteredCredits.length ? (
            <View style={{ gap: space(2) }}>
              {filteredCredits.map((c) => {
                const badge = statusMeta(c.status);
                return (
                  <Card
                    key={`${c.mediaType}:${c.id}`}
                    onPress={() => openCredit(c)}
                    style={{ flexDirection: 'row', gap: space(3), alignItems: 'center', padding: space(3) }}
                  >
                    <Poster uri={c.poster} size="sm" kind={c.mediaType} />
                    <View style={{ flex: 1, gap: 3 }}>
                      <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '600' }} numberOfLines={2}>
                        {c.title}
                      </Text>
                      {c.role ? (
                        <Text style={{ color: t.textDim, fontSize: 12.5 }} numberOfLines={1}>
                          {c.role}
                        </Text>
                      ) : null}
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5), flexWrap: 'wrap', marginTop: 1 }}>
                        <Pill label={c.mediaType === 'tv' ? 'TV' : 'Movie'} tone="faint" />
                        {c.year ? (
                          <Text style={[st.num, { color: t.textFaint, fontSize: 12 }]}>{c.year}</Text>
                        ) : null}
                        {c.voteAverage ? (
                          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
                            <Icon name="star" size={12} color={t.warn} />
                            <Text style={[st.num, { color: t.textDim, fontSize: 12 }]}>
                              {c.voteAverage.toFixed(1)}
                            </Text>
                          </View>
                        ) : null}
                        {badge ? <Pill label={badge.label} tone={badge.tone} /> : null}
                      </View>
                    </View>
                    <Text style={{ color: t.textFaint, fontSize: 20, fontWeight: '400' }}>›</Text>
                  </Card>
                );
              })}
            </View>
          ) : (
            <Card>
              <Empty icon="movie-open" title="No credits in this category" />
            </Card>
          )}
        </Section>
      ) : null}
    </Screen>
  );
}

const st = StyleSheet.create({
  portrait: {
    width: 96,
    height: 132,
    borderRadius: 12,
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: space(1.5),
  },
  bio: {
    fontSize: 14,
    lineHeight: 21,
  },
  sortBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  num: {
    fontVariant: ['tabular-nums'],
  },
});
