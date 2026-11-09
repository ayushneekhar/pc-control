// Native recording player (Media3 ExoPlayer on a TextureView, pc-native's RecPlayerView): one camrec HLS playlist,
// muted, hardware decode (H.265 for cat3hd). Imperative: seek(seconds, exact) and snapshot(label) on the ref.
import { requireNativeView } from 'expo';
import { forwardRef } from 'react';
import { StyleProp, ViewStyle } from 'react-native';

export type RecProgress = {
  /** media position, seconds */
  position: number;
  duration: number;
  /** idle | buffering | ready | ended */
  state: 'idle' | 'buffering' | 'ready' | 'ended';
  playing: boolean;
  seeking: boolean;
  /** MediaCodec decoder name, '' until one is initialised */
  decoder: string;
  width: number;
  height: number;
};

export type RecPlayerHandle = {
  /** Seek to a media position (s). exact=false: nearest keyframe (fast, for scrubbing previews). */
  seek(seconds: number, exact: boolean): Promise<void>;
  /** Save the frame on screen to Pictures/PC Control; resolves to its content:// uri. */
  snapshot(label: string): Promise<string>;
};

type Props = {
  /** playlist + where to start (s); a new object = a new load */
  source: { url: string; start: number } | null;
  playing: boolean;
  rate: number;
  style?: StyleProp<ViewStyle>;
  onProgress?: (e: { nativeEvent: RecProgress }) => void;
  onPlayerError?: (e: { nativeEvent: { message: string } }) => void;
};

const Native = requireNativeView<Props & { ref?: unknown }>('PcNative');

export const RecPlayer = forwardRef<RecPlayerHandle, Props>(function RecPlayer(props, ref) {
  return <Native {...props} ref={ref} />;
});
