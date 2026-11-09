// Live download progress: the native LiveDownloadService polls pc-api and keeps one Live Update notification
// (status bar chip / Now Bar on Android 16). pc-api wakes it with an FCM push when a download starts; this file
// covers the other way in: opening the app (or returning to it) while something is downloading.
import { PcNative } from '../../modules/pc-native';
import { get } from './api';

export type DownloadItem = {
  id: string;
  name: string;
  torrent: string;
  category: string;
  state: 'downloading' | 'stalled' | 'metadata' | 'queued' | 'checking' | 'moving';
  progress: number;
  speed: number;
  eta: number | null;
  size: number;
  done: number;
};

export type DownloadsReply = {
  enabled: boolean;
  qbt: 'ok' | 'down' | 'auth';
  downloads: DownloadItem[];
  total: { count: number; downloading: number; progress: number | null; speed: number; eta: number | null };
};

export function liveStatus() {
  if (!PcNative || typeof PcNative.liveStatus !== 'function') return null;
  try {
    return PcNative.liveStatus();
  } catch {
    return null;
  }
}

export type LiveStart = 'started' | 'running' | 'none' | 'off' | 'refused' | 'unavailable' | 'unreachable';

/** Start the live notification if something is downloading and the category is on. Never throws. */
export async function startLiveIfDownloading(): Promise<LiveStart> {
  const st = liveStatus();
  if (!st) return 'unavailable';
  if (st.running) return 'running';
  let r: DownloadsReply;
  try {
    r = await get<DownloadsReply>('/pc/api/downloads', 8000);
  } catch {
    return 'unreachable';
  }
  if (!r.enabled) return 'off';
  if (!r.downloads.length) return 'none';
  return PcNative!.liveStart(r.downloads.map((d) => d.id)) ? 'started' : 'refused';
}
