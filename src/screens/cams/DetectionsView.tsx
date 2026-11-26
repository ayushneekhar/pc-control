// Detections gallery: last 300 saved catdetect annotated JPEG snapshots in a virtualized 2-column grid,
// with camera filter chips, pull-to-refresh, and a full-screen viewer modal with Jump to Replay.
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Image,
  Modal,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { PinchZoom } from '../../components/PinchZoom';
import { Banner, Button, Icon } from '../../components/ui';
import { DetectionSnap, getCatdetectSnaps, serverUrl } from '../../lib/api';
import { CAMS, NAMES } from '../../lib/cams/config';
import { DAY, recCamOf } from '../../lib/cams/replay';
import { radius, screenPad, space, Theme, useTheme } from '../../theme';

const GRID_GAP = space(2.5); // 10
const REPLAY_WINDOW_MS = DAY * 1000;

function camLabel(cam: string): string {
  return NAMES[cam] ?? cam;
}

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

/** Compact local timestamp for thumbnail overlays: "HH:mm" today, else "d MMM · HH:mm". */
function formatThumbTime(tsMs: number, now: Date): string {
  const d = new Date(tsMs);
  const hm = d.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  if (isSameDay(d, now)) return hm;
  const dm = d.toLocaleDateString([], { day: 'numeric', month: 'short' });
  return `${dm} · ${hm}`;
}

