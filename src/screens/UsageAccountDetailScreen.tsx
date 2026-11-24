import { useNavigation, useRoute } from '@react-navigation/native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Banner, Card, Empty, Icon, KV, Pill, Screen, Section, Tone } from '../components/ui';
import { BrandIcon, UsageProgressBar, usageSegmentColors } from '../components/UsageSection';
import { AgentUsageResponse, getAgentUsage } from '../lib/api';
import { cacheGet, cacheSet } from '../lib/cache';
import {
  AccountWindowDetail,
  CACHE_AGENT_USAGE,
  formatUpdatedAgo,
  getAccountDetailSummary,
  PaceStatus,
  UsageWindowKey,
} from '../lib/usageSummary';
import { useNow } from '../lib/useNow';
import { radius, space, useTheme } from '../theme';

export type UsageAccountDetailParams = {
  accountId: string;
  initialWindow?: UsageWindowKey;
  usageSnapshot?: AgentUsageResponse | null;
};

const STALE_REPOLL_MS = 2500;
const STALE_REPOLL_MAX = 4;

function paceTone(status: PaceStatus): Tone {
  if (status === 'Under pace') return 'ok';
  if (status === 'Ahead of pace') return 'warn';
  return 'dim';
}

function formatEvenPaceText(detail: AccountWindowDetail): string | null {
  if (detail.evenPaceExpectedLeftPct == null) return null;
  const base = `Even pace: ${detail.evenPaceExpectedLeftPct}% left by now`;
  if (detail.paceDeltaPct == null || (detail.usedPct ?? 0) <= 0) return base;
  if (detail.paceDeltaPct > 0) return `${base} (+${detail.paceDeltaPct}% buffer)`;
  if (detail.paceDeltaPct < 0) return `${base} (${detail.paceDeltaPct}% ahead of pace)`;
  return `${base} (right on pace)`;
}

function DetailLine({
  icon,
  text,
  suffix,
  color,
  iconColor,
}: {
  icon: string;
  text: string;
  suffix?: string | null;
  color?: string;
  iconColor?: string;
}) {
  const t = useTheme();
  return (
    <View style={st.detailLine}>
      <View style={st.detailIconWrap}>
        <Icon name={icon} size={14} color={iconColor ?? t.textFaint} />
      </View>
      <Text style={[st.detailText, { color: color ?? t.textDim }]}>
        {text}
        {suffix ? <Text style={{ color: t.textFaint }}>{suffix}</Text> : null}
      </Text>
    </View>
  );
}

function WindowDetailCard({ detail }: { detail: AccountWindowDetail }) {
  const t = useTheme();
  const colors = usageSegmentColors(t, detail.state, detail.remainingPct);
  const evenPaceText = formatEvenPaceText(detail);

  return (
    <Card style={{ gap: space(3) }}>
      <View style={st.cardHeaderRow}>
        <Text style={[st.cardHeaderTitle, { color: t.text }]}>{detail.headerTitle}</Text>
        {detail.paceStatus ? (
          <Pill label={detail.paceStatus} tone={paceTone(detail.paceStatus)} />
        ) : null}
      </View>

      <View style={st.heroRow}>
        {detail.state === 'ok' && detail.remainingPct != null ? (
          <>
            <View style={st.heroLeft}>
              <Text style={[st.heroPct, { color: colors.pctFg }]}>
                {Math.round(detail.remainingPct)}%
              </Text>
              <Text style={[st.heroUnit, { color: t.textDim }]}>left</Text>
            </View>
            <Text style={[st.usedText, { color: t.textFaint }]}>
              {Math.round(detail.usedPct ?? 0)}% used
            </Text>
          </>
        ) : detail.state === 'signed_out' ? (
          <Text style={[st.stateText, { color: t.textFaint }]}>Not signed in</Text>
        ) : detail.state === 'error' ? (
          <Text style={[st.stateText, { color: t.bad }]}>Unavailable</Text>
        ) : (
          <Text style={[st.stateText, { color: t.textFaint }]}>—</Text>
        )}
      </View>

      <UsageProgressBar
        state={detail.state}
        remainingPct={detail.remainingPct}
        expectedRemainingPct={detail.expectedRemainingPct}
        height={24}
      />

      {detail.state === 'ok' ? (
        <View style={[st.detailsBlock, { borderTopColor: t.border }]}>
          {detail.resetDateTimeLabel ? (
            <DetailLine
              icon="clock"
              text={`Resets ${detail.resetDateTimeLabel}`}
              suffix={detail.countdownLabel ? ` (in ${detail.countdownLabel})` : null}
              color={t.text}
            />
          ) : detail.isFullIdle ? (
            <DetailLine icon="check-circle" text="Quota full · no active cooldown" color={t.textDim} />
          ) : (
            <DetailLine icon="clock" text="Reset time unavailable" color={t.textFaint} />
          )}

          {evenPaceText ? <DetailLine icon="speedometer" text={evenPaceText} /> : null}

          {detail.poolRestorePct != null ? (
            <DetailLine
              icon="refresh"
              text={`Restores +${detail.poolRestorePct}% of the ${detail.activePoolSize}-account pool on reset`}
            />
          ) : null}

          {detail.poolSharePct != null ? (
            <DetailLine
              icon="chart-donut"
              text={`Contributes ${detail.poolSharePct}% of current pool`}
            />
          ) : null}

          {detail.projection ? (
            <DetailLine
              icon={
                detail.projection.kind === 'exhausted'
                  ? 'alert-circle'
                  : detail.projection.kind === 'runs_out'
                    ? 'alert'
                    : 'trending-up'
              }
              text={detail.projection.label}
              color={
                detail.projection.kind === 'exhausted'
                  ? t.bad
                  : detail.projection.kind === 'runs_out'
                    ? t.warn
                    : t.textDim
              }
              iconColor={
                detail.projection.kind === 'exhausted'
                  ? t.bad
                  : detail.projection.kind === 'runs_out'
                    ? t.warn
                    : t.textFaint
              }
            />
          ) : null}
        </View>
      ) : null}
    </Card>
  );
}

