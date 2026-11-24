import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { Card, Icon } from './ui';
import type { AgentUsageAccount } from '../lib/api';
import {
  AccountWindowState,
  groupAccountsByProvider,
  summarizeWindowGroup,
  UsageProviderKey,
  UsageWindowKey,
} from '../lib/usageSummary';
import { radius, space, Theme, useTheme } from '../theme';

const ANTHROPIC_ICON = require('../../assets/brand-anthropic.png');
const GEMINI_ICON = require('../../assets/brand-gemini.png');

// Fixed stripe offsets (in px) for the pure-RN diagonal hatch on the used portion of a segment.
const MULTI_HATCH_OFFSETS = Array.from({ length: 16 }, (_, i) => i * 8 - 12);
const SINGLE_HATCH_OFFSETS = Array.from({ length: 54 }, (_, i) => i * 8 - 12);

export function BrandIcon({ brand, size = 18, color }: { brand: UsageProviderKey; size?: number; color: string }) {
  if (brand === 'opencode') {
    return <Icon name="code-tags" size={size} color={color} />;
  }
  return (
    <Image
      source={brand === 'claude' ? ANTHROPIC_ICON : GEMINI_ICON}
      style={{ width: size, height: size, tintColor: color }}
      resizeMode="contain"
    />
  );
}

export function usageSegmentColors(
  t: Theme,
  state: AccountWindowState['state'],
  remainingPct: number | null,
) {
  if (state !== 'ok' || remainingPct == null) {
    return {
      fill: 'transparent',
      badgeBg: t.cardAlt,
      badgeFg: t.textFaint,
      pctFg: t.textFaint,
    };
  }
  if (remainingPct < 15) {
    return {
      fill: t.dark ? 'rgba(242, 104, 90, 0.28)' : 'rgba(210, 57, 43, 0.22)',
      badgeBg: t.badSoft,
      badgeFg: t.bad,
      pctFg: t.bad,
    };
  }
  if (remainingPct < 40) {
    return {
      fill: t.dark ? 'rgba(242, 181, 68, 0.26)' : 'rgba(184, 120, 0, 0.20)',
      badgeBg: t.warnSoft,
      badgeFg: t.warn,
      pctFg: t.warn,
    };
  }
  return {
    fill: t.dark ? 'rgba(61, 220, 151, 0.24)' : 'rgba(21, 154, 99, 0.20)',
    badgeBg: t.okSoft,
    badgeFg: t.ok,
    pctFg: t.text,
  };
}

function segmentColors(t: Theme, row: AccountWindowState) {
  return usageSegmentColors(t, row.state, row.remainingPct);
}

export function UsageProgressBar({
  state,
  remainingPct,
  expectedRemainingPct,
  height = 24,
}: {
  state: AccountWindowState['state'];
  remainingPct: number | null;
  expectedRemainingPct: number | null;
  height?: number;
}) {
  const t = useTheme();
  const trackBg = t.dark ? '#1B1B1B' : '#EAEAEA';
  const hatchColor = t.dark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(0, 0, 0, 0.08)';
  const colors = usageSegmentColors(t, state, remainingPct);
  const fillPct = state === 'ok' && remainingPct != null ? remainingPct : 0;
  const showHatch = state === 'ok' && fillPct < 100;

  return (
    <View style={[st.singleBar, { height, backgroundColor: trackBg }]}>
      {showHatch ? (
        <View pointerEvents="none" style={[st.hatchClip, { width: `${100 - fillPct}%` }]}>
          {SINGLE_HATCH_OFFSETS.map((left) => (
            <View key={left} style={[st.hatchStripe, { left, backgroundColor: hatchColor }]} />
          ))}
        </View>
      ) : null}

      {fillPct > 0 ? (
        <View
          pointerEvents="none"
          style={[st.segFill, { width: `${fillPct}%`, backgroundColor: colors.fill }]}
        />
      ) : null}

      {expectedRemainingPct != null ? (
        <View
          pointerEvents="none"
          style={[
            st.paceTick,
            {
              left: `${expectedRemainingPct}%`,
              backgroundColor: t.text,
            },
          ]}
        />
      ) : null}
    </View>
  );
}

