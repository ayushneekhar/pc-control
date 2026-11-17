import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNavigationContainerRef, DarkTheme, DefaultTheme, LinkingOptions, NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { Alert, AppState, StyleSheet, Text, View } from 'react-native';
import { PcNative } from './modules/pc-native';
import { youtubeLink } from './src/lib/cams/youtube';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { SystemInsetsProvider } from './src/components/SystemInsets';
import { Button, Icon } from './src/components/ui';
import { applyOta, dismissOta, startOta, updateOffered, useOta } from './src/lib/otaStore';
import { startLiveIfDownloading } from './src/lib/live';
import { uploadCrashes } from './src/lib/crash';
import { askNotificationPermission, registerPush } from './src/lib/push';
import { pendingList, startRootStore, useRootStore } from './src/lib/rootStore';
import AgentRuleScreen from './src/screens/AgentRuleScreen';
import AgentViewScreen from './src/screens/AgentViewScreen';
import HealthScreen from './src/screens/HealthScreen';
import RootScreen from './src/screens/RootScreen';
import RoutesScreen from './src/screens/RoutesScreen';
import ServiceDetailScreen from './src/screens/ServiceDetailScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import VitalsScreen from './src/screens/VitalsScreen';
import { radius, screenPad, space, useTheme } from './src/theme';

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();
const navRef = createNavigationContainerRef<any>();

/** "Share → PC Control" from YouTube: open Cat cams → Music, which plays it on the laptop speakers. */
function takeShare() {
  let text: string | null = null;
  try {
    text = PcNative?.takeSharedText() ?? null;
  } catch {
    return;
  }
  if (!text) return;
  const link = youtubeLink(text);
  if (!link) {
    Alert.alert('Not a YouTube link', 'PC Control can play YouTube links on the laptop speakers. Share a video from the YouTube app.');
    return;
  }
  const go = () => navRef.navigate('Cams', { tab: 'music', share: link, shareId: Date.now() });
  if (navRef.isReady()) go();
  else setTimeout(() => (navRef.isReady() ? go() : setTimeout(go, 1000)), 300);
}

const linking: LinkingOptions<any> = {
  prefixes: ['pccontrol://'],
  config: {
    screens: {
      Tabs: {
        screens: {
          Routes: 'routes',
          Vitals: 'vitals',
          Root: 'root/:requestId?',
          Health: 'health',
          Settings: 'settings',
        },
      },
      Cams: 'cams',
      ServiceDetail: 'service/:unit',
    },
  },
};

const ICONS: Record<string, [string, string]> = {
  Routes: ['home-variant', 'home-variant-outline'],
  Vitals: ['chart-line', 'chart-line'],
  Root: ['shield-key', 'shield-key-outline'],
  Health: ['server', 'server'],
  Settings: ['cog', 'cog-outline'],
};

const TAB_LABEL: Record<string, string> = { Routes: 'Home', Vitals: 'Vitals', Root: 'Root', Health: 'Services', Settings: 'Settings' };

export default function App() {
  const t = useTheme();
  useEffect(() => startRootStore(), []);
  useEffect(() => startOta(), []);
  useEffect(() => void uploadCrashes(), []);
  useEffect(() => {
    // Notifications come through FCM: ask once for the permission (Android 13+), then (re-)register the token.
    askNotificationPermission().finally(() => registerPush());
  }, []);
  useEffect(() => {
    takeShare();
    const s = AppState.addEventListener('change', (st) => st === 'active' && takeShare());
    return () => s.remove();
  }, []);
  useEffect(() => {
    // Live download progress: also start it when the app is opened (or comes back) while something downloads.
    startLiveIfDownloading();
    const s = AppState.addEventListener('change', (st) => st === 'active' && startLiveIfDownloading());
    return () => s.remove();
  }, []);

  const navTheme = {
    ...(t.dark ? DarkTheme : DefaultTheme),
    colors: {
      ...(t.dark ? DarkTheme : DefaultTheme).colors,
      background: t.bg,
      card: t.card,
      border: t.border,
      primary: t.accent,
      text: t.text,
    },
  };

  return (
    <SafeAreaProvider>
      <SystemInsetsProvider>
        <NavigationContainer ref={navRef} theme={navTheme} linking={linking}>
          <StatusBar style={t.dark ? 'light' : 'dark'} />
          {/* 'fade' is 150 ms on Android (react-native-screens' rns_fade_* anims); the old slide was ~300 ms.
              animationDuration only applies on iOS, where it shortens the same fade. */}
          <Stack.Navigator screenOptions={{ headerShown: false, animation: 'fade', animationDuration: 150 }}>
            <Stack.Screen name="Tabs" component={Tabs} />
            {/* loaded on first open: react-native-webrtc stays out of app start */}
            <Stack.Screen name="AgentView" component={AgentViewScreen} />
            <Stack.Screen name="AgentRule" component={AgentRuleScreen} />
            <Stack.Screen name="ServiceDetail" component={ServiceDetailScreen} />
            <Stack.Screen
              name="Cams"
              getComponent={() => {
                // WebRTC options + crash marker, before react-native-webrtc's module is created (CamsWebrtc)
                try {
                  PcNative?.camsPrepare();
                } catch {
                  // defaults
                }
                // eslint-disable-next-line @typescript-eslint/no-require-imports
                return require('./src/screens/CamsScreen').default;
              }}
            />
          </Stack.Navigator>
          <OtaPrompt />
        </NavigationContainer>
      </SystemInsetsProvider>
    </SafeAreaProvider>
  );
}

