import { ActivityIndicator, Image, StyleSheet, Text, View } from 'react-native';
import { RTCView } from 'react-native-webrtc';
import { stopHomeLive, useLiveCam } from '../lib/cams/useLiveCam';
import { useTheme } from '../theme';
import { Icon } from './ui';

export { stopHomeLive };

export function LiveTile({
  stream,
  label,
  poster,
  posterFailed,
  active,
  style,
}: {
  stream: string;
  label: string;
  poster: string | null;
  posterFailed: boolean;
  active: boolean;
  style?: object;
}) {
  const t = useTheme();
  const link = useLiveCam(active ? stream : null, 'video', { maxAttempts: 2, owner: 'home' });
  const live =
    link.stream && link.state.everPlayed && (link.state.phase === 'live' || link.state.phase === 'stalled')
      ? link.stream
      : null;
  return (
    <View style={[st.tile, style]}>
      {live ? (
        <RTCView streamURL={live.toURL()} objectFit="cover" zOrder={0} style={StyleSheet.absoluteFill} />
      ) : poster ? (
        <Image source={{ uri: poster }} style={StyleSheet.absoluteFill} resizeMode="cover" fadeDuration={0} />
      ) : (
        <View style={st.center}>
          {posterFailed ? (
            <Icon name="video-off" size={20} color={t.onMediaDim} />
          ) : (
            <ActivityIndicator size="small" color={t.onMediaDim} />
          )}
        </View>
      )}
      <View style={[st.name, { backgroundColor: t.mediaChip }]}>
        <Text style={{ color: t.onMedia, fontSize: 11 }} numberOfLines={1}>
          {label}
        </Text>
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  tile: { overflow: 'hidden', alignItems: 'stretch', justifyContent: 'flex-end' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  name: { paddingHorizontal: 6, paddingVertical: 3, borderRadius: 6, margin: 6, alignSelf: 'flex-start' },
});
