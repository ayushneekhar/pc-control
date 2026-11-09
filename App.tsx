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
import { askNotificationPermission, registerPush } from './src/lib/push';
import { pendingList, startRootStore, useRootStore } from './src/lib/rootStore';
import RootScreen from './src/screens/RootScreen';
import RoutesScreen from './src/screens/RoutesScreen';
import ServicesScreen from './src/screens/ServicesScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import VitalsScreen from './src/screens/VitalsScreen';
import { radius, space, useTheme } from './src/theme';

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
          Services: 'services',
          Settings: 'settings',
        },
      },
      Cams: 'cams',
    },
  },
};

const ICONS: Record<string, [string, string]> = {
  Routes: ['view-grid', 'view-grid-outline'],
  Vitals: ['heart-pulse', 'heart-pulse'],
  Root: ['shield-key', 'shield-key-outline'],
  Services: ['cog-play', 'cog-play-outline'],
  Settings: ['tune-variant', 'tune-variant'],
};

export default function App() {
  const t = useTheme();
  useEffect(() => startRootStore(), []);
  useEffect(() => startOta(), []);
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
          <Stack.Navigator screenOptions={{ headerShown: false }}>
            <Stack.Screen name="Tabs" component={Tabs} />
            {/* loaded on first open: react-native-webrtc stays out of app start */}
            <Stack.Screen
              name="Cams"
              // eslint-disable-next-line @typescript-eslint/no-require-imports
              getComponent={() => require('./src/screens/CamsScreen').default}
              options={{ animation: 'slide_from_right' }}
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
  const store = useRootStore();
  const waiting = pendingList(store).filter((r) => r.status === 'pending').length;
  return (
    <Tab.Navigator
      initialRouteName={waiting ? 'Root' : 'Routes'}
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: t.accent,
        tabBarInactiveTintColor: t.textFaint,
        tabBarStyle: { backgroundColor: t.card, borderTopColor: t.border },
        tabBarLabelStyle: { fontSize: 11.5, fontWeight: '600' },
        tabBarIcon: ({ focused, color, size }) => (
          <Icon name={ICONS[route.name][focused ? 0 : 1]} size={size - 1} color={color} />
        ),
      })}
    >
      <Tab.Screen name="Routes" component={RoutesScreen} />
      <Tab.Screen name="Vitals" component={VitalsScreen} />
      <Tab.Screen
        name="Root"
        component={RootScreen}
        options={{ tabBarBadge: waiting || undefined, tabBarBadgeStyle: { backgroundColor: t.warn, color: '#1A1300', fontWeight: '800' } }}
      />
      <Tab.Screen name="Services" component={ServicesScreen} />
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
    <View style={[st.prompt, { bottom: insets.bottom + 64, backgroundColor: t.card, borderColor: t.border }]}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2) }}>
        <Icon name="cloud-download-outline" size={20} color={t.accent} />
        <Text style={{ color: t.text, fontWeight: '700', fontSize: 15, flex: 1 }}>App update available</Text>
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
  prompt: {
    position: 'absolute',
    left: space(4),
    right: space(4),
    padding: space(4),
    gap: space(3),
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    elevation: 8,
  },
});
