import { BottomTabBarProps, createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import {
  createNavigationContainerRef,
  DarkTheme,
  DefaultTheme,
  getStateFromPath,
  LinkingOptions,
  NavigationContainer,
} from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useState } from 'react';
import { Alert, AppState, Pressable, StyleSheet, Text, View } from 'react-native';
import { PcNative } from './modules/pc-native';
import { prepareCamsOnce } from './src/lib/cams/prepare';
import { parseSharedText, SharedTarget } from './src/lib/share';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { ShareSheet } from './src/components/ShareSheet';
import { SystemInsetsProvider } from './src/components/SystemInsets';
import { Button, Icon } from './src/components/ui';
import { applyOta, dismissOta, startOta, updateOffered, useOta } from './src/lib/otaStore';
import { normalizeDeepLinkPath } from './src/lib/linking';
import { startLiveIfDownloading } from './src/lib/live';
import { uploadCrashes } from './src/lib/crash';
import { askNotificationPermission, registerPush } from './src/lib/push';
import { pendingList, startRootStore, useRootStore } from './src/lib/rootStore';
import AgentRuleScreen from './src/screens/AgentRuleScreen';
import AgentSettingsScreen from './src/screens/AgentSettingsScreen';
import AgentViewScreen from './src/screens/AgentViewScreen';
import ChatScreen from './src/screens/ChatScreen';
import DownloadsScreen from './src/screens/DownloadsScreen';
import RootScreen from './src/screens/RootScreen';
import RoutesScreen from './src/screens/RoutesScreen';
import SeerrScreen from './src/screens/SeerrScreen';
import ServiceDetailScreen from './src/screens/ServiceDetailScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import SpaceScreen from './src/screens/SpaceScreen';
import SystemScreen from './src/screens/SystemScreen';
import { radius, screenPad, space, useTheme } from './src/theme';

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();
const navRef = createNavigationContainerRef<any>();

/** "Share → PC Control": YouTube -> cam-music, magnet -> qBittorrent confirm, IMDb/TMDB/Letterboxd -> Seerr confirm. */
function takeShare(onConfirm: (t: Exclude<SharedTarget, { kind: 'youtube' }>) => void) {
  let text: string | null = null;
  try {
    text = PcNative?.takeSharedText() ?? null;
  } catch {
    return;
  }
  if (!text) return;
  const parsed = parseSharedText(text);
  if (!parsed) {
    Alert.alert(
      'Unsupported link',
      'Share a YouTube video, magnet link, or IMDb/TMDB/Letterboxd link.',
    );
    return;
  }
  if (parsed.kind === 'youtube') {
    const go = () => navRef.navigate('Cams', { tab: 'music', share: parsed.url, shareId: Date.now() });
    if (navRef.isReady()) go();
    else setTimeout(() => (navRef.isReady() ? go() : setTimeout(go, 1000)), 300);
    return;
  }
  onConfirm(parsed);
}

const linking: LinkingOptions<any> = {
  prefixes: ['pccontrol://'],
  getStateFromPath: (path, options) => getStateFromPath(normalizeDeepLinkPath(path), options),
  config: {
    screens: {
      Tabs: {
        screens: {
          Routes: 'routes',
          Chat: 'chat',
          System: 'system/:tab?',
          Root: 'root/:requestId?',
          Settings: 'settings',
        },
      },
      Cams: 'cams',
      Downloads: 'downloads',
      Seerr: 'seerr',
      Space: 'space',
      ServiceDetail: 'service/:unit',
      AgentSettings: 'agent-settings',
    },
  },
};

const ICONS: Record<string, [string, string]> = {
  Routes: ['home-variant', 'home-variant-outline'],
  Chat: ['message-text', 'message-text-outline'],
  System: ['monitor-dashboard', 'monitor-dashboard'],
  Root: ['shield-key', 'shield-key-outline'],
  Settings: ['cog', 'cog-outline'],
};

const TAB_LABEL: Record<string, string> = { Routes: 'Home', Chat: 'Chat', System: 'System', Root: 'Root', Settings: 'Settings' };
/** Material 3 Expressive Navigation Bar content height (6pt top + 32pt pill + 4pt gap + 16pt label + 6pt bottom). */
const TAB_BAR_HEIGHT = 64;

export default function App() {
  const t = useTheme();
  const [shareTarget, setShareTarget] = useState<Exclude<SharedTarget, { kind: 'youtube' }> | null>(null);
  const checkShare = useCallback(() => takeShare(setShareTarget), []);
  useEffect(() => startRootStore(), []);
  useEffect(() => startOta(), []);
  useEffect(() => void uploadCrashes(), []);
  useEffect(() => {
    // Notifications come through FCM: ask once for the permission (Android 13+), then (re-)register the token.
    askNotificationPermission().finally(() => registerPush());
  }, []);
  useEffect(() => {
    checkShare();
    const s = AppState.addEventListener('change', (st) => st === 'active' && checkShare());
    return () => s.remove();
  }, [checkShare]);
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
            <Stack.Screen name="AgentSettings" component={AgentSettingsScreen} />
            <Stack.Screen name="Downloads" component={DownloadsScreen} />
            <Stack.Screen name="Seerr" component={SeerrScreen} />
            <Stack.Screen name="Space" component={SpaceScreen} />
            <Stack.Screen name="ServiceDetail" component={ServiceDetailScreen} />
            <Stack.Screen
              name="Cams"
              getComponent={() => {
                // WebRTC options + crash marker, before react-native-webrtc's module is created (CamsWebrtc)
                prepareCamsOnce();
                // eslint-disable-next-line @typescript-eslint/no-require-imports
                return require('./src/screens/CamsScreen').default;
              }}
            />
          </Stack.Navigator>
          <OtaPrompt />
          <ShareSheet target={shareTarget} onClose={() => setShareTarget(null)} />
        </NavigationContainer>
      </SystemInsetsProvider>
    </SafeAreaProvider>
  );
}