function SegmentedBar({
  rows,
  onSelect,
}: {
  rows: AccountWindowState[];
  onSelect?: (account: AgentUsageAccount) => void;
}) {
  const t = useTheme();
  const trackBg = t.dark ? '#1B1B1B' : '#EAEAEA';
  const hatchColor = t.dark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(0, 0, 0, 0.08)';
  const offsets = rows.length === 1 ? SINGLE_HATCH_OFFSETS : MULTI_HATCH_OFFSETS;

  return (
    <View style={st.segRow}>
      {rows.map((row) => {
        const colors = segmentColors(t, row);
        const fillPct = row.state === 'ok' && row.remainingPct != null ? row.remainingPct : 0;
        const showHatch = row.state === 'ok' && fillPct < 100;

        const inner = (
          <>
            {showHatch ? (
              <View
                pointerEvents="none"
                style={[st.hatchClip, { width: `${100 - fillPct}%` }]}
              >
                {offsets.map((left) => (
                  <View
                    key={left}
                    style={[st.hatchStripe, { left, backgroundColor: hatchColor }]}
                  />
                ))}
              </View>
            ) : null}

            {fillPct > 0 ? (
              <View
                pointerEvents="none"
                style={[st.segFill, { width: `${fillPct}%`, backgroundColor: colors.fill }]}
              />
            ) : null}

            {row.expectedRemainingPct != null ? (
              <View
                pointerEvents="none"
                style={[
                  st.paceTick,
                  {
                    left: `${row.expectedRemainingPct}%`,
                    backgroundColor: t.text,
                  },
                ]}
              />
            ) : null}

            <Text
              style={[
                st.segNum,
                { color: row.state === 'ok' ? t.text : t.textFaint },
              ]}
            >
              {row.index}
            </Text>
          </>
        );

        if (!onSelect) {
          return (
            <View key={row.account.id} style={[st.segment, { backgroundColor: trackBg }]}>
              {inner}
            </View>
          );
        }

        return (
          <Pressable
            key={row.account.id}
            onPress={() => onSelect(row.account)}
            accessibilityRole="button"
            accessibilityLabel={`${row.account.label}, ${
              row.state === 'ok' && row.remainingPct != null ? `${Math.round(row.remainingPct)}% left` : row.state
            }`}
            android_ripple={{ color: t.ripple, borderless: false }}
            style={({ pressed }) => [
              st.segment,
              { backgroundColor: trackBg },
              pressed && { opacity: 0.8 },
            ]}
          >
            {inner}
          </Pressable>
        );
      })}
    </View>
  );
}

function WindowUsageCard({
  accounts,
  windowKey,
  onSelectAccount,
}: {
  accounts: AgentUsageAccount[];
  windowKey: UsageWindowKey;
  onSelectAccount?: (account: AgentUsageAccount, windowKey: UsageWindowKey) => void;
}) {
  const t = useTheme();
  const summary = summarizeWindowGroup(accounts, windowKey);
  const errorRows = windowKey === 'five_hour' ? summary.rows.filter((r) => r.state === 'error' && r.account.error) : [];
  const handleSelect = onSelectAccount ? (acc: AgentUsageAccount) => onSelectAccount(acc, windowKey) : undefined;

  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ gap: 4 }}>
        <View style={st.headerRow}>
          <Text style={{ color: t.text, fontSize: 14, fontWeight: '500' }}>{summary.title}</Text>
          {summary.paceStatus ? (
            <Text style={{ color: t.textFaint, fontSize: 12.5, fontWeight: '500' }}>
              {summary.paceStatus}
            </Text>
          ) : null}
        </View>

        <View style={st.heroRow}>
          <Text style={[st.heroPct, { color: t.text }]}>
            {summary.aggregateRemainingPct != null ? `${summary.aggregateRemainingPct}%` : '—'}
          </Text>
          {summary.aggregateRemainingPct != null ? (
            <Text style={{ color: t.textDim, fontSize: 13.5, fontWeight: '400' }}>left</Text>
          ) : null}
        </View>

        {summary.replenishment ? (
          <View style={st.replenishRow}>
            <Icon name="refresh" size={12.5} color={t.textFaint} />
            <Text style={[st.replenishText, { color: t.textFaint }]}>
              +{summary.replenishment.gainPct}% in {summary.replenishment.durationLabel}
            </Text>
          </View>
        ) : null}
      </View>

      <SegmentedBar rows={summary.rows} onSelect={handleSelect} />

      <View style={{ gap: handleSelect ? 2 : 9 }}>
        {summary.rows.map((row) => {
          const colors = segmentColors(t, row);
          const rowContent = (
            <>
              <View style={[st.badge, { backgroundColor: colors.badgeBg }]}>
                <Text style={[st.badgeText, { color: colors.badgeFg }]}>{row.index}</Text>
              </View>
              <Text
                style={[
                  st.accLabel,
                  { color: row.state === 'ok' ? t.text : t.textFaint },
                ]}
                numberOfLines={1}
              >
                {row.account.label}
              </Text>
              <View style={st.accTrailing}>
                {row.state === 'signed_out' ? (
                  <Text style={{ color: t.textFaint, fontSize: 12.5 }}>Not signed in</Text>
                ) : row.state === 'error' ? (
                  <Text style={{ color: t.bad, fontSize: 12.5, fontWeight: '500' }}>Error</Text>
                ) : row.state === 'missing' || row.remainingPct == null ? (
                  <Text style={[st.accPct, { color: t.textFaint }]}>—</Text>
                ) : (
                  <>
                    <Text style={[st.accPct, { color: colors.pctFg }]}>
                      {Math.round(row.remainingPct)}%
                    </Text>
                    {row.countdownLabel ? (
                      <View style={st.countdownRow}>
                        <Icon name="refresh" size={12} color={t.textFaint} />
                        <Text style={[st.countdownText, { color: t.textFaint }]}>
                          {row.countdownLabel}
                        </Text>
                      </View>
                    ) : null}
                  </>
                )}
                {handleSelect ? <Icon name="chevron-right" size={16} color={t.textFaint} /> : null}
              </View>
            </>
          );

          if (!handleSelect) {
            return (
              <View key={row.account.id} style={st.accRow}>
                {rowContent}
              </View>
            );
          }

          return (
            <Pressable
              key={row.account.id}
              onPress={() => handleSelect(row.account)}
              accessibilityRole="button"
              accessibilityLabel={`${row.account.label} details`}
              android_ripple={{ color: t.ripple, borderless: false }}
              style={({ pressed }) => [st.accRowPressable, pressed && { opacity: 0.78 }]}
            >
              {rowContent}
            </Pressable>
          );
        })}
      </View>

      {errorRows.length > 0 ? (
        <View style={{ gap: 4 }}>
          {errorRows.map((r) => (
            <Text key={r.account.id} style={{ color: t.bad, fontSize: 12, lineHeight: 16 }} numberOfLines={2}>
              {r.account.label}: {r.account.error}
            </Text>
          ))}
        </View>
      ) : null}
    </Card>
  );
}

