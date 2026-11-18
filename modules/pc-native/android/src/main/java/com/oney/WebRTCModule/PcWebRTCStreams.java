package com.oney.WebRTCModule;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.bridge.ReactContext;

import org.webrtc.MediaStream;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.function.Consumer;

/**
 * Bridge from pc-native's TextureView renderer to react-native-webrtc's stream lookup.
 *
 * <p>This class lives in react-native-webrtc's package because {@link WebRTCModule#getStreamForReactTag}
 * and {@link ThreadUtils} are package-private. It is compiled into the app from pc-native, not patched
 * into node_modules, and it keeps the same executor rules as WebRTCView.
 */
public final class PcWebRTCStreams {
    private static final String TAG = "PcLive";

    private PcWebRTCStreams() {}

    /** Calls back on the main thread with the first video track of the stream, or null. */
    public static void getVideoTrack(Context context, String streamURL, Consumer<VideoTrack> callback) {
        if (!(context instanceof ReactContext)) {
            callback.accept(null);
            return;
        }
        WebRTCModule module = ((ReactContext) context).getNativeModule(WebRTCModule.class);
        if (module == null) {
            callback.accept(null);
            return;
        }

        ThreadUtils.runOnExecutor(() -> {
            VideoTrack track = null;
            try {
                MediaStream stream = module.getStreamForReactTag(streamURL);
                if (stream != null && !stream.videoTracks.isEmpty()) {
                    track = stream.videoTracks.get(0);
                }
            } catch (Throwable tr) {
                Log.w(TAG, "stream lookup failed for " + streamURL, tr);
            }
            final VideoTrack result = track;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(result));
        });
    }

    public static void addSink(VideoTrack track, VideoSink sink) {
        ThreadUtils.runOnExecutor(() -> {
            try {
                track.addSink(sink);
            } catch (Throwable tr) {
                Log.w(TAG, "addSink failed", tr);
            }
        });
    }

    public static void removeSink(VideoTrack track, VideoSink sink) {
        ThreadUtils.runOnExecutor(() -> {
            try {
                track.removeSink(sink);
            } catch (Throwable tr) {
                // mediaStreamTrackRelease may already have disposed the track.
                Log.w(TAG, "removeSink failed", tr);
            }
        });
    }
}
