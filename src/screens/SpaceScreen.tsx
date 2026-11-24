import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
import {
  Banner,
  Bar,
  Button,
  Card,
  Empty,
  Icon,
  List,
  Pill,
  Screen,
  Segmented,
} from '../components/ui';
import {
  cancelSpaceJob,
  deleteDriveItems,
  DriveDeleteResponse,
  DriveItem,
  DriveStatusResponse,
  freeSpace,
  getSpace,
  getSpaceDrive,
  offloadSpaceItems,
  restoreDriveItems,
  SpaceCandidate,
  SpaceFreeResponse,
  SpaceHdd,
  SpaceResponse,
  SpaceScope,
  TransferJob,
} from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import { bytes, duration, rate } from '../lib/format';
import {
  activeTransferJobs,
  filterAndSortCandidates,
  filterAndSortDriveItems,
  formatUpstreamErrors,
  mergeEnqueuedJobs,
  selectionStateForVisible,
  SpaceFilter,
  SpaceMode,
  SpaceSort,
  summarizeDriveSelection,
  summarizeSelection,
  toFreeRequest,
  toggleSelectAllVisible,
  transferStatusLabel,
} from '../lib/spaceHelpers';
import { subscribeSpaceJobs } from '../lib/spaceJobsStream';
import { radius, screenPad, space, useTheme } from '../theme';

const CACHE_KEY_WATCHED = 'space:overview';
const CACHE_KEY_ALL = 'space:overview:all';
const CACHE_KEY_DRIVE = 'space:drive';

const MODES: { id: SpaceMode; label: string }[] = [
  { id: 'watched', label: 'Watched' },
  { id: 'all', label: 'All Local' },
  { id: 'drive', label: 'On Drive' },
];

const FILTERS: { id: SpaceFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'movie', label: 'Movies' },
  { id: 'season', label: 'TV' },
];

type ConfirmKind = 'delete_local' | 'offload' | 'restore' | 'delete_drive' | null;

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

function DriveStatusCard({ drive }: { drive: DriveStatusResponse | null }) {
  const t = useTheme();
  if (!drive) return null;

  const quota = drive.quota;
  const total = quota?.total_bytes ?? 0;
  const used = quota?.used_bytes ?? 0;
  const free = quota?.free_bytes ?? (total > 0 ? Math.max(0, total - used) : 0);
  const pct = total > 0 ? (used / total) * 100 : 0;
  const barColor = pct > 90 ? t.warn : t.accent;

  return (
    <Card style={{ gap: space(2) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2), flex: 1 }}>
          <Icon name="google-drive" size={18} color={t.textDim} />
          <Text style={[st.driveTitle, { color: t.text }]} numberOfLines={1}>
            Google Drive
          </Text>
        </View>
        {!drive.configured ? (
          <Pill label="Drive not configured" tone="dim" />
        ) : drive.mounted ? (
          <Pill label="Playable in Jellyfin" tone="ok" dot />
        ) : (
          <Pill label="Mount offline" tone="warn" dot />
        )}
      </View>

      {!drive.configured ? (
        <Text style={[st.metaText, { color: t.textDim }]}>
          Configure the &apos;gdrive&apos; remote in rclone and enable rclone-gdrive.service to offload media.
        </Text>
      ) : total > 0 ? (
        <>
          <Bar pct={pct} color={barColor} height={5} />
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
            <Text style={[st.metaText, { color: t.textDim }]} numberOfLines={1}>
              {bytes(used, 1)} / {bytes(total, 1)}
            </Text>
            <Text style={[st.metaText, { color: t.textDim }]} numberOfLines={1}>
              {bytes(free, 1)} free · {drive.items.length} item{drive.items.length === 1 ? '' : 's'}
            </Text>
          </View>
        </>
      ) : (
        <Text style={[st.metaText, { color: t.textDim }]}>
          {drive.items.length} offloaded item{drive.items.length === 1 ? '' : 's'}
        </Text>
      )}
    </Card>
  );
}

