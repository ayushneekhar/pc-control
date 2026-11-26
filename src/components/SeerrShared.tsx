import { ActivityIndicator, Image, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Card, Icon, Pill, Tone } from './ui';
import { SeerrCollection, SeerrItem, SeerrStatus } from '../lib/api';
import { ago } from '../lib/format';
import {
  computeAge,
  formatCompactCount,
  formatDate,
  formatMoney,
  formatRuntime,
  isRequestableMovie,
  isRequestableSeason,
} from '../lib/seerrHelpers';
import { radius, screenPad, space, useTheme } from '../theme';

export {
  computeAge,
  formatCompactCount,
  formatDate,
  formatMoney,
  formatRuntime,
  isRequestableMovie,
  isRequestableSeason,
};

export function statusMeta(st: SeerrStatus): { label: string; tone: Tone } | null {
  switch (st) {
    case 'available':
      return { label: 'Available', tone: 'ok' };
    case 'grabbing':
      return { label: 'Grabbing', tone: 'accent' };
    case 'partial':
      return { label: 'Partial', tone: 'warn' };
    case 'pending':
      return { label: 'Pending', tone: 'warn' };
    case 'requested':
      return { label: 'Processing', tone: 'dim' };
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

export function isoAge(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  return ago(ms / 1000);
}

export function Poster({
  uri,
  size = 'sm',
  kind = 'movie',
}: {
  uri: string | null;
  size?: 'sm' | 'md' | 'lg';
  kind?: 'movie' | 'tv' | 'collection';
}) {
  const t = useTheme();
  const dim = size === 'lg' ? st.posterLg : size === 'md' ? st.posterMd : st.posterSm;
  if (!uri) {
    const iconName = kind === 'collection' ? 'layers' : kind === 'tv' ? 'television' : 'movie';
    return (
      <View style={[dim, { backgroundColor: t.cardAlt, alignItems: 'center', justifyContent: 'center' }]}>
        <Icon name={iconName} size={size === 'lg' ? 30 : size === 'md' ? 24 : 20} color={t.textFaint} />
      </View>
    );
  }
  return <Image source={{ uri }} style={[dim, { backgroundColor: t.cardAlt }]} resizeMode="cover" />;
}

export function PersonAvatar({
  uri,
  size = 40,
}: {
  uri: string | null;
  size?: number;
}) {
  const t = useTheme();
  const box = { width: size, height: size, borderRadius: size / 2, backgroundColor: t.cardAlt };
  if (!uri) {
    return (
      <View style={[box, { alignItems: 'center', justifyContent: 'center' }]}>
        <Icon name="account" size={Math.round(size * 0.52)} color={t.textFaint} />
      </View>
    );
  }
  return <Image source={{ uri }} style={box} resizeMode="cover" />;
}

export function CollectionSheet({
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
        <View style={[st.sheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4) }]}>
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
                    ) : (
                      <Text style={{ color: t.textFaint, fontSize: 20, fontWeight: '400' }}>›</Text>
                    )}
                  </Card>
                );
              })}
            </ScrollView>
          )}

          <View style={{ flexDirection: 'row', gap: space(2), paddingHorizontal: screenPad, paddingTop: space(1) }}>
            <Button
              label="Close"
              kind="subtle"
              style={{ flex: missingParts.length > 0 ? 0.55 : 1 }}
              disabled={batchSubmitting}
              onPress={onClose}
            />
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

const st = StyleSheet.create({
  posterSm: { width: 44, height: 64, borderRadius: 8 },
  posterMd: { width: 56, height: 82, borderRadius: 10 },
  posterLg: { width: 96, height: 144, borderRadius: 12 },
  num: { fontVariant: ['tabular-nums'] },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '85%', borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg, paddingTop: space(2) },
  grabber: { width: 38, height: 4, borderRadius: 2, alignSelf: 'center' },
});
