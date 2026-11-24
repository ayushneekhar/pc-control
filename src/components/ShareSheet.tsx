import { useEffect, useState } from 'react';
import { ActivityIndicator, Image, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Button, Icon, List, ListRow, Pill } from './ui';
import { addMagnet, createSeerrRequest, resolveMediaUrl, SeerrItem } from '../lib/api';
import { SharedTarget } from '../lib/share';
import { radius, screenPad, space, useTheme } from '../theme';

export function ShareSheet({
  target,
  onClose,
  onDone,
}: {
  target: Exclude<SharedTarget, { kind: 'youtube' }> | null;
  onClose: () => void;
  onDone?: (kind: 'magnet' | 'media') => void;
}) {
  if (!target) return null;
  const key = target.kind === 'magnet' ? target.uri : target.url;
  return <ShareSheetInner key={key} target={target} onClose={onClose} onDone={onDone} />;
}

function ShareSheetInner({
  target,
  onClose,
  onDone,
}: {
  target: Exclude<SharedTarget, { kind: 'youtube' }>;
  onClose: () => void;
  onDone?: (kind: 'magnet' | 'media') => void;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const [resolved, setResolved] = useState<SeerrItem | null>(null);
  const [loading, setLoading] = useState(target.kind === 'media');
  const [submitting, setSubmitting] = useState(false);
  const [selectedSeasons, setSelectedSeasons] = useState<number[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [doneMsg, setDoneMsg] = useState<string | null>(null);

  useEffect(() => {
    if (target.kind !== 'media') return;
    let cancelled = false;
    resolveMediaUrl(target.url)
      .then((item) => {
        if (cancelled) return;
        setResolved(item);
        if (item.mediaType === 'tv') {
          setSelectedSeasons(
            (item.seasons ?? [])
              .filter((s) => s.status === 'none' || s.status === 'deleted')
              .map((s) => s.seasonNumber),
          );
        }
      })
      .catch((e) => {
        if (!cancelled) setErr((e as Error).message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [target]);

  const confirm = async () => {
    if (!target || submitting) return;
    setSubmitting(true);
    setErr(null);
    try {
      if (target.kind === 'magnet') {
        const res = await addMagnet(target.uri);
        setDoneMsg(res.already_exists ? 'Already in qBittorrent' : 'Added to qBittorrent');
        onDone?.('magnet');
      } else if (resolved && resolved.mediaType !== 'collection') {
        await createSeerrRequest(
          resolved.mediaType,
          resolved.id,
          resolved.mediaType === 'tv' ? selectedSeasons : undefined,
        );
        setDoneMsg('Requested in Seerr');
        onDone?.('media');
      }
      setTimeout(() => onClose(), 900);
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

  const alreadyAvailable =
    resolved != null &&
    (resolved.status === 'available' ||
      (resolved.mediaType === 'movie' && (resolved.status === 'requested' || resolved.status === 'grabbing')));

  return (
    <Modal
      visible={!!target}
      animationType="slide"
      onRequestClose={onClose}
      transparent
      statusBarTranslucent
      navigationBarTranslucent
    >
      <View style={[st.wrap, { backgroundColor: t.overlay }]}>
        <Pressable style={{ flex: 1 }} onPress={() => !submitting && onClose()} />
        <View style={[st.sheet, { backgroundColor: t.bg, paddingBottom: insets.bottom + space(4) }]}>
          <View style={[st.grabber, { backgroundColor: t.border }]} />
          <View style={{ paddingHorizontal: screenPad, paddingTop: space(3), gap: space(3) }}>
            <Text style={{ color: t.text, fontSize: 18, fontWeight: '600' }}>
              {target?.kind === 'magnet' ? 'Add torrent' : 'Request in Seerr'}
            </Text>

            {err ? <Banner tone="bad" title="Failed">{err}</Banner> : null}
            {doneMsg ? <Banner tone="ok" title={doneMsg} /> : null}

            {target?.kind === 'magnet' ? (
              <View style={[st.box, { backgroundColor: t.card }]}>
                <Icon name="magnet" size={20} color={t.accent} />
                <Text style={{ color: t.text, fontSize: 14.5, fontWeight: '500', flex: 1 }} numberOfLines={3}>
                  {target.name}
                </Text>
              </View>
            ) : loading ? (
              <View style={{ paddingVertical: space(5), alignItems: 'center' }}>
                <ActivityIndicator size="small" color={t.accent} />
              </View>
            ) : resolved ? (
              <View style={{ gap: space(3) }}>
                <View style={[st.box, { backgroundColor: t.card }]}>
                  {resolved.poster ? (
                    <Image source={{ uri: resolved.poster }} style={[st.poster, { backgroundColor: t.cardAlt }]} />
                  ) : null}
                  <View style={{ flex: 1, gap: 4 }}>
                    <Text style={{ color: t.text, fontSize: 15, fontWeight: '600' }} numberOfLines={2}>
                      {resolved.title}
                    </Text>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(1.5) }}>
                      <Pill label={resolved.mediaType === 'tv' ? 'TV' : 'Movie'} tone="faint" />
                      {resolved.year ? <Text style={{ color: t.textFaint, fontSize: 12.5 }}>{resolved.year}</Text> : null}
                      {resolved.status !== 'none' ? (
                        <Pill label={resolved.status} tone={resolved.status === 'available' ? 'ok' : 'dim'} />
                      ) : null}
                    </View>
                  </View>
                </View>

                {resolved.mediaType === 'tv' && (resolved.seasons?.length ?? 0) > 0 ? (
                  <ScrollView style={{ maxHeight: 220 }}>
                    <List>
                      {resolved.seasons!.map((s, idx, arr) => {
                        const locked = s.status !== 'none' && s.status !== 'deleted';
                        const on = selectedSeasons.includes(s.seasonNumber);
                        return (
                          <ListRow
                            key={s.seasonNumber}
                            title={s.name}
                            last={idx === arr.length - 1}
                            chevron={false}
                            onPress={locked ? undefined : () => toggleSeason(s.seasonNumber)}
                            trailing={
                              locked ? (
                                <Pill label={s.status} tone={s.status === 'available' ? 'ok' : 'dim'} />
                              ) : (
                                <Icon
                                  name={on ? 'checkbox-marked' : 'checkbox-blank-outline'}
                                  size={20}
                                  color={on ? t.accent : t.textFaint}
                                />
                              )
                            }
                          />
                        );
                      })}
                    </List>
                  </ScrollView>
                ) : null}
              </View>
            ) : null}

            <View style={{ flexDirection: 'row', gap: space(2) }}>
              <Button label="Cancel" kind="subtle" style={{ flex: 1 }} disabled={submitting} onPress={onClose} />
              {!doneMsg ? (
                <Button
                  label={target?.kind === 'magnet' ? 'Download' : 'Request'}
                  icon={target?.kind === 'magnet' ? 'download' : 'plus'}
                  style={{ flex: 1 }}
                  busy={submitting}
                  disabled={
                    loading ||
                    (target?.kind === 'media' &&
                      (!resolved || alreadyAvailable || (resolved.mediaType === 'tv' && selectedSeasons.length === 0)))
                  }
                  onPress={() => void confirm()}
                />
              ) : null}
            </View>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const st = StyleSheet.create({
  wrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '80%', borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg, paddingTop: space(2) },
  grabber: { width: 38, height: 4, borderRadius: 2, alignSelf: 'center' },
  box: { flexDirection: 'row', alignItems: 'center', gap: space(3), padding: space(3.5), borderRadius: radius.md },
  poster: { width: 44, height: 64, borderRadius: 8 },
});
