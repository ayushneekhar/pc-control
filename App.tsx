import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { DarkTheme, DefaultTheme, LinkingOptions, NavigationContainer } from '@react-navigation/native';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { SystemInsetsProvider } from './src/components/SystemInsets';
import { Icon } from './src/components/ui';
import { pendingList, startRootStore, useRootStore } from './src/lib/rootStore';
import RootScreen from './src/screens/RootScreen';
import RoutesScreen from './src/screens/RoutesScreen';
import ServicesScreen from './src/screens/ServicesScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import VitalsScreen from './src/screens/VitalsScreen';
import { useTheme } from './src/theme';

const Tab = createBottomTabNavigator();

const linking: LinkingOptions<any> = {
  prefixes: ['pccontrol://'],
  config: {
    screens: {
      Routes: 'routes',
      Vitals: 'vitals',
      Root: 'root/:requestId?',
      Services: 'services',
      Settings: 'settings',
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
  const store = useRootStore();
  useEffect(() => startRootStore(), []);
  const waiting = pendingList(store).filter((r) => r.status === 'pending').length;

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
        <NavigationContainer theme={navTheme} linking={linking}>
          <StatusBar style={t.dark ? 'light' : 'dark'} />
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
        </NavigationContainer>
      </SystemInsetsProvider>
    </SafeAreaProvider>
  );
}