function ActiveTransfersCard({
  jobs,
  cancellingId,
  onCancel,
}: {
  jobs: TransferJob[];
  cancellingId: string | null;
  onCancel: (jobId: string) => void;
}) {
  const t = useTheme();
  if (jobs.length === 0) return null;

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
          <Icon name="cloud-sync" size={18} color={t.accent} />
          <Text style={[st.driveTitle, { color: t.text }]}>
            Active Transfers
          </Text>
        </View>
        <Pill label={`${jobs.length} active`} tone="accent" dot />
      </View>

      {jobs.map((job, idx) => {
        const label = transferStatusLabel(job);
        const isCopying = job.status === 'copying';
        const isVerifying = job.status === 'verifying';
        const canCancel = job.status !== 'cleaning';
        const tone = isVerifying ? 'warn' : job.status === 'queued' ? 'dim' : 'accent';
        const metaParts: string[] = [];
        if (isCopying) {
          metaParts.push(`${job.pct.toFixed(0)}%`);
          if (job.speed_bps > 0) metaParts.push(rate(job.speed_bps));
          if (job.eta_s != null && job.eta_s > 0) metaParts.push(`${duration(job.eta_s)} left`);
        } else if (isVerifying) {
          metaParts.push('Checking MD5 hashes');
        } else if (job.status === 'cleaning') {
          metaParts.push(job.op === 'offload' ? 'Removing local copy' : 'Cleaning remote copy');
        } else {
          metaParts.push(bytes(job.size_bytes, 1));
        }

        return (
          <View
            key={job.id}
            style={[
              { gap: space(1.5) },
              idx > 0 && { paddingTop: space(2.5), borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: t.border },
            ]}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
              <View style={{ flex: 1, gap: 2 }}>
                <Text style={[st.itemTitle, { color: t.text }]} numberOfLines={1}>
                  {job.title}
                  {job.subtitle ? ` · ${job.subtitle}` : ''}
                </Text>
                <Text style={[st.metaText, { color: t.textDim }]} numberOfLines={1}>
                  {metaParts.join(' · ')}
                </Text>
              </View>
              <Pill label={label} tone={tone} />
            </View>

            <Bar
              pct={job.status === 'queued' ? 0 : isVerifying || job.status === 'cleaning' ? 100 : job.pct}
              color={isVerifying ? t.warn : t.accent}
              height={5}
            />

            {canCancel ? (
              <View style={{ flexDirection: 'row', justifyContent: 'flex-end' }}>
                <Button
                  label="Cancel"
                  kind="subtle"
                  compact
                  busy={cancellingId === job.id}
                  onPress={() => onCancel(job.id)}
                />
              </View>
            ) : null}
          </View>
        );
      })}
    </Card>
  );
}