function shortModelName(model: string): string {
  const idx = model.indexOf('/');
  return idx >= 0 ? model.slice(idx + 1) : model;
}

function OpenCodeUsageCard({ acc }: { acc: AgentUsageAccount }) {
  const t = useTheme();
  const ds = acc.deepseek;
  const zen = acc.zen;

  return (
    <Card style={{ gap: space(3) }}>
      {acc.error ? (
        <Text style={{ color: t.bad, fontSize: 12.5, lineHeight: 18 }}>{acc.error}</Text>
      ) : (
        <View style={{ gap: space(2.5) }}>
          <View style={st.ocRow}>
            <Text style={{ color: t.textDim, fontSize: 13.5 }}>DeepSeek balance</Text>
            {ds?.error ? (
              <Text style={{ color: t.bad, fontSize: 12.5, flexShrink: 1, textAlign: 'right' }} numberOfLines={1}>
                {ds.error}
              </Text>
            ) : ds?.formatted ? (
              <Text style={{ color: t.text, fontSize: 14, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
                {ds.formatted} left
              </Text>
            ) : (
              <Text style={{ color: t.textFaint, fontSize: 13 }}>—</Text>
            )}
          </View>

          {zen?.status === 'ok' ? (
            <View style={[st.ocZenBox, { backgroundColor: t.cardAlt }]}>
              <View style={st.ocRow}>
                <Text style={{ color: t.textDim, fontSize: 12.5, fontWeight: '500' }}>
                  Zen ({zen.days ?? 7}d)
                </Text>
                <Text style={{ color: t.text, fontSize: 12.5, fontWeight: '600', fontVariant: ['tabular-nums'] }}>
                  {(zen.messages ?? 0).toLocaleString()} msgs · {zen.total_cost ?? '$0.00'}
                </Text>
              </View>
              {zen.models?.length ? (
                <View style={{ gap: 4, marginTop: 2 }}>
                  {zen.models.map((m) => (
                    <View key={m.model} style={st.ocRow}>
                      <Text style={{ color: t.textFaint, fontSize: 12, flex: 1 }} numberOfLines={1}>
                        {shortModelName(m.model)}
                      </Text>
                      <Text style={{ color: t.textDim, fontSize: 12, fontVariant: ['tabular-nums'] }}>
                        {m.messages.toLocaleString()} msgs
                      </Text>
                    </View>
                  ))}
                </View>
              ) : null}
            </View>
          ) : zen?.error ? (
            <Text style={{ color: t.textFaint, fontSize: 12 }}>Zen: {zen.error}</Text>
          ) : null}
        </View>
      )}
    </Card>
  );
}

export function GroupedUsageSection({
  accounts,
  onSelectAccount,
}: {
  accounts: AgentUsageAccount[];
  onSelectAccount?: (account: AgentUsageAccount, windowKey: UsageWindowKey) => void;
}) {
  const t = useTheme();
  const groups = groupAccountsByProvider(accounts);

  return (
    <View style={{ gap: space(4) }}>
      {groups.map((group) => (
        <View key={group.key} style={{ gap: space(2.5) }}>
          <View style={st.providerHeader}>
            <BrandIcon brand={group.key} size={17} color={t.textDim} />
            <Text style={[st.providerName, { color: t.text }]}>{group.name}</Text>
          </View>

          {group.key === 'opencode' ? (
            <View style={{ gap: space(2.5) }}>
              {group.accounts.map((acc) => (
                <OpenCodeUsageCard key={acc.id} acc={acc} />
              ))}
            </View>
          ) : (
            <View style={{ gap: space(2.5) }}>
              <WindowUsageCard
                accounts={group.accounts}
                windowKey="five_hour"
                onSelectAccount={onSelectAccount}
              />
              <WindowUsageCard
                accounts={group.accounts}
                windowKey="weekly"
                onSelectAccount={onSelectAccount}
              />
            </View>
          )}
        </View>
      ))}
    </View>
  );
}

export function UsageSkeletonCard({ segments = 4 }: { segments?: number }) {
  const t = useTheme();
  return (
    <Card style={{ gap: space(3) }}>
      <View style={{ gap: 6 }}>
        <View style={st.headerRow}>
          <View style={[st.skelLine, { width: 64, height: 14, backgroundColor: t.cardAlt }]} />
          <View style={[st.skelLine, { width: 72, height: 12, backgroundColor: t.cardAlt }]} />
        </View>
        <View style={[st.skelLine, { width: 96, height: 28, backgroundColor: t.cardAlt }]} />
      </View>

      <View style={st.segRow}>
        {Array.from({ length: segments }, (_, i) => (
          <View key={i} style={[st.segment, { backgroundColor: t.cardAlt }]} />
        ))}
      </View>

      <View style={{ gap: 9 }}>
        {Array.from({ length: segments }, (_, i) => (
          <View key={i} style={st.accRow}>
            <View style={[st.badge, { backgroundColor: t.cardAlt }]} />
            <View style={[st.skelLine, { flex: 1, maxWidth: 132, height: 13, backgroundColor: t.cardAlt }]} />
            <View style={{ flex: 1 }} />
            <View style={[st.skelLine, { width: 64, height: 13, backgroundColor: t.cardAlt }]} />
          </View>
        ))}
      </View>
    </Card>
  );
}

const st = StyleSheet.create({
  providerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space(2),
    paddingHorizontal: 2,
  },
  providerName: {
    fontSize: 15,
    fontWeight: '600',
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
  },
  heroRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: 6,
  },
  heroPct: {
    fontSize: 28,
    fontWeight: '600',
    letterSpacing: -0.5,
    fontVariant: ['tabular-nums'],
  },
  replenishRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    marginTop: 2,
  },
  replenishText: {
    fontSize: 12,
    fontWeight: '400',
    fontVariant: ['tabular-nums'],
  },
  segRow: {
    flexDirection: 'row',
    gap: 6,
  },
  singleBar: {
    width: '100%',
    borderRadius: 7,
    overflow: 'hidden',
  },
  segment: {
    flex: 1,
    height: 28,
    borderRadius: 7,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  segFill: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
  },
  hatchClip: {
    position: 'absolute',
    right: 0,
    top: 0,
    bottom: 0,
    overflow: 'hidden',
  },
  hatchStripe: {
    position: 'absolute',
    top: -12,
    width: 1.5,
    height: 52,
    transform: [{ rotate: '35deg' }],
  },
  paceTick: {
    position: 'absolute',
    top: 2,
    bottom: 2,
    width: 2,
    marginLeft: -1,
    borderRadius: 1,
    opacity: 0.9,
  },
  segNum: {
    fontSize: 12,
    fontWeight: '600',
    fontVariant: ['tabular-nums'],
  },
  accRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  accRowPressable: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginHorizontal: -space(2),
    paddingHorizontal: space(2),
    paddingVertical: 6,
    borderRadius: 10,
    overflow: 'hidden',
  },
  badge: {
    width: 20,
    height: 20,
    borderRadius: 5,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: {
    fontSize: 11.5,
    fontWeight: '600',
    fontVariant: ['tabular-nums'],
  },
  accLabel: {
    flex: 1,
    fontSize: 13.5,
    fontWeight: '400',
  },
  accTrailing: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  accPct: {
    fontSize: 13.5,
    fontWeight: '600',
    fontVariant: ['tabular-nums'],
  },
  countdownRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
  },
  countdownText: {
    fontSize: 12,
    fontWeight: '400',
    fontVariant: ['tabular-nums'],
  },
  ocRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space(2),
  },
  ocZenBox: {
    borderRadius: radius.sm,
    padding: space(3),
    gap: 6,
  },
  skelLine: {
    borderRadius: 4,
  },
});