export default function UsageAccountDetailScreen() {
  const t = useTheme();
  const nav = useNavigation<any>();
  const route = useRoute<any>();
  const params = (route.params ?? {}) as UsageAccountDetailParams;
  const accountId = params.accountId ?? '';
  const initialWindow: UsageWindowKey = params.initialWindow === 'weekly' ? 'weekly' : 'five_hour';

  const nowMs = useNow(5000);
  const mounted = useRef(true);
  const staleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const staleAttempts = useRef(0);

  const [usage, setUsage] = useState<AgentUsageResponse | null>(
    () => cacheGet<AgentUsageResponse>(CACHE_AGENT_USAGE) ?? params.usageSnapshot ?? null,
  );
  const [refreshing, setRefreshing] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const clearStaleTimer = useCallback(() => {
    if (staleTimer.current) {
      clearTimeout(staleTimer.current);
      staleTimer.current = null;
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      clearStaleTimer();
    };
  }, [clearStaleTimer]);

  const loadUsage = useCallback(
    async (force: boolean) => {
      const run = async (doForce: boolean) => {
        clearStaleTimer();
        if (doForce) staleAttempts.current = 0;
        try {
          const next = await getAgentUsage(doForce);
          if (!mounted.current) return;
          cacheSet(CACHE_AGENT_USAGE, next);
          setUsage(next);
          setErr(null);
          if (next.stale && staleAttempts.current < STALE_REPOLL_MAX) {
            staleAttempts.current += 1;
            staleTimer.current = setTimeout(() => {
              if (mounted.current) void run(false);
            }, STALE_REPOLL_MS);
          } else if (!next.stale) {
            staleAttempts.current = 0;
          }
        } catch (e) {
          if (!mounted.current) return;
          setErr((e as Error).message || 'Failed to refresh usage');
        }
      };
      await run(force);
    },
    [clearStaleTimer],
  );

  useEffect(() => {
    if (!usage) {
      void loadUsage(false);
    }
  }, [usage, loadUsage]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await loadUsage(true);
    } finally {
      if (mounted.current) setRefreshing(false);
    }
  }, [loadUsage]);

  const summary = useMemo(
    () => (usage?.accounts ? getAccountDetailSummary(usage.accounts, accountId, new Date(nowMs)) : null),
    [usage, accountId, nowMs],
  );

  const windows: AccountWindowDetail[] = useMemo(() => {
    if (!summary) return [];
    return initialWindow === 'weekly'
      ? [summary.weekly, summary.fiveHour]
      : [summary.fiveHour, summary.weekly];
  }, [summary, initialWindow]);

  const account = summary?.account ?? null;
  const statusInfo: { label: string; tone: Tone; color: string } = !account
    ? { label: 'Unknown', tone: 'faint', color: t.textFaint }
    : !account.signed_in
      ? { label: 'Not signed in', tone: 'faint', color: t.textFaint }
      : account.error
        ? { label: 'Error', tone: 'bad', color: t.bad }
        : { label: 'Signed in', tone: 'ok', color: t.ok };

  return (
    <Screen
      title="Account"
      onBack={() => nav.goBack()}
      onRefresh={onRefresh}
      refreshing={refreshing}
      right={
        usage?.stale ? (
          <Text style={{ color: t.textFaint, fontSize: 12, paddingBottom: 6 }}>updating…</Text>
        ) : undefined
      }
    >
      {err ? (
        <Banner tone="bad" title="Refresh failed">
          {err}
        </Banner>
      ) : null}

      {!summary || !account ? (
        <Card>
          <Empty
            icon="account-question"
            title="Account not found"
            body="Pull down to refresh usage data from your PC."
          />
        </Card>
      ) : (
        <>
          <Card style={{ gap: space(2.5) }}>
            <View style={st.heroAccountRow}>
              <BrandIcon brand={summary.providerKey} size={24} color={t.text} />
              <Text style={[st.accountLabel, { color: t.text }]} numberOfLines={1}>
                {account.label}
              </Text>
              {summary.poolSize > 1 ? (
                <View style={[st.poolBadge, { backgroundColor: t.cardAlt }]}>
                  <Text style={[st.poolBadgeText, { color: t.textDim }]}>
                    #{summary.poolIndex} of {summary.poolSize}
                  </Text>
                </View>
              ) : null}
            </View>

            <View style={{ gap: 4 }}>
              {account.email ? (
                <Text selectable style={[st.emailText, { color: t.text }]}>
                  {account.email}
                </Text>
              ) : null}
              <Text style={[st.providerSub, { color: t.textDim }]}>{summary.providerSubtitle}</Text>
            </View>

            <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2), marginTop: 2 }}>
              <Pill label={statusInfo.label} tone={statusInfo.tone} dot />
            </View>
          </Card>

          {account.error ? (
            <Banner tone="bad" title="Account error">
              {account.error}
            </Banner>
          ) : null}

          {windows.map((win) => (
            <WindowDetailCard key={win.windowKey} detail={win} />
          ))}

          <Section label="Account info">
            <Card style={{ gap: 2 }}>
              <KV
                k="Status"
                v={
                  <Text style={{ color: statusInfo.color, fontSize: 13.5, fontWeight: '500' }}>
                    {statusInfo.label}
                  </Text>
                }
              />
              <KV k="Email" v={account.email || '—'} />
              <KV k="Instance ID" v={account.id} mono />
              <KV k="Last updated" v={formatUpdatedAgo(usage?.ts, nowMs)} />
            </Card>
          </Section>
        </>
      )}
    </Screen>
  );
}

