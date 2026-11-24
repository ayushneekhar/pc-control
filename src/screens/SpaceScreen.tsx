import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useMemo, useState } from 'react';
import {
  Modal,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Banner, Bar, Button, Card, Empty, Icon, List, Pill, Screen } from '../components/ui';
import {
  freeSpace,
  getSpace,
  SpaceCandidate,
  SpaceFreeResponse,
  SpaceHdd,
  SpaceResponse,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { bytes } from '../lib/format';
import {
  filterAndSortCandidates,
  formatUpstreamErrors,
  SpaceFilter,
  SpaceSort,
  summarizeSelection,
  toFreeRequest,
} from '../lib/spaceHelpers';
import { radius, screenPad, space, useTheme } from '../theme';

const CACHE_KEY = 'space:overview';

const FILTERS: { id: SpaceFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'movie', label: 'Movies' },
  { id: 'season', label: 'TV' },
];

function HddSkeleton() {
  const t = useTheme();
  return (
    <Card style={{ gap: space(2.5) }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <View style={[st.skel, { width: 120, height: 22, backgroundColor: t.cardAlt }]} />
        <View style={[st.skel, { width: 96, height: 14, backgroundColor: t.cardAlt }]} />
      </View>
      <View style={[st.skel, { height: 6, borderRadius: 3, backgroundColor: t.cardAlt }]} />
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <View style={[st.skel, { width: 110, height: 13, backgroundColor: t.cardAlt }]} />
        <View style={[st.skel, { width: 72, height: 13, backgroundColor: t.cardAlt }]} />
      </View>
    </Card>
  );
}

function ListSkeleton() {
  const t = useTheme();
  return (
    <List>
      {[0, 1, 2, 3, 4].map((i) => (
        <View
          key={i}
          style={[
            st.itemRow,
            i < 4 && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: t.border },
          ]}
        >
          <View style={[st.skel, { width: 20, height: 20, borderRadius: 5, backgroundColor: t.cardAlt }]} />
          <View style={{ flex: 1, gap: 6 }}>
            <View style={[st.skel, { width: `${62 + ((i * 11) % 26)}%`, height: 15, backgroundColor: t.cardAlt }]} />
            <View style={[st.skel, { width: 90, height: 12, backgroundColor: t.cardAlt }]} />
          </View>
          <View style={[st.skel, { width: 52, height: 15, backgroundColor: t.cardAlt }]} />
        </View>
      ))}
    </List>
  );
}

function HddSummaryCard({ hdd }: { hdd: SpaceHdd }) {
  const t = useTheme();
  if (!hdd.mounted) {
    return <Banner tone="warn" icon="harddisk-remove" title="HDD not mounted" />;
  }
  const total = hdd.total_bytes ?? 0;
  const used = hdd.used_bytes ?? 0;
  const free = hdd.free_bytes ?? 0;
  const predicted = hdd.predicted_free_bytes ?? free;
  const pct = total > 0 ? (used / total) * 100 : 0;
  const lowNow = free < hdd.threshold_bytes;
  const lowAfter = predicted < hdd.threshold_bytes;
  const barColor = predicted < 0 ? t.bad : lowNow || lowAfter ? t.warn : t.accent;
  const freeColor = free < hdd.threshold_bytes ? t.warn : t.text;
  const hasPending = hdd.pending_count > 0 || hdd.pending_bytes > 0;

  return (
    <Card style={{ gap: space(2.5) }}>
      <View style={{ flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', gap: space(2) }}>
        <Text style={[st.freeHeadline, { color: freeColor }]} numberOfLines={1}>
          {bytes(free, 1)} free
        </Text>
        <Text style={[st.totalText, { color: t.textDim }]} numberOfLines={1}>
          {bytes(used, 1)} / {bytes(total, 1)}
        </Text>
      </View>

      <Bar pct={pct} color={barColor} height={6} />

      {hasPending ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
          <Text style={[st.metaText, { color: t.textDim }]} numberOfLines={1}>
            After {hdd.pending_count} active download{hdd.pending_count === 1 ? '' : 's'}
            {hdd.pending_unknown > 0 ? ` (+${hdd.pending_unknown} unknown)` : ''}
          </Text>
          <Text
            style={[
              st.metaText,
              {
                color: predicted < 0 ? t.bad : lowAfter ? t.warn : t.accent,
                fontWeight: '600',
              },
            ]}
            numberOfLines={1}
          >
            {predicted >= 0 ? `${bytes(predicted, 1)} free` : `${bytes(-predicted, 1)} short`}
          </Text>
        </View>
      ) : null}
    </Card>
  );
}

function CandidateRow({
  item,
  selected,
  last,
  onToggle,
}: {
  item: SpaceCandidate;
  selected: boolean;
  last: boolean;
  onToggle: (id: string) => void;
}) {
  const t = useTheme();
  const subParts: string[] = [];
  if (item.subtitle) subParts.push(item.subtitle);
  else if (item.year) subParts.push(String(item.year));
  if (item.kind === 'season' && item.episode_count) {
    subParts.push(`${item.episode_count} ep`);
  }

  return (
    <Pressable
      onPress={() => onToggle(item.id)}
      android_ripple={{ color: t.ripple, foreground: true }}
      accessibilityRole="checkbox"
      accessibilityState={{ checked: selected }}
      style={({ pressed }) => [
        st.itemRow,
        !last && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: t.border },
        selected && { backgroundColor: t.accentSoft },
        pressed && { opacity: 0.85 },
      ]}
    >
      <Icon
        name={selected ? 'checkbox-marked' : 'checkbox-blank-outline'}
        size={22}
        color={selected ? t.accent : t.textFaint}
      />
      <View style={{ flex: 1, gap: 3 }}>
        <Text style={[st.itemTitle, { color: t.text }]} numberOfLines={1}>
          {item.title}
        </Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6 }}>
          {subParts.length > 0 ? (
            <Text style={[st.itemMeta, { color: t.textDim }]} numberOfLines={1}>
              {subParts.join(' · ')}
            </Text>
          ) : null}
          {item.warn_unwatched && item.unwatched_users.length > 0 ? (
            <View style={[st.warnChip, { backgroundColor: t.warnSoft }]}>
              <Icon name="alert" size={12} color={t.warn} />
              <Text style={[st.warnChipText, { color: t.warn }]} numberOfLines={1}>
                {item.unwatched_users.join(', ')}
              </Text>
            </View>
          ) : null}
        </View>
      </View>
      <Text style={[st.itemSize, { color: t.text }]} numberOfLines={1}>
        {bytes(item.size_bytes, 1)}
      </Text>
    </Pressable>
  );
}