/** The five tabs (the root stack's first screen). */
function Tabs() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const store = useRootStore();
  const waiting = pendingList(store).filter((r) => r.status === 'pending').length;
  return (
    <Tab.Navigator
      initialRouteName={waiting ? 'Root' : 'Routes'}
      screenOptions={({ route }) => ({
        headerShown: false,
        // Tabs switch instantly (v7's default is already 'none', but don't let a preset turn animation on), and a
        // blurred tab stops re-rendering (Root's store updates, etc.) until it is focused again.
        animation: 'none',
        freezeOnBlur: true,
        tabBarLabel: TAB_LABEL[route.name] ?? route.name,
        tabBarActiveTintColor: t.text,
        tabBarInactiveTintColor: t.textFaint,
        tabBarStyle: {
          backgroundColor: t.bg,
          borderTopColor: t.border,
          height: 68 + insets.bottom,
          paddingTop: 6,
          paddingBottom: insets.bottom,
        },
        tabBarLabelStyle: { fontSize: 11, fontWeight: '500' },
        tabBarIcon: ({ focused }) => (
          <View style={[st.tabIcon, focused && { backgroundColor: t.accentSoft }]}>
            <Icon name={ICONS[route.name][focused ? 0 : 1]} size={20} color={focused ? t.accent : t.textFaint} />
          </View>
        ),
      })}
    >
      <Tab.Screen name="Routes" component={RoutesScreen} />
      <Tab.Screen name="Vitals" component={VitalsScreen} />
      <Tab.Screen
        name="Root"
        component={RootScreen}
        options={{ tabBarBadge: waiting || undefined, tabBarBadgeStyle: { backgroundColor: t.warn, color: t.bg, fontWeight: '600' } }}
      />
      <Tab.Screen name="Health" component={HealthScreen} />
      <Tab.Screen name="Settings" component={SettingsScreen} />
    </Tab.Navigator>
  );
}

/** Apex-style "update available" card above the tab bar: Later, or Update (download, verify, restart). */
function OtaPrompt() {
  const t = useTheme();
  const ota = useOta();
  const insets = useSafeAreaInsets();
  if (!updateOffered(ota)) return null;
  const busy = ota.phase === 'downloading';
  return (
    <View style={[st.prompt, { bottom: insets.bottom + 64, backgroundColor: t.card }]}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Icon name="cloud-download-outline" size={20} color={t.accent} />
        <Text style={{ color: t.text, fontWeight: '600', fontSize: 15, flex: 1 }}>App update available</Text>
      </View>
      <Text style={{ color: t.textDim, fontSize: 13 }}>
        {busy
          ? `Downloading… ${Math.round(ota.progress * 100)}%`
          : ota.phase === 'error'
            ? ota.error
            : ota.check?.metadata?.releaseNotes || 'A newer version of the app is ready. Update now?'}
      </Text>
      <View style={{ flexDirection: 'row', gap: space(2) }}>
        <Button label="Later" kind="subtle" style={{ flex: 1 }} disabled={busy} onPress={dismissOta} />
        <Button label={ota.phase === 'error' ? 'Retry' : 'Update'} icon="restart" style={{ flex: 1 }} busy={busy} onPress={() => applyOta(true)} />
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  tabIcon: { width: 64, height: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  prompt: {
    position: 'absolute',
    left: screenPad,
    right: screenPad,
    padding: space(4),
    gap: space(3),
    borderRadius: radius.md,
    elevation: 8,
  },
});
