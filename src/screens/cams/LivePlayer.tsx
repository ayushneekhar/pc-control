// Live cat-cam video on a native TextureView renderer (pc-native's LivePlayerView). Unlike RTCView's
// SurfaceViewRenderer, this view honours parent transforms, so PinchZoom can scale/pan it. Home LiveTiles keep
// RTCView and stay unzoomed.
import { requireNativeView } from 'expo';
import { StyleProp, ViewStyle } from 'react-native';

type Props = {
  streamURL: string | null;
  style?: StyleProp<ViewStyle>;
};

const Native = requireNativeView<Props>('PcNative', 'LivePlayer');

export function LivePlayer({ streamURL, style }: Props) {
  return <Native streamURL={streamURL} style={style} />;
}