export default function SpaceScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const insets = useSafeAreaInsets();
  const [data, setData] = useState<SpaceResponse | null>(() => cacheGet<SpaceResponse>(CACHE_KEY));
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState<SpaceFilter>('all');
  const [sort, setSort] = useState<SpaceSort>('size');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [freeing, setFreeing] = useState(false);
  const [feedback, setFeedback] = useState<SpaceFreeResponse | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await getSpace();
      cacheSet(CACHE_KEY, res);
      setData(res);
      setError(null);
      setSelectedIds((prev) => {
        if (prev.size === 0) return prev;
        const valid = new Set(res.candidates.map((c) => c.id));
        const next = new Set<string>();
        for (const id of prev) {
          if (valid.has(id)) next.add(id);
        }
        return next.size === prev.size ? prev : next;
      });
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load]),
  );

  const candidates = useMemo(() => data?.candidates ?? [], [data?.candidates]);
  const visible = useMemo(
    () => filterAndSortCandidates(candidates, filter, sort),
    [candidates, filter, sort],
  );
  const selection = useMemo(
    () => summarizeSelection(candidates, selectedIds),
    [candidates, selectedIds],
  );

  const toggleItem = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedIds(new Set());
  }, []);

  const onConfirmFree = useCallback(async () => {
    if (selection.count === 0 || freeing) return;
    setFreeing(true);
    try {
      const res = await freeSpace(toFreeRequest(selection.selected));
      setFeedback(res);
      setConfirmOpen(false);
      const succeededIds = new Set(res.results.filter((r) => r.ok).map((r) => r.id));
      setSelectedIds((prev) => {
        const next = new Set<string>();
        for (const id of prev) {
          if (!succeededIds.has(id)) next.add(id);
        }
        return next;
      });
      if (data) {
        const updated: SpaceResponse = {
          ...data,
          hdd: res.hdd,
          candidates: data.candidates.filter((c) => !succeededIds.has(c.id)),
        };
        cacheSet(CACHE_KEY, updated);
        setData(updated);
      }
      await load();
    } catch (e) {
      setConfirmOpen(false);
      setError((e as Error).message);
    } finally {
      setFreeing(false);
    }
  }, [data, freeing, load, selection.count, selection.selected]);

  const upstreamErr = formatUpstreamErrors(data?.errors);
  const failedCount = feedback ? feedback.results.filter((r) => !r.ok).length : 0;
  const hasWarnings = feedback
    ? feedback.warnings.length > 0 || feedback.results.some((r) => r.warnings.length > 0)
    : false;
  const feedbackTone =
    !feedback ? 'ok' : failedCount === feedback.results.length ? 'bad' : failedCount > 0 || hasWarnings ? 'warn' : 'ok';

  return (
    <Screen title="Space" onBack={() => nav.goBack()} scroll={false}>
      <ScrollView
        style={{ flex: 1 }}
        contentContainerStyle={{
          paddingHorizontal: screenPad,
          paddingBottom: selection.count > 0 ? space(24) + insets.bottom : space(8) + insets.bottom,
          gap: space(3),
        }}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={async () => {
              setRefreshing(true);
              await load();
              setRefreshing(false);
            }}
            tintColor={t.accent}
            colors={[t.accent]}
            progressBackgroundColor={t.card}
          />
        }
      >
        {error ? <Banner tone="bad" title={error} /> : null}

        {feedback ? (
          <Banner
            tone={feedbackTone}
            title={
              failedCount === 0
                ? `Freed ${bytes(feedback.freed_bytes, 1)}`
                : failedCount === feedback.results.length
                  ? 'Could not free selected items'
                  : `Freed ${bytes(feedback.freed_bytes, 1)} · ${failedCount} failed`
            }
          >
            <View style={{ gap: 4, marginTop: 2 }}>
              {feedback.results.map((r) => (
                <View key={r.id} style={{ gap: 2 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                    <Icon
                      name={r.ok ? 'check' : 'close-circle'}
                      size={14}
                      color={r.ok ? t.ok : t.bad}
                    />
                    <Text style={{ color: t.text, fontSize: 13, flex: 1 }} numberOfLines={1}>
                      {r.label}
                    </Text>
                    {r.ok ? (
                      <Text style={{ color: t.textDim, fontSize: 12.5, fontVariant: ['tabular-nums'] }}>
                        {bytes(r.freed_bytes, 1)}
                      </Text>
                    ) : null}
                  </View>
                  {r.error ? (
                    <Text style={{ color: t.bad, fontSize: 12, paddingLeft: 20 }}>{r.error}</Text>
                  ) : null}
                  {r.warnings.map((w, idx) => (
                    <Text key={idx} style={{ color: t.warn, fontSize: 12, paddingLeft: 20 }}>
                      {w}
                    </Text>
                  ))}
                </View>
              ))}
              {feedback.warnings.map((w, idx) => (
                <Text key={idx} style={{ color: t.warn, fontSize: 12 }}>
                  {w}
                </Text>
              ))}
              <Button
                label="Dismiss"
                kind="subtle"
                compact
                style={{ alignSelf: 'flex-start', marginTop: 6 }}
                onPress={() => setFeedback(null)}
              />
            </View>
          </Banner>
        ) : null}

        {data ? <HddSummaryCard hdd={data.hdd} /> : <HddSkeleton />}

        {upstreamErr ? <Banner tone="warn" title={upstreamErr} /> : null}

        <View style={st.controlsRow}>
          <View style={{ flexDirection: 'row', gap: 6, flex: 1 }}>
            {FILTERS.map((f) => {
              const active = filter === f.id;
              return (
                <Pressable
                  key={f.id}
                  onPress={() => setFilter(f.id)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                  style={[
                    st.chip,
                    { backgroundColor: active ? t.accentSoft : t.card },
                  ]}
                >
                  <Text style={[st.chipText, { color: active ? t.accent : t.textDim }]}>
                    {f.label}
                  </Text>
                </Pressable>
              );
            })}
          </View>
          <Pressable
            onPress={() => setSort((s) => (s === 'size' ? 'title' : 'size'))}
            accessibilityRole="button"
            accessibilityLabel={`Sort by ${sort === 'size' ? 'Largest' : 'Title'}`}
            style={[st.chip, { backgroundColor: t.card }]}
          >
            <Icon name="sort-variant" size={15} color={t.textDim} />
            <Text style={[st.chipText, { color: t.text }]}>
              {sort === 'size' ? 'Largest' : 'Title'}
            </Text>
          </Pressable>
        </View>

        {!data ? (
          <ListSkeleton />
        ) : visible.length > 0 ? (
          <List>
            {visible.map((item, idx) => (
              <CandidateRow
                key={item.id}
                item={item}
                selected={selectedIds.has(item.id)}
                last={idx === visible.length - 1}
                onToggle={toggleItem}
              />
            ))}
          </List>
        ) : (
          <Empty
            icon="check-circle"
            title={
              filter === 'movie'
                ? 'No watched movies'
                : filter === 'season'
                  ? 'No watched TV seasons'
                  : 'No watched items'
            }
          />
        )}
      </ScrollView>

      {selection.count > 0 ? (
        <View
          style={[
            st.stickyBar,
            {
              backgroundColor: t.card,
              borderTopColor: t.border,
              paddingBottom: Math.max(insets.bottom, space(3)),
            },
          ]}
        >
          <View style={{ flex: 1, gap: 2 }}>
            <Text style={[st.stickyCount, { color: t.text }]}>
              {selection.count} selected · {bytes(selection.totalBytes, 1)}
            </Text>
            <Pressable onPress={clearSelection} hitSlop={8} style={{ alignSelf: 'flex-start' }}>
              <Text style={{ color: t.textDim, fontSize: 12.5 }}>Clear</Text>
            </Pressable>
          </View>
          <Button
            label={`Free ${bytes(selection.totalBytes, 1)}`}
            kind="primary"
            onPress={() => setConfirmOpen(true)}
          />
        </View>
      ) : null}

      <Modal
        visible={confirmOpen}
        transparent
        animationType="fade"
        onRequestClose={() => !freeing && setConfirmOpen(false)}
      >
        <View style={[st.modalBackdrop, { backgroundColor: t.overlay }]}>
          <View style={[st.modalCard, { backgroundColor: t.card }]}>
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
              <Text style={[st.modalTitle, { color: t.text }]}>
                Free {bytes(selection.totalBytes, 1)}?
              </Text>
              <Pill label={`${selection.count} item${selection.count === 1 ? '' : 's'}`} tone="dim" />
            </View>

            {selection.unwatched.length > 0 ? (
              <View style={[st.unwatchedBox, { backgroundColor: t.warnSoft }]}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <Icon name="alert" size={16} color={t.warn} />
                  <Text style={{ color: t.warn, fontSize: 13, fontWeight: '600' }}>
                    Not watched by others
                  </Text>
                </View>
                {selection.unwatched.map((it) => (
                  <Text key={it.id} style={{ color: t.text, fontSize: 12.5 }} numberOfLines={2}>
                    • {it.title}
                    {it.subtitle ? ` · ${it.subtitle}` : ''} ({it.unwatched_users.join(', ')})
                  </Text>
                ))}
              </View>
            ) : null}

            <ScrollView style={{ maxHeight: 220 }} contentContainerStyle={{ gap: 6 }}>
              {selection.selected.map((it) => (
                <View
                  key={it.id}
                  style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}
                >
                  <Text style={{ color: t.textDim, fontSize: 13.5, flex: 1 }} numberOfLines={1}>
                    {it.title}
                    {it.subtitle ? ` · ${it.subtitle}` : ''}
                  </Text>
                  <Text style={{ color: t.text, fontSize: 13, fontWeight: '500', fontVariant: ['tabular-nums'] }}>
                    {bytes(it.size_bytes, 1)}
                  </Text>
                </View>
              ))}
            </ScrollView>

            <View style={{ flexDirection: 'row', gap: space(2), marginTop: space(1) }}>
              <Button
                label="Cancel"
                kind="subtle"
                style={{ flex: 1 }}
                disabled={freeing}
                onPress={() => setConfirmOpen(false)}
              />
              <Button
                label="Delete"
                icon="delete"
                kind="danger"
                style={{ flex: 1 }}
                busy={freeing}
                onPress={() => void onConfirmFree()}
              />
            </View>
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

const st = StyleSheet.create({
  skel: { borderRadius: 6 },
  freeHeadline: { fontSize: 20, fontWeight: '600', fontVariant: ['tabular-nums'] },
  totalText: { fontSize: 13, fontVariant: ['tabular-nums'] },
  metaText: { fontSize: 12.5, fontVariant: ['tabular-nums'] },
  controlsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
    minHeight: 34,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 12,
    height: 34,
    borderRadius: radius.pill,
  },
  chipText: { fontSize: 13, fontWeight: '500' },
  itemRow: {
    minHeight: 60,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(3),
    paddingHorizontal: space(4),
    paddingVertical: space(2.5),
  },
  itemTitle: { fontSize: 14.5, fontWeight: '500' },
  itemMeta: { fontSize: 12.5, fontVariant: ['tabular-nums'] },
  itemSize: { fontSize: 13.5, fontWeight: '600', fontVariant: ['tabular-nums'] },
  warnChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: radius.pill,
  },
  warnChipText: { fontSize: 11.5, fontWeight: '500' },
  stickyBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(3),
    paddingHorizontal: screenPad,
    paddingTop: space(3),
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  stickyCount: { fontSize: 14, fontWeight: '600', fontVariant: ['tabular-nums'] },
  modalBackdrop: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: screenPad,
  },
  modalCard: {
    borderRadius: radius.md,
    padding: space(4),
    gap: space(3),
  },
  modalTitle: { fontSize: 18, fontWeight: '600', fontVariant: ['tabular-nums'] },
  unwatchedBox: {
    borderRadius: radius.sm,
    padding: space(3),
    gap: 5,
  },
});