/** Material 3 compliant Bottom Navigation Bar (64×32 active pill, 24dp icons, 12sp labels, no overflowing ripple). */
function M3TabBar({ state, descriptors, navigation, insets }: BottomTabBarProps) {
  const t = useTheme();
  return (
    <View
      style={[
        st.tabBar,
        {
          backgroundColor: t.bg,
          borderTopColor: t.border,
          paddingBottom: 6 + insets.bottom,
        },
      ]}
    >
      {state.routes.map((route, index) => {
        const { options } = descriptors[route.key];
        const focused = state.index === index;
        const label =
          typeof options.tabBarLabel === 'string'
            ? options.tabBarLabel
            : (TAB_LABEL[route.name] ?? route.name);
        const badge = options.tabBarBadge;
        const iconPair = ICONS[route.name] ?? ['circle', 'circle-outline'];

        const onPress = () => {
          const event = navigation.emit({
            type: 'tabPress',
            target: route.key,
            canPreventDefault: true,
          });
          if (!focused && !event.defaultPrevented) {
            navigation.navigate(route.name, route.params);
          }
        };

        const onLongPress = () => {
          navigation.emit({
            type: 'tabLongPress',
            target: route.key,
          });
        };

        return (
          <Pressable
            key={route.key}
            onPress={onPress}
            onLongPress={onLongPress}
            accessibilityRole="tab"
            accessibilityState={{ selected: focused }}
            accessibilityLabel={options.tabBarAccessibilityLabel ?? label}
            style={st.tabItem}
          >
            {({ pressed }) => (
              <>
                <View style={st.tabPillWrap}>
                  <View
                    style={[
                      st.tabPill,
                      { backgroundColor: focused ? t.accentSoft : pressed ? t.ripple : 'transparent' },
                    ]}
                  >
                    {focused && pressed ? (
                      <View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: t.ripple }]} />
                    ) : null}
                    <Icon name={iconPair[focused ? 0 : 1]} size={24} color={focused ? t.accent : t.textFaint} />
                  </View>
                  {badge !== undefined && badge !== null && badge !== '' ? (
                    <View style={[st.tabBadge, { backgroundColor: t.warn }]}>
                      <Text style={[st.tabBadgeText, { color: t.bg }]} numberOfLines={1}>
                        {typeof badge === 'number' && badge > 99 ? '99+' : String(badge)}
                      </Text>
                    </View>
                  ) : null}
                </View>
                <Text
                  numberOfLines={1}
                  style={[
                    st.tabLabel,
                    {
                      color: focused ? t.text : t.textFaint,
                      fontWeight: focused ? '600' : '500',
                    },
                  ]}
                >
                  {label}
                </Text>
              </>
            )}
          </Pressable>
        );
      })}
    </View>
  );
}

/** The five tabs (the root stack's first screen). */
function Tabs() {
  const store = useRootStore();
  const waiting = pendingList(store).filter((r) => r.status === 'pending').length;
  const renderTabBar = useCallback((props: BottomTabBarProps) => <M3TabBar {...props} />, []);
  return (
    <Tab.Navigator
      initialRouteName={waiting ? 'Root' : 'Routes'}
      tabBar={renderTabBar}
      screenOptions={({ route }) => ({
        headerShown: false,
        // Tabs switch instantly (v7's default is already 'none', but don't let a preset turn animation on), and a
        // blurred tab stops re-rendering (Root's store updates, etc.) until it is focused again.
        animation: 'none',
        freezeOnBlur: true,
        tabBarLabel: TAB_LABEL[route.name] ?? route.name,
      })}
    >
      <Tab.Screen name="Routes" component={RoutesScreen} />
      <Tab.Screen name="System" component={SystemScreen} />
      <Tab.Screen name="Chat" component={ChatScreen} />
      <Tab.Screen
        name="Root"
        component={RootScreen}
        options={{ tabBarBadge: waiting || undefined }}
      />
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
    <View style={[st.prompt, { bottom: insets.bottom + TAB_BAR_HEIGHT + space(2), backgroundColor: t.card }]}>
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
  tabBar: {
    flexDirection: 'row',
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 6,
  },
  tabItem: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabPillWrap: {
    position: 'relative',
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabPill: {
    width: 64,
    height: 32,
    borderRadius: 16,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabBadge: {
    position: 'absolute',
    top: -2,
    right: 8,
    minWidth: 16,
    height: 16,
    borderRadius: 8,
    paddingHorizontal: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabBadgeText: {
    fontSize: 10,
    lineHeight: 12,
    fontWeight: '600',
    fontVariant: ['tabular-nums'],
  },
  tabLabel: {
    marginTop: 4,
    fontSize: 12,
    lineHeight: 16,
  },
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