const st = StyleSheet.create({
  heroAccountRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2.5),
  },
  accountLabel: {
    flex: 1,
    fontSize: 20,
    fontWeight: '600',
    letterSpacing: -0.3,
  },
  poolBadge: {
    paddingHorizontal: 9,
    paddingVertical: 4,
    borderRadius: radius.pill,
  },
  poolBadgeText: {
    fontSize: 12,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
  },
  emailText: {
    fontSize: 14,
    fontWeight: '400',
  },
  providerSub: {
    fontSize: 12.5,
    fontWeight: '400',
  },
  cardHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
  },
  cardHeaderTitle: {
    fontSize: 14.5,
    fontWeight: '500',
  },
  heroRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    gap: space(2),
  },
  heroLeft: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: 6,
  },
  heroPct: {
    fontSize: 32,
    fontWeight: '600',
    letterSpacing: -0.6,
    fontVariant: ['tabular-nums'],
  },
  heroUnit: {
    fontSize: 14.5,
    fontWeight: '400',
  },
  usedText: {
    fontSize: 13,
    fontWeight: '400',
    fontVariant: ['tabular-nums'],
  },
  stateText: {
    fontSize: 16,
    fontWeight: '500',
  },
  detailsBlock: {
    paddingTop: space(2.5),
    borderTopWidth: StyleSheet.hairlineWidth,
    gap: 8,
  },
  detailLine: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
  },
  detailIconWrap: {
    width: 16,
    paddingTop: 2,
    alignItems: 'center',
  },
  detailText: {
    flex: 1,
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '400',
    fontVariant: ['tabular-nums'],
  },
});