/** Exact local date and time for the full-screen viewer header. */
function formatExactTime(tsMs: number): string {
  const d = new Date(tsMs);
  const dateStr = d.toLocaleDateString([], {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
  const timeStr = d.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  return `${dateStr} · ${timeStr}`;
}

type TileProps = {
  item: DetectionSnap;
  index: number;
  baseUrl: string;
  tileW: number;
  tileH: number;
  timeText: string;
  theme: Theme;
  onPress: (index: number) => void;
};

const SnapTile = memo(function SnapTile({
  item,
  index,
  baseUrl,
  tileW,
  tileH,
  timeText,
  theme: t,
  onPress,
}: TileProps) {
  return (
    <Pressable
      onPress={() => onPress(index)}
      accessibilityRole="button"
      accessibilityLabel={`${camLabel(item.cam)} detection at ${timeText}`}
      style={({ pressed }) => [
        st.tile,
        {
          width: tileW,
          height: tileH,
          backgroundColor: t.card,
          opacity: pressed ? 0.85 : 1,
        },
      ]}
    >
      <Image
        source={{ uri: `${baseUrl}${item.url}` }}
        style={StyleSheet.absoluteFill}
        resizeMode="cover"
        fadeDuration={100}
      />
      <View style={st.tileOverlay} pointerEvents="none">
        <View style={[st.overlayChip, { backgroundColor: t.mediaChip, flexShrink: 1 }]}>
          <Text style={[st.overlayText, { color: t.onMedia }]} numberOfLines={1}>
            {camLabel(item.cam)}
          </Text>
        </View>
        <View style={[st.overlayChip, { backgroundColor: t.mediaChip, flexShrink: 0 }]}>
          <Text style={[st.overlayText, st.tabular, { color: t.onMedia }]} numberOfLines={1}>
            {timeText}
          </Text>
        </View>
      </View>
    </Pressable>
  );
});

export function DetectionsView({
  visible,
  bottomInset,
  onJumpToReplay,
}: {
  visible: boolean;
  bottomInset: number;
  onJumpToReplay: (cam: string, tsMs: number) => void;
}) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const win = useWindowDimensions();

  const [camFilter, setCamFilter] = useState<string>('all');
  const [snaps, setSnaps] = useState<DetectionSnap[]>([]);
  const [counts, setCounts] = useState<Record<string, number>>({
    all: 0,
    cat: 0,
    cat2: 0,
    cat3: 0,
  });
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);
  const [loadedAt, setLoadedAt] = useState<number>(() => Date.now());
  const [viewerNow, setViewerNow] = useState<number>(() => Date.now());

  const reqSeq = useRef(0);
  const baseUrl = serverUrl();

  const fetchSnaps = useCallback(async (cam: string, isRefresh: boolean) => {
    const seq = ++reqSeq.current;
    if (isRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const res = await getCatdetectSnaps(cam === 'all' ? undefined : cam, 300);
      if (seq !== reqSeq.current) return;
      setSnaps(res.snaps ?? []);
      setCounts(res.counts ?? { all: 0, cat: 0, cat2: 0, cat3: 0 });
      setLoadedAt(Date.now());
      setSelectedIdx((prev) => {
        if (prev === null) return null;
        if (!res.snaps?.length) return null;
        return Math.min(prev, res.snaps.length - 1);
      });
    } catch (e) {
      if (seq !== reqSeq.current) return;
      setError((e as Error)?.message || "Couldn't load detections");
    } finally {
      if (seq === reqSeq.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    if (!visible) return;
    const seq = ++reqSeq.current;
    let cancelled = false;
    getCatdetectSnaps(camFilter === 'all' ? undefined : camFilter, 300)
      .then((res) => {
        if (cancelled || seq !== reqSeq.current) return;
        setError(null);
        setSnaps(res.snaps ?? []);
        setCounts(res.counts ?? { all: 0, cat: 0, cat2: 0, cat3: 0 });
        setLoadedAt(Date.now());
        setSelectedIdx((prev) => {
          if (prev === null) return null;
          if (!res.snaps?.length) return null;
          return Math.min(prev, res.snaps.length - 1);
        });
      })
      .catch((e: unknown) => {
        if (cancelled || seq !== reqSeq.current) return;
        setError((e as Error)?.message || "Couldn't load detections");
      })
      .finally(() => {
        if (cancelled || seq !== reqSeq.current) return;
        setLoading(false);
        setRefreshing(false);
      });
    return () => {
      cancelled = true;
    };
  }, [visible, camFilter]);

  const onRefresh = useCallback(() => {
    void fetchSnaps(camFilter, true);
  }, [camFilter, fetchSnaps]);

  const onSelectFilter = useCallback((next: string) => {
    setSelectedIdx(null);
    setError(null);
    setCamFilter(next);
  }, []);

  const openViewer = useCallback((index: number) => {
    setViewerNow(Date.now());
    setSelectedIdx(index);
  }, []);

  const closeViewer = useCallback(() => {
    setSelectedIdx(null);
  }, []);

  const chipItems = useMemo(() => {
    const knownIds = new Set<string>(['all', ...CAMS.map((c) => c.id)]);
    const extras = Object.keys(counts)
      .filter((k) => !knownIds.has(k) && (counts[k] ?? 0) > 0)
      .sort()
      .map((k) => ({ id: k, label: camLabel(k), count: counts[k] ?? 0 }));
    return [
      { id: 'all', label: 'All', count: counts.all ?? 0 },
      ...CAMS.map((c) => ({ id: c.id, label: c.name, count: counts[c.id] ?? 0 })),
      ...extras,
    ];
  }, [counts]);

  const tileW = Math.max(120, Math.floor((win.width - screenPad * 2 - GRID_GAP) / 2));
  const tileH = Math.round((tileW * 9) / 16);
  const rowStride = tileH + GRID_GAP;

  const nowDate = useMemo(() => new Date(loadedAt), [loadedAt]);

  const renderItem = useCallback(
    ({ item, index }: { item: DetectionSnap; index: number }) => (
      <SnapTile
        item={item}
        index={index}
        baseUrl={baseUrl}
        tileW={tileW}
        tileH={tileH}
        timeText={formatThumbTime(item.ts_ms, nowDate)}
        theme={t}
        onPress={openViewer}
      />
    ),
    [baseUrl, tileW, tileH, nowDate, t, openViewer],
  );

  const keyExtractor = useCallback((item: DetectionSnap) => item.filename, []);

  const getItemLayout = useCallback(
    (_data: ArrayLike<DetectionSnap> | null | undefined, index: number) => {
      const row = Math.floor(index / 2);
      return { length: rowStride, offset: row * rowStride, index };
    },
    [rowStride],
  );

  const selected =
    selectedIdx !== null && selectedIdx >= 0 && selectedIdx < snaps.length
      ? snaps[selectedIdx]
      : null;

  const canJumpToReplay =
    selected !== null &&
    recCamOf(selected.cam) !== null &&
    viewerNow - selected.ts_ms <= REPLAY_WINDOW_MS &&
    selected.ts_ms <= viewerNow + 60_000;

  const handleJump = useCallback(() => {
    if (!selected) return;
    const { cam, ts_ms } = selected;
    setSelectedIdx(null);
    onJumpToReplay(cam, ts_ms);
  }, [selected, onJumpToReplay]);

  const handlePrev = useCallback(() => {
    setSelectedIdx((i) => (i !== null && i > 0 ? i - 1 : i));
  }, []);

  const handleNext = useCallback(() => {
    setSelectedIdx((i) => (i !== null && i < snaps.length - 1 ? i + 1 : i));
  }, [snaps.length]);

  return (
    <View style={[st.root, { backgroundColor: t.bg }]}>
      {/* Compact camera filter chips */}
      <View style={[st.chipBarWrap, { borderBottomColor: t.border }]}>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={st.chipBar}
        >
          {chipItems.map((chip) => {
            const active = camFilter === chip.id;
            return (
              <Pressable
                key={chip.id}
                onPress={() => onSelectFilter(chip.id)}
                accessibilityRole="tab"
                accessibilityState={{ selected: active }}
                style={({ pressed }) => [
                  st.chip,
                  {
                    backgroundColor: active ? t.accentSoft : t.card,
                    opacity: pressed ? 0.8 : 1,
                  },
                ]}
              >
                <Text
                  style={[
                    st.chipLabel,
                    { color: active ? t.accent : t.textDim },
                  ]}
                  numberOfLines={1}
                >
                  {chip.label}
                </Text>
                <Text
                  style={[
                    st.chipCount,
                    st.tabular,
                    { color: active ? t.accent : t.textFaint },
                  ]}
                  numberOfLines={1}
                >
                  {chip.count}
                </Text>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      {/* Grid / Loading / Error / Empty */}
      {error && snaps.length === 0 ? (
        <View style={st.stateWrap}>
          <Banner tone="bad" title="Couldn't load detections">
            {error}
          </Banner>
          <Button
            label="Retry"
            kind="subtle"
            icon="refresh"
            onPress={() => void fetchSnaps(camFilter, false)}
          />
        </View>
      ) : loading && snaps.length === 0 ? (
        <View style={st.centerState}>
          <ActivityIndicator size="small" color={t.accent} />
          <Text style={[st.stateText, { color: t.textDim }]}>Loading detections…</Text>
        </View>
      ) : (
        <FlatList
          data={snaps}
          keyExtractor={keyExtractor}
          renderItem={renderItem}
          numColumns={2}
          getItemLayout={getItemLayout}
          columnWrapperStyle={st.row}
          contentContainerStyle={[
            st.listContent,
            { paddingBottom: bottomInset + space(6) },
            snaps.length === 0 && st.emptyListGrow,
          ]}
          initialNumToRender={14}
          maxToRenderPerBatch={14}
          windowSize={7}
          removeClippedSubviews
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={onRefresh}
              tintColor={t.accent}
              colors={[t.accent]}
              progressBackgroundColor={t.card}
            />
          }
          ListHeaderComponent={
            error ? (
              <View style={{ marginBottom: space(2.5) }}>
                <Banner tone="warn" title="Refresh failed">
                  {error}
                </Banner>
              </View>
            ) : null
          }
          ListEmptyComponent={
            <View style={st.centerState}>
              <Icon name="cat" size={32} color={t.textFaint} />
              <Text style={[st.stateText, { color: t.textDim }]}>
                {camFilter === 'all'
                  ? 'No saved detections yet'
                  : `No detections on ${camLabel(camFilter)}`}
              </Text>
            </View>
          }
        />
      )}

      {/* Full-screen snapshot viewer modal */}
      <Modal
        visible={selected !== null}
        transparent={false}
        animationType="fade"
        statusBarTranslucent
        onRequestClose={closeViewer}
      >
        {selected ? (
          <View style={[st.modalRoot, { backgroundColor: t.media }]}>
            {/* Top header */}
            <View
              style={[
                st.modalHeader,
                {
                  paddingTop: insets.top + space(2),
                  borderBottomColor: t.border,
                  backgroundColor: t.media,
                },
              ]}
            >
              <Pressable
                onPress={closeViewer}
                hitSlop={8}
                accessibilityLabel="Close viewer"
                style={st.modalClose}
              >
                <Icon name="close" size={24} color={t.onMedia} />
              </Pressable>
              <View style={{ flex: 1, minWidth: 0 }}>
                <Text
                  style={[st.modalTitle, { color: t.onMedia }]}
                  numberOfLines={1}
                >
                  {camLabel(selected.cam)}
                </Text>
                <Text
                  style={[st.modalSub, st.tabular, { color: t.onMediaDim }]}
                  numberOfLines={1}
                >
                  {formatExactTime(selected.ts_ms)}
                </Text>
              </View>
              <Text
                style={[st.modalPos, st.tabular, { color: t.onMediaDim }]}
                numberOfLines={1}
              >
                {(selectedIdx ?? 0) + 1} / {snaps.length}
              </Text>
            </View>

            {/* Full image with pinch-to-zoom */}
            <View style={st.modalBody}>
              <PinchZoom resetKey={selected.filename} style={StyleSheet.absoluteFill}>
                <Image
                  source={{ uri: `${baseUrl}${selected.url}` }}
                  style={StyleSheet.absoluteFill}
                  resizeMode="contain"
                />
              </PinchZoom>
            </View>

            {/* Bottom controls: Previous / Jump to Replay (if <=24h) / Next */}
            <View
              style={[
                st.modalFooter,
                {
                  paddingBottom: insets.bottom + space(3),
                  backgroundColor: t.bg,
                  borderTopColor: t.border,
                },
              ]}
            >
              <Button
                label="Prev"
                icon="chevron-left"
                kind="subtle"
                compact
                disabled={selectedIdx === null || selectedIdx <= 0}
                onPress={handlePrev}
                style={st.navBtn}
              />
              {canJumpToReplay ? (
                <Button
                  label="Jump to Replay"
                  icon="history"
                  kind="primary"
                  compact
                  onPress={handleJump}
                  style={st.jumpBtn}
                />
              ) : (
                <View style={st.jumpPlaceholder} />
              )}
              <Button
                label="Next"
                icon="chevron-right"
                kind="subtle"
                compact
                disabled={selectedIdx === null || selectedIdx >= snaps.length - 1}
                onPress={handleNext}
                style={st.navBtn}
              />
            </View>
          </View>
        ) : null}
      </Modal>
    </View>
  );
}

const st = StyleSheet.create({
  root: {
    flex: 1,
  },
  chipBarWrap: {
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  chipBar: {
    paddingHorizontal: screenPad,
    paddingVertical: space(2.5),
    gap: space(2),
    alignItems: 'center',
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 34,
    paddingHorizontal: 12,
    borderRadius: radius.pill,
  },
  chipLabel: {
    fontSize: 13,
    fontWeight: '500',
  },
  chipCount: {
    fontSize: 12,
    fontWeight: '500',
  },
  listContent: {
    paddingHorizontal: screenPad,
    paddingTop: space(3),
  },
  emptyListGrow: {
    flexGrow: 1,
  },
  row: {
    gap: GRID_GAP,
    marginBottom: GRID_GAP,
  },
  tile: {
    borderRadius: radius.sm,
    overflow: 'hidden',
  },
  tileOverlay: {
    position: 'absolute',
    left: 6,
    right: 6,
    bottom: 6,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 4,
  },
  overlayChip: {
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: radius.pill,
  },
  overlayText: {
    fontSize: 11,
    fontWeight: '500',
  },
  tabular: {
    fontVariant: ['tabular-nums'],
  },
  stateWrap: {
    paddingHorizontal: screenPad,
    paddingTop: space(4),
    gap: space(3),
  },
  centerState: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space(2),
    padding: space(6),
  },
  stateText: {
    fontSize: 14,
    fontWeight: '400',
    textAlign: 'center',
  },
  modalRoot: {
    flex: 1,
  },
  modalHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: space(3),
    paddingBottom: space(2.5),
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  modalClose: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 22,
  },
  modalTitle: {
    fontSize: 16,
    fontWeight: '600',
  },
  modalSub: {
    fontSize: 12.5,
    fontWeight: '400',
    marginTop: 1,
  },
  modalPos: {
    fontSize: 12.5,
    fontWeight: '500',
    paddingRight: space(1),
  },
  modalBody: {
    flex: 1,
  },
  modalFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: screenPad,
    paddingTop: space(3),
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  navBtn: {
    minWidth: 84,
  },
  jumpBtn: {
    flex: 1,
  },
  jumpPlaceholder: {
    flex: 1,
  },
});