function CandidateRow({
  item,
  selected,
  last,
  showUnwatchedTag,
  onToggle,
}: {
  item: SpaceCandidate;
  selected: boolean;
  last: boolean;
  showUnwatchedTag: boolean;
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
          {showUnwatchedTag && item.watched === false ? (
            <View style={[st.warnChip, { backgroundColor: t.cardAlt }]}>
              <Text style={[st.warnChipText, { color: t.textDim }]} numberOfLines={1}>
                Unwatched
              </Text>
            </View>
          ) : item.warn_unwatched && item.unwatched_users.length > 0 ? (
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

function DriveItemRow({
  item,
  selected,
  last,
  onToggle,
}: {
  item: DriveItem;
  selected: boolean;
  last: boolean;
  onToggle: (relPath: string) => void;
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
      onPress={() => onToggle(item.rel_path)}
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
        {subParts.length > 0 ? (
          <Text style={[st.itemMeta, { color: t.textDim }]} numberOfLines={1}>
            {subParts.join(' · ')}
          </Text>
        ) : null}
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

  const [mode, setMode] = useState<SpaceMode>('watched');
  const [watchedData, setWatchedData] = useState<SpaceResponse | null>(() => cacheGet<SpaceResponse>(CACHE_KEY_WATCHED));
  const [allData, setAllData] = useState<SpaceResponse | null>(() => cacheGet<SpaceResponse>(CACHE_KEY_ALL));
  const [driveData, setDriveData] = useState<DriveStatusResponse | null>(() =>
    cacheGet<DriveStatusResponse>(CACHE_KEY_DRIVE),
  );
  const [jobs, setJobs] = useState<TransferJob[]>(() => cacheGet<DriveStatusResponse>(CACHE_KEY_DRIVE)?.jobs ?? []);
  const [sseConnected, setSseConnected] = useState(false);

  const [error, setError] = useState<string | null>(null);
  const [driveError, setDriveError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'warn' | 'bad'; title: string; body?: string } | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState<SpaceFilter>('all');
  const [sort, setSort] = useState<SpaceSort>('size');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [selectedDrivePaths, setSelectedDrivePaths] = useState<Set<string>>(() => new Set());

  const [confirmKind, setConfirmKind] = useState<ConfirmKind>(null);
  const [busyAction, setBusyAction] = useState(false);
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<SpaceFreeResponse | null>(null);
  const [driveDeleteFeedback, setDriveDeleteFeedback] = useState<DriveDeleteResponse | null>(null);

  const scrollRef = useRef<ScrollView | null>(null);
  const mountedRef = useRef(true);
  const refreshingRef = useRef(false);
  const jobsSeqRef = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const activeScope: SpaceScope = mode === 'all' ? 'all' : 'watched';
  const localData = activeScope === 'all' ? allData : watchedData;
  const modeRef = useRef<SpaceMode>(mode);
  const activeScopeRef = useRef<SpaceScope>(activeScope);

  useEffect(() => {
    modeRef.current = mode;
    activeScopeRef.current = activeScope;
  }, [activeScope, mode]);

  const loadLocal = useCallback(async (scope: SpaceScope) => {
    try {
      const res = await getSpace(scope);
      if (!mountedRef.current) return;
      const key = scope === 'all' ? CACHE_KEY_ALL : CACHE_KEY_WATCHED;
      cacheSet(key, res);
      if (scope === 'all') setAllData(res);
      else setWatchedData(res);
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
      if (mountedRef.current) setError((e as Error).message);
    }
  }, []);

  const loadDrive = useCallback(async (forceRefresh = false) => {
    const reqSeq = jobsSeqRef.current;
    try {
      const res = await getSpaceDrive(forceRefresh);
      if (!mountedRef.current) return;
      cacheSet(CACHE_KEY_DRIVE, res);
      setDriveData(res);
      setDriveError(null);
      // Only overwrite jobs if no newer SSE or enqueue update arrived while this request was in flight
      if (jobsSeqRef.current === reqSeq) {
        jobsSeqRef.current += 1;
        setJobs(res.jobs);
      }
      setSelectedDrivePaths((prev) => {
        if (prev.size === 0) return prev;
        const valid = new Set(res.items.map((it) => it.rel_path));
        const next = new Set<string>();
        for (const p of prev) {
          if (valid.has(p)) next.add(p);
        }
        return next.size === prev.size ? prev : next;
      });
    } catch (e) {
      if (mountedRef.current) {
        setDriveError((e as Error).message);
      }
    }
  }, []);

  const loadAll = useCallback(
    async (forceDrive = false) => {
      const curMode = modeRef.current;
      const curScope = activeScopeRef.current;
      await Promise.all([
        curMode === 'drive' ? Promise.resolve() : loadLocal(curScope),
        loadDrive(forceDrive),
      ]);
    },
    [loadDrive, loadLocal],
  );

  // Track job transitions so completing a transfer refreshes local & drive lists
  const prevJobStatusRef = useRef<Record<string, string>>({});
  const handleJobsUpdate = useCallback(
    (nextJobs: TransferJob[]) => {
      const prev = prevJobStatusRef.current;
      let anyFinished = false;
      let latestFailed: TransferJob | null = null;
      for (const j of nextJobs) {
        const was = prev[j.id];
        if (was && was !== j.status) {
          if (j.status === 'done') anyFinished = true;
          if (j.status === 'failed') latestFailed = j;
        }
      }
      const nextMap: Record<string, string> = {};
      for (const j of nextJobs) nextMap[j.id] = j.status;
      prevJobStatusRef.current = nextMap;
      jobsSeqRef.current += 1;
      setJobs(nextJobs);
      if (latestFailed) {
        setNotice({
          tone: 'bad',
          title: `${latestFailed.op === 'restore' ? 'Restore' : 'Offload'} failed: ${latestFailed.title}`,
          body: latestFailed.error || 'Original files were kept.',
        });
      }
      if (anyFinished) {
        void loadDrive(true);
        void loadLocal(activeScopeRef.current);
      }
    },
    [loadDrive, loadLocal],
  );

  // Subscribe to SSE /pc/api/space/jobs/stream while screen is focused
  useFocusEffect(
    useCallback(() => {
      void loadAll(false);
      return subscribeSpaceJobs(handleJobsUpdate, setSseConnected);
    }, [handleJobsUpdate, loadAll]),
  );

  // Graceful polling fallback if SSE is disconnected while jobs are active
  const activeJobs = useMemo(() => activeTransferJobs(jobs), [jobs]);
  useEffect(() => {
    if (sseConnected || activeJobs.length === 0) return;
    const id = setInterval(() => {
      void loadDrive(false);
    }, 4000);
    return () => clearInterval(id);
  }, [activeJobs.length, loadDrive, sseConnected]);

  const onChangeMode = useCallback(
    (nextMode: SpaceMode) => {
      setMode(nextMode);
      setSelectedIds(new Set());
      setSelectedDrivePaths(new Set());
      if (nextMode === 'drive') {
        void loadDrive(false);
      } else {
        void loadLocal(nextMode);
      }
    },
    [loadDrive, loadLocal],
  );

  const candidates = useMemo(() => localData?.candidates ?? [], [localData?.candidates]);
  const visibleLocal = useMemo(
    () => filterAndSortCandidates(candidates, filter, sort),
    [candidates, filter, sort],
  );
  const localSelection = useMemo(
    () => summarizeSelection(candidates, selectedIds),
    [candidates, selectedIds],
  );

  const driveItems = useMemo(() => driveData?.items ?? [], [driveData?.items]);
  const visibleDrive = useMemo(
    () => filterAndSortDriveItems(driveItems, filter, sort),
    [driveItems, filter, sort],
  );
  const driveSelection = useMemo(
    () => summarizeDriveSelection(driveItems, selectedDrivePaths),
    [driveItems, selectedDrivePaths],
  );

  const toggleLocalItem = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const toggleDriveItem = useCallback((relPath: string) => {
    setSelectedDrivePaths((prev) => {
      const next = new Set(prev);
      if (next.has(relPath)) next.delete(relPath);
      else next.add(relPath);
      return next;
    });
  }, []);

  const visibleLocalKeys = useMemo(() => visibleLocal.map((it) => it.id), [visibleLocal]);
  const visibleDriveKeys = useMemo(() => visibleDrive.map((it) => it.rel_path), [visibleDrive]);
  const visibleTotalBytes = useMemo(
    () =>
      mode === 'drive'
        ? visibleDrive.reduce((sum, it) => sum + it.size_bytes, 0)
        : visibleLocal.reduce((sum, it) => sum + it.size_bytes, 0),
    [mode, visibleDrive, visibleLocal],
  );
  const visibleSelection = useMemo(
    () =>
      mode === 'drive'
        ? selectionStateForVisible(visibleDriveKeys, selectedDrivePaths)
        : selectionStateForVisible(visibleLocalKeys, selectedIds),
    [mode, selectedDrivePaths, selectedIds, visibleDriveKeys, visibleLocalKeys],
  );

  const onToggleSelectAllVisible = useCallback(() => {
    if (mode === 'drive') {
      setSelectedDrivePaths((prev) => toggleSelectAllVisible(visibleDriveKeys, prev));
    } else {
      setSelectedIds((prev) => toggleSelectAllVisible(visibleLocalKeys, prev));
    }
  }, [mode, visibleDriveKeys, visibleLocalKeys]);

  const scrollToTop = useCallback(() => {
    scrollRef.current?.scrollTo({ y: 0, animated: true });
  }, []);

  const onRefresh = useCallback(async () => {
    if (refreshingRef.current) return;
    refreshingRef.current = true;
    setRefreshing(true);
    try {
      await loadAll(true);
    } finally {
      refreshingRef.current = false;
      if (mountedRef.current) setRefreshing(false);
    }
  }, [loadAll]);

  const onCancelJob = useCallback(async (jobId: string) => {
    setCancellingId(jobId);
    try {
      const res = await cancelSpaceJob(jobId);
      if (res?.job) {
        jobsSeqRef.current += 1;
        setJobs((prev) => mergeEnqueuedJobs(prev, [res.job]));
      }
      void loadDrive(false);
    } catch (e) {
      setError((e as Error).message);
      scrollToTop();
    } finally {
      if (mountedRef.current) setCancellingId(null);
    }
  }, [loadDrive, scrollToTop]);

  const onConfirmAction = useCallback(async () => {
    if (busyAction || !confirmKind) return;
    setBusyAction(true);
    setError(null);
    try {
      if (confirmKind === 'delete_local') {
        const res = await freeSpace(toFreeRequest(localSelection.selected));
        setFeedback(res);
        setConfirmKind(null);
        const succeededIds = new Set(res.results.filter((r) => r.ok).map((r) => r.id));
        setSelectedIds((prev) => {
          const next = new Set<string>();
          for (const id of prev) {
            if (!succeededIds.has(id)) next.add(id);
          }
          return next;
        });
        scrollToTop();
        await loadLocal(activeScope);
      } else if (confirmKind === 'offload') {
        const res = await offloadSpaceItems(toFreeRequest(localSelection.selected));
        setConfirmKind(null);
        setSelectedIds(new Set());
        if (res.jobs.length > 0) {
          for (const j of res.jobs) {
            prevJobStatusRef.current[j.id] = j.status;
          }
          jobsSeqRef.current += 1;
          setJobs((prev) => mergeEnqueuedJobs(prev, res.jobs));
        }
        const n = res.jobs.length;
        const hasErrs = Boolean(res.errors && res.errors.length > 0);
        setNotice({
          tone: hasErrs ? (n > 0 ? 'warn' : 'bad') : 'ok',
          title:
            n > 0
              ? `Queued ${n} item${n === 1 ? '' : 's'} for offload`
              : hasErrs
                ? 'Could not queue offload'
                : 'Already queued for offload',
          body: hasErrs ? res.errors!.map((e) => e.error).join('; ') : undefined,
        });
        scrollToTop();
        void loadDrive(false);
      } else if (confirmKind === 'restore') {
        const res = await restoreDriveItems(driveSelection.selected.map((it) => ({ rel_path: it.rel_path })));
        setConfirmKind(null);
        setSelectedDrivePaths(new Set());
        if (res.jobs.length > 0) {
          for (const j of res.jobs) {
            prevJobStatusRef.current[j.id] = j.status;
          }
          jobsSeqRef.current += 1;
          setJobs((prev) => mergeEnqueuedJobs(prev, res.jobs));
        }
        const n = res.jobs.length;
        setNotice({
          tone: 'ok',
          title: n > 0 ? `Queued ${n} item${n === 1 ? '' : 's'} to restore` : 'Already queued for restore',
        });
        scrollToTop();
        void loadDrive(false);
      } else if (confirmKind === 'delete_drive') {
        const res = await deleteDriveItems(driveSelection.selected.map((it) => ({ rel_path: it.rel_path })));
        setConfirmKind(null);
        setDriveDeleteFeedback(res);
        setSelectedDrivePaths(new Set());
        scrollToTop();
        await loadDrive(true);
      }
    } catch (e) {
      setConfirmKind(null);
      setError((e as Error).message);
      scrollToTop();
    } finally {
      if (mountedRef.current) setBusyAction(false);
    }
  }, [
    activeScope,
    busyAction,
    confirmKind,
    driveSelection.selected,
    loadDrive,
    loadLocal,
    localSelection.selected,
    scrollToTop,
  ]);

  const upstreamErr = formatUpstreamErrors(mode === 'drive' ? driveData?.errors : localData?.errors);
  const failedCount = feedback ? feedback.results.filter((r) => !r.ok).length : 0;
  const hasWarnings = feedback
    ? feedback.warnings.length > 0 || feedback.results.some((r) => r.warnings.length > 0)
    : false;
  const feedbackTone =
    !feedback ? 'ok' : failedCount === feedback.results.length ? 'bad' : failedCount > 0 || hasWarnings ? 'warn' : 'ok';

  const hasSelection = mode === 'drive' ? driveSelection.count > 0 : localSelection.count > 0;
  // Only disable Offload when Drive is explicitly known to be unconfigured; allow attempts while loading or on transient status errors
  const driveExplicitlyUnconfigured = driveData !== null && !driveData.configured;

  return (
    <Screen title="Space" onBack={() => nav.goBack()} scroll={false}>
      <ScrollView
        ref={scrollRef}
        style={{ flex: 1 }}
        contentContainerStyle={{
          paddingHorizontal: screenPad,
          paddingBottom: hasSelection ? space(28) + insets.bottom : space(8) + insets.bottom,
          gap: space(3),
        }}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => void onRefresh()}
            tintColor={t.accent}
            colors={[t.accent]}
            progressBackgroundColor={t.card}
          />
        }
      >
        <Segmented options={MODES} value={mode} onChange={onChangeMode} />

        {error ? <Banner tone="bad" title={error} /> : null}

        {driveError ? (
          <Banner tone="warn" title="Drive status unavailable">
            {driveError}
          </Banner>
        ) : null}

        {notice ? (
          <Banner tone={notice.tone} title={notice.title}>
            <View style={{ gap: 6, marginTop: 2 }}>
              {notice.body ? <Text style={{ color: t.textDim, fontSize: 13 }}>{notice.body}</Text> : null}
              <Button
                label="Dismiss"
                kind="subtle"
                compact
                style={{ alignSelf: 'flex-start' }}
                onPress={() => setNotice(null)}
              />
            </View>
          </Banner>
        ) : null}

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
                    <Icon name={r.ok ? 'check' : 'close-circle'} size={14} color={r.ok ? t.ok : t.bad} />
                    <Text style={{ color: t.text, fontSize: 13, flex: 1 }} numberOfLines={1}>
                      {r.label}
                    </Text>
                    {r.ok ? (
                      <Text style={{ color: t.textDim, fontSize: 12.5, fontVariant: ['tabular-nums'] }}>
                        {bytes(r.freed_bytes, 1)}
                      </Text>
                    ) : null}
                  </View>
                  {r.error ? <Text style={{ color: t.bad, fontSize: 12, paddingLeft: 20 }}>{r.error}</Text> : null}
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

        {driveDeleteFeedback ? (
          <Banner
            tone={driveDeleteFeedback.ok ? 'ok' : 'warn'}
            title={
              driveDeleteFeedback.ok
                ? `Deleted ${driveDeleteFeedback.results.length} item${driveDeleteFeedback.results.length === 1 ? '' : 's'} from Drive`
                : 'Some Drive items could not be deleted'
            }
          >
            <View style={{ gap: 4, marginTop: 2 }}>
              {driveDeleteFeedback.results
                .filter((r) => !r.ok)
                .map((r) => (
                  <Text key={r.rel_path} style={{ color: t.bad, fontSize: 12 }}>
                    {r.rel_path}: {r.error}
                  </Text>
                ))}
              <Button
                label="Dismiss"
                kind="subtle"
                compact
                style={{ alignSelf: 'flex-start', marginTop: 4 }}
                onPress={() => setDriveDeleteFeedback(null)}
              />
            </View>
          </Banner>
        ) : null}

        {mode !== 'drive' ? (localData ? <HddSummaryCard hdd={localData.hdd} /> : <HddSkeleton />) : null}

        <DriveStatusCard drive={driveData} />

        <ActiveTransfersCard jobs={activeJobs} cancellingId={cancellingId} onCancel={(id) => void onCancelJob(id)} />

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
                  style={[st.chip, { backgroundColor: active ? t.accentSoft : t.card }]}
                >
                  <Text style={[st.chipText, { color: active ? t.accent : t.textDim }]}>{f.label}</Text>
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
            <Text style={[st.chipText, { color: t.text }]}>{sort === 'size' ? 'Largest' : 'Title'}</Text>
          </Pressable>
        </View>

        {visibleSelection.visibleCount > 0 ? (
          <View style={st.selectBar}>
            <Pressable
              onPress={onToggleSelectAllVisible}
              hitSlop={6}
              accessibilityRole="checkbox"
              accessibilityState={{
                checked: visibleSelection.allSelected
                  ? true
                  : visibleSelection.someSelected
                    ? 'mixed'
                    : false,
              }}
              accessibilityLabel={visibleSelection.allSelected ? 'Deselect all' : 'Select all'}
              style={({ pressed }) => [st.selectAllBtn, pressed && { opacity: 0.75 }]}
            >
              <Icon
                name={
                  visibleSelection.allSelected
                    ? 'checkbox-marked'
                    : visibleSelection.someSelected
                      ? 'minus-box'
                      : 'checkbox-blank-outline'
                }
                size={20}
                color={visibleSelection.allSelected || visibleSelection.someSelected ? t.accent : t.textFaint}
              />
              <Text
                style={[
                  st.selectAllText,
                  { color: visibleSelection.allSelected ? t.accent : t.text },
                ]}
              >
                {visibleSelection.allSelected ? 'Deselect all' : 'Select all'}
              </Text>
            </Pressable>
            <Text style={[st.metaText, { color: t.textDim }]} numberOfLines={1}>
              {visibleSelection.selectedVisibleCount > 0
                ? `${visibleSelection.selectedVisibleCount} of ${visibleSelection.visibleCount} selected`
                : `${visibleSelection.visibleCount} item${visibleSelection.visibleCount === 1 ? '' : 's'} · ${bytes(visibleTotalBytes, 1)}`}
            </Text>
          </View>
        ) : null}

        {mode === 'drive' ? (
          !driveData ? (
            <ListSkeleton />
          ) : !driveData.configured ? (
            <Empty
              icon="google-drive"
              title="Drive not configured"
              body="Add a 'gdrive' remote in rclone on your PC to offload and stream media."
            />
          ) : visibleDrive.length > 0 ? (
            <List>
              {visibleDrive.map((item, idx) => (
                <DriveItemRow
                  key={item.rel_path}
                  item={item}
                  selected={selectedDrivePaths.has(item.rel_path)}
                  last={idx === visibleDrive.length - 1}
                  onToggle={toggleDriveItem}
                />
              ))}
            </List>
          ) : (
            <Empty
              icon="cloud-check"
              title={
                filter === 'movie'
                  ? 'No offloaded movies'
                  : filter === 'season'
                    ? 'No offloaded TV seasons'
                    : 'Nothing on Drive yet'
              }
            />
          )
        ) : !localData ? (
          <ListSkeleton />
        ) : visibleLocal.length > 0 ? (
          <List>
            {visibleLocal.map((item, idx) => (
              <CandidateRow
                key={item.id}
                item={item}
                selected={selectedIds.has(item.id)}
                last={idx === visibleLocal.length - 1}
                showUnwatchedTag={mode === 'all'}
                onToggle={toggleLocalItem}
              />
            ))}
          </List>
        ) : (
          <Empty
            icon="check-circle"
            title={
              mode === 'all'
                ? 'No local media found'
                : filter === 'movie'
                  ? 'No watched movies'
                  : filter === 'season'
                    ? 'No watched TV seasons'
                    : 'No watched items'
            }
          />
        )}
      </ScrollView>

      {mode !== 'drive' && localSelection.count > 0 ? (
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
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <Text style={[st.stickyCount, { color: t.text }]}>
              {localSelection.count} selected · {bytes(localSelection.totalBytes, 1)}
            </Text>
            <Pressable onPress={() => setSelectedIds(new Set())} hitSlop={8}>
              <Text style={{ color: t.textDim, fontSize: 12.5 }}>Clear</Text>
            </Pressable>
          </View>
          <View style={{ flexDirection: 'row', gap: space(2) }}>
            <Button
              label="Offload to Drive"
              icon="cloud-upload"
              kind="primary"
              disabled={driveExplicitlyUnconfigured}
              style={{ flex: 1 }}
              onPress={() => setConfirmKind('offload')}
            />
            <Button
              label="Delete"
              icon="delete"
              kind="danger"
              onPress={() => setConfirmKind('delete_local')}
            />
          </View>
        </View>
      ) : null}

      {mode === 'drive' && driveSelection.count > 0 ? (
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
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <Text style={[st.stickyCount, { color: t.text }]}>
              {driveSelection.count} selected · {bytes(driveSelection.totalBytes, 1)}
            </Text>
            <Pressable onPress={() => setSelectedDrivePaths(new Set())} hitSlop={8}>
              <Text style={{ color: t.textDim, fontSize: 12.5 }}>Clear</Text>
            </Pressable>
          </View>
          <View style={{ flexDirection: 'row', gap: space(2) }}>
            <Button
              label="Restore to HDD"
              icon="cloud-download"
              kind="primary"
              style={{ flex: 1 }}
              onPress={() => setConfirmKind('restore')}
            />
            <Button
              label="Delete"
              icon="delete"
              kind="danger"
              onPress={() => setConfirmKind('delete_drive')}
            />
          </View>
        </View>
      ) : null}

      <Modal
        visible={confirmKind !== null}
        transparent
        animationType="fade"
        onRequestClose={() => !busyAction && setConfirmKind(null)}
      >
        <View style={[st.modalBackdrop, { backgroundColor: t.overlay }]}>
          <View style={[st.modalCard, { backgroundColor: t.card }]}>
            {confirmKind === 'delete_local' || confirmKind === 'offload' ? (
              <>
                <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
                  <Text style={[st.modalTitle, { color: t.text }]}>
                    {confirmKind === 'offload'
                      ? `Offload ${bytes(localSelection.totalBytes, 1)}?`
                      : `Delete ${bytes(localSelection.totalBytes, 1)}?`}
                  </Text>
                  <Pill
                    label={`${localSelection.count} item${localSelection.count === 1 ? '' : 's'}`}
                    tone="dim"
                  />
                </View>

                {confirmKind === 'offload' ? (
                  <Text style={{ color: t.textDim, fontSize: 13, lineHeight: 18 }}>
                    Uploads to Google Drive, verifies MD5 hashes, then removes the local HDD copy. Items stay playable in
                    Jellyfin via the VFS mount.
                  </Text>
                ) : null}

                {confirmKind === 'delete_local' && localSelection.unwatched.length > 0 ? (
                  <View style={[st.unwatchedBox, { backgroundColor: t.warnSoft }]}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                      <Icon name="alert" size={16} color={t.warn} />
                      <Text style={{ color: t.warn, fontSize: 13, fontWeight: '600' }}>Not watched by others</Text>
                    </View>
                    {localSelection.unwatched.map((it) => (
                      <Text key={it.id} style={{ color: t.text, fontSize: 12.5 }} numberOfLines={2}>
                        • {it.title}
                        {it.subtitle ? ` · ${it.subtitle}` : ''} ({it.unwatched_users.join(', ')})
                      </Text>
                    ))}
                  </View>
                ) : null}

                <ScrollView style={{ maxHeight: 220 }} contentContainerStyle={{ gap: 6 }}>
                  {localSelection.selected.map((it) => (
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
                    disabled={busyAction}
                    onPress={() => setConfirmKind(null)}
                  />
                  <Button
                    label={confirmKind === 'offload' ? 'Offload' : 'Delete'}
                    icon={confirmKind === 'offload' ? 'cloud-upload' : 'delete'}
                    kind={confirmKind === 'offload' ? 'primary' : 'danger'}
                    style={{ flex: 1 }}
                    busy={busyAction}
                    onPress={() => void onConfirmAction()}
                  />
                </View>
              </>
            ) : confirmKind === 'restore' || confirmKind === 'delete_drive' ? (
              <>
                <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space(2) }}>
                  <Text style={[st.modalTitle, { color: t.text }]}>
                    {confirmKind === 'restore'
                      ? `Restore ${bytes(driveSelection.totalBytes, 1)}?`
                      : `Delete ${bytes(driveSelection.totalBytes, 1)} from Drive?`}
                  </Text>
                  <Pill
                    label={`${driveSelection.count} item${driveSelection.count === 1 ? '' : 's'}`}
                    tone="dim"
                  />
                </View>

                <Text style={{ color: t.textDim, fontSize: 13, lineHeight: 18 }}>
                  {confirmKind === 'restore'
                    ? 'Copies back to the local HDD, verifies MD5 hashes, then removes the Google Drive copy.'
                    : 'Permanently removes these items from Google Drive. They will no longer play in Jellyfin.'}
                </Text>

                <ScrollView style={{ maxHeight: 220 }} contentContainerStyle={{ gap: 6 }}>
                  {driveSelection.selected.map((it) => (
                    <View
                      key={it.rel_path}
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
                    disabled={busyAction}
                    onPress={() => setConfirmKind(null)}
                  />
                  <Button
                    label={confirmKind === 'restore' ? 'Restore' : 'Delete from Drive'}
                    icon={confirmKind === 'restore' ? 'cloud-download' : 'delete'}
                    kind={confirmKind === 'restore' ? 'primary' : 'danger'}
                    style={{ flex: 1 }}
                    busy={busyAction}
                    onPress={() => void onConfirmAction()}
                  />
                </View>
              </>
            ) : null}
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

const st = StyleSheet.create({
  skel: { borderRadius: 6 },
  freeHeadline: { fontSize: 20, fontWeight: '600', fontVariant: ['tabular-nums'] },
  driveTitle: { fontSize: 15, fontWeight: '600' },
  totalText: { fontSize: 13, fontVariant: ['tabular-nums'] },
  metaText: { fontSize: 12.5, fontVariant: ['tabular-nums'] },
  controlsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
    minHeight: 34,
  },
  selectBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
    paddingHorizontal: space(1),
    minHeight: 32,
  },
  selectAllBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingVertical: 4,
  },
  selectAllText: { fontSize: 13, fontWeight: '500' },
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
    gap: space(2.5),
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
