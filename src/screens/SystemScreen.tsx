import { useIsFocused, useRoute } from '@react-navigation/native';
import { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Screen, Segmented, StatusDot, Tone } from '../components/ui';
import { screenPad, space, useTheme } from '../theme';
import HealthScreen from './HealthScreen';
import VitalsScreen from './VitalsScreen';

export type SystemTab = 'vitals' | 'services';
type StatusInfo = { tone: Tone; text: string } | null;

function resolveTab(raw?: string): SystemTab | null {
  if (!raw) return null;
  const lower = raw.toLowerCase();
  if (lower === 'vitals') return 'vitals';
  if (lower === 'services' || lower === 'health') return 'services';
  return null;
}

export default function SystemScreen() {
  const t = useTheme();
  const isFocused = useIsFocused();
  const route = useRoute<any>();
  const requestedTab = resolveTab(route.params?.tab);
  const reqKey = requestedTab ? `${requestedTab}:${route.params?.tabRequest ?? ''}` : null;

  const [tabState, setTabState] = useState<{ tab: SystemTab; reqKey: string | null }>(() => ({
    tab: requestedTab ?? 'vitals',
    reqKey,
  }));
  const [statuses, setStatuses] = useState<{ vitals: StatusInfo; services: StatusInfo }>({
    vitals: null,
    services: null,
  });

  const tab: SystemTab = requestedTab && reqKey !== tabState.reqKey ? requestedTab : tabState.tab;

  const selectTab = useCallback(
    (next: SystemTab) => {
      setTabState({ tab: next, reqKey });
    },
    [reqKey],
  );

  const setVitalsStatus = useCallback((next: StatusInfo) => {
    setStatuses((prev) => {
      if (prev.vitals?.tone === next?.tone && prev.vitals?.text === next?.text) return prev;
      return { ...prev, vitals: next };
    });
  }, []);

  const setServicesStatus = useCallback((next: StatusInfo) => {
    setStatuses((prev) => {
      if (prev.services?.tone === next?.tone && prev.services?.text === next?.text) return prev;
      return { ...prev, services: next };
    });
  }, []);

  const activeStatus = statuses[tab];

  return (
    <Screen
      title="System"
      subtitle={
        activeStatus ? (
          <View style={st.statusRow}>
            <StatusDot tone={activeStatus.tone} />
            <Text style={{ color: t.textDim, fontSize: 12.5 }}>{activeStatus.text}</Text>
          </View>
        ) : undefined
      }
      scroll={false}
    >
      <View style={st.segmentWrap}>
        <Segmented<SystemTab>
          value={tab}
          onChange={selectTab}
          options={[
            { id: 'vitals', label: 'Vitals' },
            { id: 'services', label: 'Services' },
          ]}
        />
      </View>
      {tab === 'vitals' ? (
        <VitalsScreen embedded active={isFocused} onStatus={setVitalsStatus} />
      ) : (
        <HealthScreen embedded active={isFocused} onStatus={setServicesStatus} />
      )}
    </Screen>
  );
}

const st = StyleSheet.create({
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  segmentWrap: { paddingHorizontal: screenPad, paddingBottom: space(3) },
});
